package com.jianbing.coupontest.cases;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jianbing.coupontest.dao.entity.UserCouponDO;
import com.jianbing.coupontest.dao.mapper.UserCouponMapper;
import com.jianbing.coupontest.req.CouponTemplateRedeemReq;
import com.jianbing.coupontest.req.CouponTemplateReq;
import com.jianbing.coupontest.service.EngineApi;
import com.jianbing.coupontest.service.MerchantAdminApi;
import com.jianbing.coupontest.utils.ShardingUtil;
import io.qameta.allure.*;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testng.Assert;
import org.testng.annotations.Test;


@Epic("优惠卷系统-架构验证")
@Feature("方案二：Canal同步强一致")
@Slf4j
public class ConsistencyTest_Canal extends BaseTest {

    @Autowired
    private MerchantAdminApi merchantAdminApi;
    @Autowired
    private EngineApi engineApi;
    @Autowired
    private UserCouponMapper userCouponMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // 读取 Redis 前缀配置
    private static final String REDIS_PREFIX = System.getProperty("framework.cache.redis.prefix", "");
    // Redis List Key 格式 (Canal 消费者最终会写入这个 Key)
    private static final String REDIS_LIST_KEY_PATTERN = REDIS_PREFIX + "one-coupon_engine:user-template-list:%s";

    private String templateId;

    @Test(priority = 1, description = "准备工作：创建用于Canal同步测试的优惠券")
    @Story("构造测试数据")
    public void prepareData() {
        CouponTemplateReq req = CouponTemplateReq.builder()
                .name("Canal强一致测试券_" + System.currentTimeMillis())
                .source(0).target(0).goods("CanalGoods").type(0)
                .validStartTime("2025-12-05 00:00:00")
                .validEndTime("2025-12-31 23:59:59")
                .stock(50) // 小库存即可
                .receiveRule("{\"limitPerPerson\":1,\"usageInstructions\":\"Canal Test\"}")
                .consumeRule("{\"termsOfUse\":10,\"maximumDiscountAmount\":5,\"validityPeriod\":48}")
                .build();

        Response resp = merchantAdminApi.createCouponTemplate(req);
        Assert.assertEquals(resp.getStatusCode(), 200);
        this.templateId = resp.jsonPath().getString("data");
        log.info(">>> [Canal方案] 准备就绪，TemplateID: {}", templateId);
    }

    @Test(priority = 2, dependsOnMethods = "prepareData", description = "验证同步接口的强一致性及Canal异步同步")
    @Story("核心验证：接口返回即落库，Redis异步同步")
    @Severity(SeverityLevel.CRITICAL)
    public void testStrongConsistencyAndCanalSync() throws InterruptedException {
        // 1. 构造必定落在 t_user_coupon_0 表的用户 ID
        String userId = ShardingUtil.generateUserIdForTable0();
        log.info(">>> [Canal方案] 构造用户ID: {} (定向路由至 Table_0)", userId);

        CouponTemplateRedeemReq req = CouponTemplateRedeemReq.builder()
                .source(0)
                .shopNumber("1810714735922956666")
                .couponTemplateId(templateId)
                .build();

        // 2. 调用同步接口 (方案二)
        // 预期：后端执行 DB 事务 -> 提交 -> 返回 200
        long start = System.currentTimeMillis();
        Response resp = engineApi.redeemByCanal(req, userId); // 假设 EngineApi 中已封装该接口
        long cost = System.currentTimeMillis() - start;

        log.info(">>> [Canal方案] 接口耗时: {} ms (含DB事务提交)", cost);

        // 基础断言：接口调用成功
        Assert.assertEquals(resp.getStatusCode(), 200);
        Assert.assertEquals(resp.jsonPath().getString("code"), "0", "抢券失败：" + resp.getBody().asString());

        // --------------------------------------------------------------------------------
        // 验证点一：数据库强一致性 (立即验证，不等待)
        // --------------------------------------------------------------------------------
        log.info(">>> [Canal方案] 步骤1：立即校验数据库 (Expecting Record Immediately)...");

        LambdaQueryWrapper<UserCouponDO> query = Wrappers.lambdaQuery(UserCouponDO.class)
                .eq(UserCouponDO::getUserId, Long.valueOf(userId))
                .eq(UserCouponDO::getCouponTemplateId, Long.valueOf(templateId));

        // 直接查询，如果不为 1 则说明事务没提交就返回了，属于严重 Bug
        Long dbCount = userCouponMapper.selectCount(query);
        log.info(">>> [Canal方案] 数据库查询结果: {}", dbCount);

        Assert.assertEquals(dbCount.intValue(), 1,
                "严重故障：[Canal方案] 接口返回成功但数据库未查询到记录！这违反了强一致性承诺。");

        // --------------------------------------------------------------------------------
        // 验证点二：Redis 最终一致性 (通过 Canal -> MQ -> Consumer 异步写入)
        // --------------------------------------------------------------------------------
        log.info(">>> [Canal方案] 步骤2：等待 Canal 异步同步 Redis (Max 10s)...");

        String redisListKey = String.format(REDIS_LIST_KEY_PATTERN, userId);
        boolean redisSynced = false;
        long redisStart = System.currentTimeMillis();

        // 轮询 Redis，等待 Binlog 监听器处理完毕
        while (System.currentTimeMillis() - redisStart < 10000) {
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(redisListKey))) {
                redisSynced = true;
                log.info(">>> [Canal方案] Redis 同步成功！耗时: {} ms", System.currentTimeMillis() - redisStart);
                break;
            }
            Thread.sleep(500);
        }

        Assert.assertTrue(redisSynced,
                "Canal 同步失败：10秒内 Redis 未检测到用户领券记录，请检查 Canal Server 或 MQ Binlog Consumer 状态。");

        log.info(">>> [Canal方案] 测试全部通过：DB强一致性 ✅ + Redis最终一致性 ✅");
    }
}