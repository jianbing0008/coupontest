package com.jianbing.coupontest.cases;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jianbing.coupontest.CoupontestApplication;
import com.jianbing.coupontest.dao.entity.UserCouponDO;
import com.jianbing.coupontest.dao.mapper.UserCouponMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.testng.AbstractTestNGSpringContextTests;

@SpringBootTest(classes = CoupontestApplication.class)
public class BaseTest extends AbstractTestNGSpringContextTests {

    @Autowired
    private UserCouponMapper userCouponMapper;

    /**
     * 查询数据库中某张券的领取数量(仅查询 one_coupon_0 库作为抽样验证)
     */
    public Long getDBReceivedCount(String couponTemplateId){
        try {
            LambdaQueryWrapper<UserCouponDO> queryWrapper = Wrappers.lambdaQuery(UserCouponDO.class)
                    .eq(UserCouponDO::getCouponTemplateId,Long.valueOf(couponTemplateId));
            return userCouponMapper.selectCount(queryWrapper);
        }catch (Exception e){
            return -1L;
        }

    }
}
