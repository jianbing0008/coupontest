package com.jianbing.coupontest.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("t_user_coupon_0")
public class UserCouponDO {
    private Long id;
    private Long userId;
    private Long couponTemplateId;
    // 其他字段暂时用不到，可以不写，MP 只会映射存在的字段
}
