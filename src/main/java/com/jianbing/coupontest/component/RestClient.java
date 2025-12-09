package com.jianbing.coupontest.component;

import io.qameta.allure.restassured.AllureRestAssured;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.specification.RequestSpecification;
import org.springframework.stereotype.Component;

@Component
public class RestClient {
    public RequestSpecification getRequest(){
        return RestAssured.given()
                .spec(new RequestSpecBuilder()
                        .setContentType("application/json")
                        .addFilter(new AllureRestAssured())
                        .build())
                .log().all();
    }
}
