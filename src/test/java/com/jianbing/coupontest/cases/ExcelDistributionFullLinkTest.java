package com.jianbing.coupontest.cases;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.RandomUtil;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.javafaker.Faker;
import com.jianbing.coupontest.dao.entity.UserCouponDO;
import com.jianbing.coupontest.dao.mapper.UserCouponMapper;
import com.jianbing.coupontest.req.CouponTaskReq;
import com.jianbing.coupontest.req.CouponTemplateReq;
import com.jianbing.coupontest.service.MerchantAdminApi;
import io.qameta.allure.*;
import io.restassured.response.Response;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.File;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Excel 批量分发全链路测试 (PO模式重构版)
 */
@Slf4j
@Epic("优惠券系统-全链路测试")
@Feature("场景：商家通过Excel批量分发优惠券")
public class ExcelDistributionFullLinkTest extends BaseTest {

    @Autowired
    private MerchantAdminApi merchantAdminApi; // 注入封装好的 API 服务

    @Autowired
    private UserCouponMapper userCouponMapper; // 注入 Mapper 进行白盒验证

    private final Faker faker = new Faker(Locale.CHINA);
    // 兼容各操作系统的临时目录
    private final String excelBasePath = Paths.get("").toAbsolutePath().getParent() + File.separator + "tmp";

    @Test(description = "创建模板 -> 生成Excel -> 提交任务 -> 验证DB落库")
    @Story("验证百万级分发功能的正确性与最终一致性")
    @Severity(SeverityLevel.CRITICAL)
    public void testExcelDistributionFullLink() throws InterruptedException {
        // Step 1: 准备环境 & 创建模板 (使用 PO 对象)
        String templateId = createTemplateStep();

        // Step 2: 造数 - 生成 Excel
        List<ExcelUser> mockUsers = new ArrayList<>();
        // 生成 20 条数据进行验证
        String excelPath = generateExcelFileStep(mockUsers, 20);

        // Step 3: 调用分发接口 (使用 PO 对象)
        submitDistributionTaskStep(templateId, excelPath);

        // Step 4: 异步轮询验证数据库 (白盒测试核心)
        verifyDataInDatabase(templateId, mockUsers);
    }

    // ================= 核心步骤封装 =================

    @Step("步骤1: 创建优惠券模板")
    @SneakyThrows
    private String createTemplateStep() {

        CouponTemplateReq req = CouponTemplateReq.builder()
                .name("NoFastJSON-Test-" + RandomUtil.randomString(5))
                .source(0)
                .target(1)
                .type(0)
                .validStartTime(DateUtil.now())
                .validEndTime(DateUtil.offsetDay(new Date(), 30).toString())
                .stock(100000)
                .receiveRule("{\"limitPerPerson\":1,\"usageInstructions\":\"MQ Test\"}")
                .consumeRule("{\"termsOfUse\":10,\"maximumDiscountAmount\":5,\"validityPeriod\":48}")
                .build();

        Response response = merchantAdminApi.createCouponTemplate(req);

        Assert.assertEquals(response.getStatusCode(), 200, "接口请求状态码非200");

        String templateId = response.jsonPath().getString("data");

        // 打印一下响应内容方便排查（可选）
        if (templateId == null) {
            log.error("创建模板失败，响应内容: {}", response.asString());
        }

        Assert.assertNotNull(templateId, "模板创建失败，ID为空");
        log.info(">>> 模板创建成功, ID: {}", templateId);
        return templateId;
    }

    @Step("步骤2: 生成 Excel 文件")
    private String generateExcelFileStep(List<ExcelUser> targetList, int count) {
        if (!FileUtil.exist(excelBasePath)) {
            FileUtil.mkdir(excelBasePath);
        }
        String fileName = "分发_Test_" + IdUtil.simpleUUID() + ".xlsx";
        String fullPath = excelBasePath + File.separator + fileName;

        for (int i = 0; i < count; i++) {
            targetList.add(ExcelUser.builder()
                    .userId(IdUtil.getSnowflakeNextIdStr()) // 模拟全新用户ID
                    .phone(faker.phoneNumber().cellPhone())
                    .mail(faker.internet().emailAddress())
                    .build());
        }

        EasyExcel.write(fullPath, ExcelUser.class)
                .sheet("优惠券推送列表")
                .doWrite(targetList);

        log.info(">>> Excel 生成完毕: {}, 共 {} 条数据", fullPath, count);
        return fullPath;
    }

    @Step("步骤3: 提交分发任务")
    private void submitDistributionTaskStep(String templateId, String filePath) {
        // 使用 Builder 构建 Task Request 对象
        CouponTaskReq req = CouponTaskReq.builder()
                .taskName("分发-Task-" + DateUtil.now())
                .fileAddress(filePath)
                .couponTemplateId(templateId)
                .notifyType("0,3")
                .sendType(0) // 0-立即发送
                .build();

        // 调用 Service
        Response response = merchantAdminApi.createCouponTask(req);

        Assert.assertEquals(response.getStatusCode(), 200, "分发任务接口调用失败");
        boolean success = JSON.parseObject(response.asString()).getBoolean("success");
        Assert.assertTrue(success, "分发任务业务失败: " + response.asString());
        log.info(">>> 分发任务已提交");
    }

    @Step("步骤4: 验证数据库最终一致性")
    private void verifyDataInDatabase(String templateId, List<ExcelUser> users) throws InterruptedException {
        log.info(">>> 开始轮询验证数据库落库情况 (Max 30s)...");
        boolean allSuccess = false;
        long maxWaitTime = 30000;
        long startTime = System.currentTimeMillis();

        // 验证逻辑：抽查第一条数据是否入库
        String checkUserId = users.get(0).getUserId();

        while (System.currentTimeMillis() - startTime < maxWaitTime) {
            LambdaQueryWrapper<UserCouponDO> query = Wrappers.lambdaQuery(UserCouponDO.class)
                    .eq(UserCouponDO::getUserId, Long.valueOf(checkUserId))
                    .eq(UserCouponDO::getCouponTemplateId, Long.valueOf(templateId));

            Long count = userCouponMapper.selectCount(query);

            if (count > 0) {
                allSuccess = true;
                log.info(">>> 验证成功! 用户 {} 已存在于表 user_coupon 中", checkUserId);
                break;
            }

            TimeUnit.MILLISECONDS.sleep(1000);
            System.out.print(".");
        }

        Assert.assertTrue(allSuccess, "超时未在数据库中查找到分发记录，测试失败！(可能是RocketMQ消费延迟或Excel解析失败)");
    }

    // ================= 内部类定义 (Excel 模型) =================
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ExcelUser {
        @ColumnWidth(30)
        @ExcelProperty("用户ID")
        private String userId;

        @ColumnWidth(20)
        @ExcelProperty("手机号")
        private String phone;

        @ColumnWidth(30)
        @ExcelProperty("邮箱")
        private String mail;
    }
}