package com.jianbing.coupontest.service;

import com.jianbing.coupontest.component.RestClient;
import com.jianbing.coupontest.config.EnvConfig;
import com.jianbing.coupontest.req.CouponTemplateRedeemReq;
import io.qameta.allure.Step;
import io.restassured.response.Response;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EngineApi {
    private final EnvConfig envConfig;
    private final RestClient restClient;

    @Step("API:MQ异步抢券 [User: {userId}]")
    public Response redeemByMQ(CouponTemplateRedeemReq req, String userId){
        var request = restClient.getRequest()
                .baseUri(envConfig.getEngineUrl())
                .body(req);
        if(!userId.isEmpty()){
            request.header("userId",userId);
        }
        return request
                .when()
                .post("/api/engine/user-coupon/redeem-mq");

    }
    @Step("API:Canal同步抢卷 [User: {userId}]")
    public Response redeemByCanal(CouponTemplateRedeemReq req, String userId){
        var request = restClient.getRequest()
                .baseUri(envConfig.getEngineUrl())
                .body(req);
        if(!userId.isEmpty()){
            request.header("userId",userId);
        }
        return request
                .when()
                .post("/api/engine/user-coupon/redeem");

    }
}
