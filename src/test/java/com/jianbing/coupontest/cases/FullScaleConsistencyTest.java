package com.jianbing.coupontest.cases;

import com.jianbing.coupontest.dao.mapper.UserCouponMapper;
import com.jianbing.coupontest.utils.UserDataGenerator;
import io.qameta.allure.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testng.Assert;
import org.testng.annotations.Test;

@Epic("全链路压测")
@Feature("5万用户抢1000券-最终一致性验证")
@Slf4j
public class FullScaleConsistencyTest extends BaseTest {

    @Autowired
    private UserCouponMapper userCouponMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // 【配置区域】请修改为你实际压测的 TemplateId
    private final Long TEMPLATE_ID = 1810966706881941507L;
    private final int EXPECTED_STOCK = 1000;
    private final int TABLE_SHARDING_COUNT = 32;

    @Test(priority = 1, description = "Step1: 生成测试数据 (JMeter用)")
    @Story("准备CSV数据")
    public void step1_prepareData() {
        UserDataGenerator.generateCsv();
        log.info(">>> 数据准备完毕，请运行 JMeter 脚本...");
    }

    @Test(priority = 2, description = "Step2: 验证 Redis 库存归零")
    @Story("Redis层数据校验")
    @Severity(SeverityLevel.CRITICAL)
    public void step2_verifyRedis() {
        String stockKey = "one-coupon_engine:coupon_template:stock:" + TEMPLATE_ID;
        String stockVal = stringRedisTemplate.opsForValue().get(stockKey);

        log.info(">>> Redis 剩余库存: {}", stockVal);

        // 断言库存应该已经空了 (<=0)
        Assert.assertNotNull(stockVal, "Redis key 不存在");
        Assert.assertTrue(Integer.parseInt(stockVal) <= 0, "Redis库存未扣完，存在超卖风险！");
    }

    @Test(priority = 3, description = "Step3: 验证 MySQL 分库分表数据总量")
    @Story("MySQL数据聚合校验")
    @Severity(SeverityLevel.BLOCKER)
    public void step3_verifyMySQL_Sharding() {
        log.info(">>> 开始统计 MySQL {} 张分表数据总量...", TABLE_SHARDING_COUNT);

        long totalCount = 0;

        // 循环统计 t_user_coupon_0 到 t_user_coupon_31
        for (int i = 0; i < TABLE_SHARDING_COUNT; i++) {
            String tableName = "t_user_coupon_" + i;
            try {
                // 使用 MyBatis-Plus Mapper 调用自定义方法
                Long count = userCouponMapper.countByTableName(tableName, TEMPLATE_ID);

                if (count != null && count > 0) {
                    log.info(">>> 表 [{}] 发现记录: {} 条", tableName, count);
                    totalCount += count;
                }
            } catch (Exception e) {
                // 如果表不存在或连接问题，记录警告但不中断（可能是某些环境只建了部分表）
                log.warn("查询表 {} 异常: {}", tableName, e.getMessage());
            }
        }

        log.info(">>> MySQL 32张表 最终汇总数量: {}", totalCount);

        // 核心验证：
        // 1. 绝对不能超过 1000 (超卖)
        // 2. 应该等于 1000 (除非 MQ 丢消息或消费积压)
        Assert.assertEquals(totalCount, (long)EXPECTED_STOCK, "数据库最终落库数量与预期不符！(可能存在超卖或消息丢失)");
        log.info(">>> \uD83C\uDF89 验证通过！高并发防超卖测试成功！");
    }
}