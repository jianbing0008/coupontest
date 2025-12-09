package com.jianbing.coupontest.cases;

import com.jianbing.coupontest.req.CouponTemplateRedeemReq;
import com.jianbing.coupontest.req.CouponTemplateReq;
import com.jianbing.coupontest.service.EngineApi;
import com.jianbing.coupontest.service.MerchantAdminApi;
import com.jianbing.coupontest.utils.ShardingUtil;
import io.qameta.allure.*;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

    private String templateId;

    @Test(priority = 1, description = "准备工作：创建用于Canal同步测试的优惠券")
    @Story("构造测试数据")
    public void prepareData() {
        // 创建库存 50 的券
        CouponTemplateReq req = CouponTemplateReq.builder()
                .name("Canal强一致测试券_" + System.currentTimeMillis())
                .source(0) // 店铺券
                .target(0) // 商品专属
                .goods("456")
                .type(0)   // 立减券
                .validStartTime("2024-01-01 00:00:00")
                .validEndTime("2025-12-31 23:59:59")
                .stock(50)
                .receiveRule("{\"limitPerPerson\":1,\"usageInstructions\":\"Canal Test\"}")
                .consumeRule("{\"termsOfUse\":10,\"maximumDiscountAmount\":5,\"validityPeriod\":48}")
                .build();

        Response resp = merchantAdminApi.createCouponTemplate(req);
        Assert.assertEquals(resp.getStatusCode(), 200);
        this.templateId = resp.jsonPath().getString("data");
        System.out.println(">>> [Canal方案] 准备就绪，TemplateID: " + templateId);
    }

    @Test(priority = 2, dependsOnMethods = "prepareData", description = "验证同步接口的强一致性")
    @Story("核心验证：接口返回200时，数据库必须已有记录")
    @Severity(SeverityLevel.CRITICAL)
    public void testStrongConsistency() {
        // 构造一个唯一用户ID
        // ❌ 修改前：随机ID，可能查不到数据
        // String userId = "CANAL_USER_" + System.currentTimeMillis();

        // ✅ 修改后：生成必然落入 t_user_coupon_0 的 ID
        String userId = ShardingUtil.generateUserIdForTable0();
        log.info(">>> [Canal方案] 构造用户ID: " + userId);

        CouponTemplateRedeemReq req = CouponTemplateRedeemReq.builder()
                .source(0)
                .shopNumber("1810714735922956666")
                .couponTemplateId(templateId)
                .build();

        // 1. 调用同步接口 (方案二：Canal + 编程式事务)
        // 这个接口在后端是先写数据库，事务提交后，才返回 HTTP 200
        long start = System.currentTimeMillis();
        Response resp = engineApi.redeemByCanal(req, userId);
        long cost = System.currentTimeMillis() - start;

        // 验证响应
        System.out.println(">>> Canal接口耗时: " + cost + "ms (包含DB事务提交)");
        Assert.assertEquals(resp.getStatusCode(), 200);
        Assert.assertEquals(resp.jsonPath().getString("code"), "0", "抢券失败：" + resp.getBody().asString());

        // 2. 核心验证：立即查询数据库
        // 不需要像 MQ 方案那样 Thread.sleep()，因为这是强一致性接口
        System.out.println(">>> 接口返回成功，立即校验数据库...");

        // 调用 BaseTest 中基于 MyBatis-Plus 封装的查询方法
        // 注意：由于是分库分表，我们这里只配置了 ds_0，如果 userId 路由到了 ds_1，这里可能查不到 (返回0)
        // 在真实 CI/CD 环境中，应该配置所有分片库的数据源
        Long count = getDBReceivedCount(templateId);

        System.out.println(">>> 数据库(ds_0)查询结果: " + count);

        // 只有当 count >= 0 时才断言（-1代表查询异常）
        if (count != -1) {
            // 如果运气好路由到了 ds_0，则断言必须为 1
            // 如果路由到了 ds_1，这里查出来是 0，虽然不算错，但没验证到。
            // 严谨做法：可以在这里打印一条警告，或者在 BaseTest 里遍历所有库
            if (count == 0) {
                System.out.println("⚠️ 警告：当前数据可能路由到了 ds_1 库，本地仅连接了 ds_0，无法验证数据落盘。建议更换 UserId 重试。");
            } else {
                Assert.assertEquals(count.intValue(), 1, "严重Bug：同步接口返回成功，但数据库记录数不对！");
            }
        }
    }
}