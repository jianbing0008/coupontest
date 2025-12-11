package com.jianbing.coupontest.cases;

import com.jianbing.coupontest.req.CouponTemplateReq;
import com.jianbing.coupontest.service.MerchantAdminApi;
import io.qameta.allure.*;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Epic("系统健壮性测试")
@Feature("防重幂等性机制验证")
public class IdempotencyTest extends BaseTest {

    @Autowired
    private MerchantAdminApi merchantAdminApi;

    // 模拟并发数量 (例如 20 个线程同时点按钮)
    private static final int THREAD_COUNT = 20;

    @Test(description = "并发创建相同优惠券模板，验证防重锁有效性")
    @Story("防止用户重复提交")
    @Severity(SeverityLevel.CRITICAL)
    public void testDuplicateSubmitProtection() throws InterruptedException {
        // 1. 准备唯一的请求参数
        // 注意：根据 Aspect 逻辑，参数的 MD5 是锁 Key 的一部分，所以所有线程必须用同一个 Req 对象
        String uniqueName = "并发防重测试_" + System.currentTimeMillis();
        CouponTemplateReq req = CouponTemplateReq.builder()
                .name(uniqueName)
                .source(0) // 假设 0 是合法来源
                .target(0)
                .goods("TestGoods")
                .type(0)
                .validStartTime("2024-12-01 00:00:00")
                .validEndTime("2025-12-30 23:59:59")
                .stock(100)
                .receiveRule("{\"limitPerPerson\":1,\"usageInstructions\":\"Test\"}")
                .consumeRule("{\"termsOfUse\":10,\"maximumDiscountAmount\":5,\"validityPeriod\":48}")
                .build();

        // 2. 准备并发工具
        // 使用 CachedThreadPool 来确保能快速创建足够多的线程
        ExecutorService executorService = Executors.newCachedThreadPool();
        // 闭锁：用于让所有线程像赛马一样在起跑线等待，同时“发令枪”响
        CountDownLatch startGate = new CountDownLatch(1);
        // 闭锁：用于主线程等待所有子线程跑完
        CountDownLatch endGate = new CountDownLatch(THREAD_COUNT);

        // 线程安全的 List 收集结果
        List<Response> responses = Collections.synchronizedList(new ArrayList<>());

        log.info(">>> 开始并发防重测试，模拟 {} 个线程同时提交...", THREAD_COUNT);

        // 3. 提交任务到线程池
        for (int i = 0; i < THREAD_COUNT; i++) {
            executorService.submit(() -> {
                try {
                    // 所有线程在这里阻塞，等待主线程发令
                    startGate.await();

                    // --- 发起请求 ---
                    Response response = merchantAdminApi.createCouponTemplate(req);
                    responses.add(response);

                } catch (Exception e) {
                    log.error("请求发送异常", e);
                } finally {
                    endGate.countDown(); // 完成一个记一个数
                }
            });
        }

        // 4. "发令枪"响，所有线程瞬间同时发起请求
        startGate.countDown();

        // 5. 等待所有请求结束 (最多等 10 秒)
        boolean allFinished = endGate.await(10, TimeUnit.SECONDS);
        Assert.assertTrue(allFinished, "测试超时，部分线程未完成");
        executorService.shutdown();

        // 6. 结果分析 (核心验证逻辑)
        int successCount = 0;
        int failCount = 0;
        int duplicateErrorCount = 0;

        for (Response resp : responses) {
            String code = resp.jsonPath().getString("code");
            String msg = resp.jsonPath().getString("message");

            // 假设 "0" 是成功，具体要看你的 BaseErrorCode 定义
            if ("0".equals(code) && resp.statusCode() == 200) {
                successCount++;
                log.info("请求成功: Thread Response -> Code: {}", code);
            } else {
                failCount++;
                // 检查是否是被 Aspect 拦截的异常
                // Aspect 抛出 ClientException，通常会映射为 400 或特定错误码
                // 你需要确认 @NoDuplicateSubmit 注解里写的 message 是什么，通常默认是 "请勿重复提交"
                if (msg != null && (msg.contains("重复") || msg.contains("duplicate"))) {
                    duplicateErrorCount++;
                }
                log.warn("请求被拦截/失败: Status: {}, Msg: {}", resp.statusCode(), msg);
            }
        }

        log.info(">>> 测试结果汇总: 总请求 {}, 成功 {}, 失败 {}", THREAD_COUNT, successCount, failCount);

        // 7. 断言
        // 预期：只有 1 个成功，其余全部失败
        Assert.assertEquals(successCount, 1, "严重失败：锁未能阻止重复提交，成功了多次！");
        Assert.assertTrue(failCount > 0, "没有请求被拦截，可能是执行速度太慢锁已经释放了");

        // 可选：验证具体的错误信息是否符合预期
        // Assert.assertTrue(duplicateErrorCount > 0, "未捕获到'重复提交'特定的错误信息");
    }
}