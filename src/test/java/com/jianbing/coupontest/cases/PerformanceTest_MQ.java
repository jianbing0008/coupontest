package com.jianbing.coupontest.cases;

import com.jianbing.coupontest.dao.entity.UserCouponDO;
import com.jianbing.coupontest.dao.mapper.UserCouponMapper;
import com.jianbing.coupontest.req.CouponTemplateRedeemReq;
import com.jianbing.coupontest.req.CouponTemplateReq;
import com.jianbing.coupontest.service.EngineApi;
import com.jianbing.coupontest.service.MerchantAdminApi;
import com.jianbing.coupontest.utils.ShardingUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.qameta.allure.*;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.*;

@Epic("优惠卷系统-架构验证")
@Feature("方案一：RocketMQ异步削峰")
@Slf4j
public class PerformanceTest_MQ extends BaseTest {
    @Autowired
    private MerchantAdminApi merchantAdminApi;
    @Autowired
    private EngineApi engineApi;
    @Autowired
    private UserCouponMapper userCouponMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // 1. 读取 Redis 前缀配置
    private static final String REDIS_PREFIX = System.getProperty("framework.cache.redis.prefix", "");

    // 2. 定义 Key 格式
    // Limit Key: 由 Lua 脚本写入 (代表请求已接受)
    private static final String REDIS_LIMIT_KEY_PATTERN = REDIS_PREFIX + "one-coupon_engine:user-template-limit:%s_%s";

    // List Key: 由 MQ 消费者写入 (代表消费已完成) [关键]
    // 原 Key 是 one-coupon_engine:user-template-list:%s
    private static final String REDIS_LIST_KEY_PATTERN = REDIS_PREFIX + "one-coupon_engine:user-template-list:%s";

    private final static int STOCK = 800;
    private final static int USER_COUNT = 2000;
    private String templateId;

    private ThreadPoolExecutor executor;
    private Set<String> successUserIds = Collections.synchronizedSet(ConcurrentHashMap.newKeySet());

    @Test(priority = 1, description = "准备秒杀券")
    public void prepareData() {
        this.executor = new ThreadPoolExecutor(
                100, 100, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(USER_COUNT),
                Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()
        );

        CouponTemplateReq req = CouponTemplateReq.builder()
                .name("MQ压测券_" + System.currentTimeMillis())
                .source(0).target(0).goods("凤梨").type(0)
                .validStartTime("2025-12-03 00:00:00").validEndTime("2025-12-12 23:59:59")
                .stock(STOCK)
                .receiveRule("{\"limitPerPerson\":1,\"usageInstructions\":\"MQ Test\"}")
                .consumeRule("{\"termsOfUse\":10,\"maximumDiscountAmount\":5,\"validityPeriod\":48}")
                .build();
        Response resp = merchantAdminApi.createCouponTemplate(req);
        this.templateId = resp.jsonPath().getString("data");
        log.info(">>> [MQ方案] 准备就绪，TemplateID: {}", templateId);
    }

    @Test(priority = 2, dependsOnMethods = "prepareData", description = "高并发抢券")
    @Story("验证Redis预扣减能力、MQ异步削峰及最终一致性")
    @Severity(SeverityLevel.CRITICAL)
    public void testHighConcurrency() throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(USER_COUNT);

        log.info(">>> [MQ方案] 开始压测，TemplateID: {}, USER_COUNT:{} ", templateId, USER_COUNT);

        for (int i = 0; i < USER_COUNT; i++) {
            String uid = ShardingUtil.generateUserIdForTable0();
            executor.submit(() -> {
                try {
                    start.await();
                    CouponTemplateRedeemReq req = CouponTemplateRedeemReq.builder()
                            .source(0)
                            .shopNumber("1810714735922956666")
                            .couponTemplateId(templateId)
                            .build();
                    Response resp = engineApi.redeemByMQ(req, uid);
                    if (resp.statusCode() == 200 && "0".equals(resp.jsonPath().getString("code"))) {
                        successUserIds.add(uid);
                    }
                } catch (Exception e) {
                    log.error(">>> [MQ方案] 请求异常", e);
                } finally {
                    end.countDown();
                }
            });
        }

