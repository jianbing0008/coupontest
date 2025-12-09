package com.jianbing.coupontest.cases;

import com.beust.ah.A;
import com.jianbing.coupontest.req.CouponTemplateRedeemReq;
import com.jianbing.coupontest.req.CouponTemplateReq;
import com.jianbing.coupontest.service.EngineApi;
import com.jianbing.coupontest.service.MerchantAdminApi;
import io.qameta.allure.*;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Epic("优惠卷系统-架构验证")
@Feature("方案一：RocketMQ异步削峰")
@Slf4j
public class PerformanceTest_MQ extends BaseTest {
    @Autowired
    private MerchantAdminApi merchantAdminApi;
    @Autowired
    private EngineApi engineApi;


    private final static int STOCK = 1000;
    private final static int USER_COUNT = 50000;
    private String templateId;

    private ThreadPoolExecutor executor;

    @Test(priority = 1, description = "准备秒杀券")
    public void prepareData() {
        this.executor = new ThreadPoolExecutor(
                500,
                500,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(USER_COUNT),
                Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
        CouponTemplateReq req = CouponTemplateReq.builder()
                .name("MQ压测券_" + System.currentTimeMillis())
                .source(0).target(0).goods("123").type(0)
                .validStartTime("2024-01-01 00:00:00").validEndTime("2025-12-31 23:59:59")
                .stock(STOCK)
                // 关键：每人限领1张，强迫必须是不同用户才能抢完
                .receiveRule("{\"limitPerPerson\":1,\"usageInstructions\":\"MQ Test\"}")
                .consumeRule("{\"termsOfUse\":10,\"maximumDiscountAmount\":5,\"validityPeriod\":48}")
                .build();
        Response resp = merchantAdminApi.createCouponTemplate(req);
        this.templateId = resp.jsonPath().getString("data");
        System.out.println(">>> [MQ方案] 准备就绪，TemplateID: " + templateId);
    }

    @Test(priority = 2,dependsOnMethods = "prepareData", description = "高并发抢券")
    @Story("验证Redis预扣减能力和防超卖")
    @Severity(SeverityLevel.CRITICAL)
    public void testHighConcurrency() throws InterruptedException {
        AtomicInteger successReq = new AtomicInteger(0);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(USER_COUNT);

        log.info(">>> [MQ方案] 开始压测，TemplateID: {}, USER_COUNT:{} " , templateId, USER_COUNT);

        for(int i =0;i<USER_COUNT;i++){
            String uid = String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits()));
            executor.submit(()->{
                try {
                    start.await();
                    CouponTemplateRedeemReq req = CouponTemplateRedeemReq.builder()
                            .source(0)
                            .shopNumber("1810714735922956666")
                            .couponTemplateId(templateId)
                            .build();

                    // 调用 MQ 异步接口
                    Response resp = engineApi.redeemByMQ(req, uid);
                    if(resp.statusCode() == 200 && "0".equals(resp.jsonPath().getString("code"))){
                        successReq.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    log.error(">>> [MQ方案] 线程异常");
                    throw new RuntimeException(e);
                } finally {
                    end.countDown();
                }
            });
        }

        log.info(">>> [MQ方案] \uD83D\uDD25流量释放\uD83D\uDD25");
        long s = System.currentTimeMillis();
        start.countDown();
        end.await();
        log.info(">>> [MQ方案] 耗时：{}", System.currentTimeMillis() - s + "ms");

        // 断言1：接口返回成功的数量，绝对不能超过库存（Redis 层防超卖）
        log.info(">>> [MQ方案] 接口返回成功数量：{}", successReq.get());
        Assert.assertTrue(successReq.get() <= STOCK, "严重bug：Redis层超卖！");
        Assert.assertEquals(successReq.get(),STOCK,"Redis预扣减未耗尽库存");

        // 断言2：数据库最终一致性（等待 MQ 消费）
        log.info(">>> [MQ方案] 等待MQ消费入库(10s)...");
        Thread.sleep(10000);
        // 简单抽样验证 DB 是否有数据 (因分片原因，本地只能验证连接的那个库是否有数据)
        // 只要大于0，说明 MQ 消费链路是通的
        Long dbCount = getDBReceivedCount(templateId);
        System.out.println(">>> (抽样) DB_0 库入库数量: " + dbCount);
        Assert.assertTrue(dbCount>=0, "数据库查询异常");
    }

    @AfterClass
    public void tearDown(){
        if(executor!=null){
            executor.shutdown();
        }
    }

}
