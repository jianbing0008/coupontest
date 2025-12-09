package com.jianbing.coupontest.req;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CouponTemplateRedeemReq {
    private Integer source;
    private String shopNumber;
    private String couponTemplateId;

}