        log.info(">>> [MQ方案] \uD83D\uDD25流量释放\uD83D\uDD25");
        long s = System.currentTimeMillis();
        start.countDown();
        end.await();
        log.info(">>> [MQ方案] 请求处理耗时：{} ms", System.currentTimeMillis() - s);

        // --- 阶段一：验证 Redis 预扣减 (生产者层面) ---
        int successCount = successUserIds.size();
        log.info(">>> [MQ方案] 接口返回成功数量：{}", successCount);
        Assert.assertTrue(successCount <= STOCK, "严重bug：Redis层发生超卖");

        // 验证 Limit Key (证明 Lua 脚本执行成功)
        int redisLimitCount = 0;
        for (String uid : successUserIds) {
            String limitKey = String.format(REDIS_LIMIT_KEY_PATTERN, uid, templateId);
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(limitKey))) {
                redisLimitCount++;
            }
        }
        Assert.assertEquals(redisLimitCount, successCount, "Redis Limit记录数与接口成功数不一致");

        // --- 阶段二：智能等待 MQ 消费完成 (无需修改后端) ---
        // 原理：消费者成功后会写入 User Coupon List 到 Redis。
        // 我们轮询 Redis 检查这些 Key 是否存在，这比查数据库快得多，且能准确反映消费进度。

        log.info(">>> [MQ方案] 开始监听 Redis 消费结果 (最大等待 300s)...");
        long waitStart = System.currentTimeMillis();
        long maxWaitTime = 300 * 1000;
        boolean allConsumed = false;

        while (System.currentTimeMillis() - waitStart < maxWaitTime) {
            int consumedCount = 0;

            // 遍历所有成功的用户，检查他们的 Redis List 是否已生成
            for (String uid : successUserIds) {
                String listKey = String.format(REDIS_LIST_KEY_PATTERN, uid);
                // 只要 Key 存在，说明消费者已经运行到了第3步(写入缓存)，DB插入肯定也完成了
                if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(listKey))) {
                    consumedCount++;
                }
            }

            // 打印进度
            if (System.currentTimeMillis() % 2000 == 0) {
                log.info(">>> [消费进度] Redis确认: {}/{}", consumedCount, successCount);
            }

            if (consumedCount >= successCount) {
                allConsumed = true;
                log.info(">>> [MQ方案] 全部消息消费完成！耗时: {} ms", System.currentTimeMillis() - waitStart);
                break;
            }

            Thread.sleep(500); // 500ms 短轮询，反应灵敏
        }

        Assert.assertTrue(allConsumed, "等待 MQ 消费超时，部分用户未在 Redis 生成领券记录，可能存在消息丢失");

        // --- 阶段三：数据库最终一致性兜底校验 ---
        // 此时 Redis 已经确认数据都在了，DB 查询应该是一次过
        Long dbCount = getDBReceivedCount0(templateId);
        log.info(">>> [MQ方案] DB_0 表最终入库数量: {}", dbCount);
        Assert.assertEquals(dbCount.intValue(), successCount, "Redis显示已消费但MySQL数据缺失(严重)");

        // 抽样验证
        int checkLimit = 0;
        for (String uid : successUserIds) {
            if (++checkLimit > 20) break;
            LambdaQueryWrapper<UserCouponDO> query = Wrappers.lambdaQuery(UserCouponDO.class)
                    .eq(UserCouponDO::getUserId, Long.valueOf(uid))
                    .eq(UserCouponDO::getCouponTemplateId, Long.valueOf(templateId));
            Assert.assertTrue(userCouponMapper.selectCount(query) > 0, "用户 " + uid + " DB记录缺失");
        }
        log.info(">>> [MQ方案] 测试通过！");
    }

    private Long getDBReceivedCount0(String couponTemplateId) {
        LambdaQueryWrapper<UserCouponDO> queryWrapper = Wrappers.lambdaQuery(UserCouponDO.class)
                .eq(UserCouponDO::getCouponTemplateId, Long.valueOf(couponTemplateId));
        return userCouponMapper.selectCount(queryWrapper);
    }

    @AfterClass
    public void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}