package com.jianbing.coupontest.req;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CouponTemplateReq {
    private String name;
    private Integer source;
    private Integer target;
    private String goods;
    private Integer type;
    private String validStartTime;
    private String validEndTime;
    private Integer stock;
    private String receiveRule;
    private String consumeRule;
}
