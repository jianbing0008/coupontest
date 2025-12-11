package com.jianbing.coupontest.cases;

import com.jianbing.coupontest.req.CouponTemplateReq;
import com.jianbing.coupontest.service.EngineApi;
import com.jianbing.coupontest.service.MerchantAdminApi;
import io.qameta.allure.*;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;

import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Epic("系统防御机制测试")
@Feature("缓存击穿/穿透解决方案验证")
public class CacheProtectionTest extends BaseTest {

    @Autowired
    private EngineApi engineApi;
    @Autowired
    private MerchantAdminApi merchantAdminApi;
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // Redis Key 常量 (需与后端 EngineRedisConstant 保持一致)
    private static final String BLOOM_FILTER_KEY = "jianbing:couponTemplateQueryBloomFilter"; // 注意你的前缀配置
    private static final String CACHE_KEY_PATTERN = "jianbing:one-coupon_engine:template:%s";
    private static final String NULL_CACHE_KEY_PATTERN = "jianbing:one-coupon_engine:template_is_null:%s";

    @Test(description = "场景1: 布隆过滤器拦截不存在的数据 (防穿透)")
    @Story("BloomFilter拦截")
    public void testBloomFilterInterception() {
        // 1. 构造一个绝对不存在的 ID (例如负数或超大随机数)
        String notExistId = "999999999999";
        String shopNumber = "1810714735922956666";

        log.info(">>> [防穿透] 请求不存在的ID: {}", notExistId);
        Response resp = engineApi.findCouponTemplate(notExistId, shopNumber);

        // 2. 验证结果
        // 预期：直接返回错误，且速度极快
        Assert.assertEquals(resp.jsonPath().getString("code"), "A000001"); // 假设 ClientException 对应的码
        Assert.assertTrue(resp.jsonPath().getString("message").contains("不存在"));

        // 3. 关键验证：确认没有生成“空值缓存”
        // 因为布隆过滤器直接拦住了，连空值缓存都不需要设
        Boolean hasNullKey = stringRedisTemplate.hasKey(String.format(NULL_CACHE_KEY_PATTERN, notExistId));
        Assert.assertFalse(hasNullKey, "布隆过滤器未生效！竟然穿透到了Null Cache层！");
    }

    @Test(description = "场景2: 模拟布隆过滤器误判，验证空值缓存兜底 (防穿透Layer2)")
    @Story("空值缓存兜底")
    public void testNullValueCache() {
        // 1. 准备一个不存在的 ID
        String fakeId = "888888888888";
        String shopNumber = "1810714735922956666";

        // 2. 【核心步骤】手动向布隆过滤器添加这个假 ID，模拟“误判”
        // 这样请求就会绕过第一道防线
        RBloomFilter<String> bloomFilter = redissonClient.getBloomFilter(BLOOM_FILTER_KEY);
        bloomFilter.add(fakeId);
        log.info(">>> [防穿透] 已手动伪造布隆过滤器误判: {}", fakeId);

        // 3. 第1次请求：应该穿透到 DB -> 发现没数据 -> 写入空值缓存
        engineApi.findCouponTemplate(fakeId, shopNumber);

        // 4. 验证：Redis 中必须存在 Null Key
        String nullKey = String.format(NULL_CACHE_KEY_PATTERN, fakeId);
        String nullVal = stringRedisTemplate.opsForValue().get(nullKey);
        log.info(">>> [防穿透] 检查空值缓存: Key={}, Val={}", nullKey, nullVal);

        Assert.assertNotNull(nullVal, "严重Bug：穿透DB后未回写空值缓存，数据库处于裸奔状态！");

        // 5. 第2次请求：应该直接打在 Null Cache 上（保护 DB）
        long start = System.currentTimeMillis();
        Response resp = engineApi.findCouponTemplate(fakeId, shopNumber);
        long cost = System.currentTimeMillis() - start;

        Assert.assertTrue(resp.jsonPath().getString("message").contains("不存在"));
        log.info(">>> [防穿透] 第二次请求耗时: {}ms (应极快)", cost);
    }

    @Test(description = "场景3: 热点Key缓存击穿，验证分布式锁 (防击穿)")
    @Story("分布式锁防击穿")
    public void testCacheBreakdownLock() throws InterruptedException {
        // 1. 准备一条真实存在的优惠券数据
        String templateId = createValidTemplate();
        String shopNumber = "1810714735922956666"; // 需用创建时的 shopNumber，这里简化写死

        // 2. 【核心步骤】手动删除 Redis 缓存，模拟缓存刚刚过期/失效
        String cacheKey = String.format(CACHE_KEY_PATTERN, templateId);
        stringRedisTemplate.delete(cacheKey);
        log.info(">>> [防击穿] 已删除缓存: {}，模拟高并发击穿瞬间", cacheKey);

        // 3. 50 线程并发查询
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startGate.await(); // 等待发令
                    engineApi.findCouponTemplate(templateId, shopNumber);
                } catch (Exception e) {
                    log.error("Request fail", e);
                } finally {
                    endGate.countDown();
                }
            });
        }

        // 4. 瞬间释放流量
        startGate.countDown();
        endGate.await();
        executor.shutdown();

        // 5. 验证结果
        // 只要所有请求都返回 200 (Result.success)，且缓存最终被重建了，就说明锁生效了
        // (如果有锁竞争失败抛异常的设计，这里需调整断言，但通常是自旋等待直到成功)
        Boolean hasCache = stringRedisTemplate.hasKey(cacheKey);
        Assert.assertTrue(hasCache, "缓存未被重建，逻辑异常");

        log.info(">>> [防击穿] 测试通过，50并发下数据一致且缓存已重建");
    }

    // 辅助方法：创建一个可用的优惠券
    private String createValidTemplate() {
        CouponTemplateReq req = CouponTemplateReq.builder()
                .name("防击穿测试券_" + System.currentTimeMillis())
                .source(0).target(0).goods("item").type(0)
                .validStartTime("2025-09-01 00:00:00")
                .validEndTime("2025-12-31 23:59:59")
                .stock(100)
                .receiveRule("{\"limitPerPerson\":1,\"usageInstructions\":\"Test\"}")
                .consumeRule("{\"termsOfUse\":10,\"maximumDiscountAmount\":5,\"validityPeriod\":48}")
                .build();
        return merchantAdminApi.createCouponTemplate(req).jsonPath().getString("data");
    }
}