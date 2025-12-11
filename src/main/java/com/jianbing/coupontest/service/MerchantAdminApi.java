package com.jianbing.coupontest.service;

import com.jianbing.coupontest.component.RestClient;
import com.jianbing.coupontest.config.EnvConfig;
import com.jianbing.coupontest.req.CouponTaskReq;
import com.jianbing.coupontest.req.CouponTemplateRedeemReq;
import com.jianbing.coupontest.req.CouponTemplateReq;
import io.qameta.allure.Step;
import io.restassured.response.Response;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MerchantAdminApi {

    private final RestClient restClient;
    private final EnvConfig envConfig;

    @Step("API: 创建优惠卷模版")
    public Response createCouponTemplate(CouponTemplateReq req){
        return restClient.getRequest()
                .baseUri(envConfig.getMerchantUrl())
                .body(req)
                .when()
                .post("/api/merchant-admin/coupon-template/create");
    }

    @Step("API: 创建优惠券分发任务")
    public Response createCouponTask(CouponTaskReq req) {
        return restClient.getRequest()
                .baseUri(envConfig.getMerchantUrl())
                .body(req)
                .when()
                .post("/api/merchant-admin/coupon-task/create");
    }
}
