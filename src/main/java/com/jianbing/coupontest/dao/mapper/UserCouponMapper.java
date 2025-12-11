package com.jianbing.coupontest.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jianbing.coupontest.dao.entity.UserCouponDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserCouponMapper extends BaseMapper<UserCouponDO> {
    @Select("SELECT count(*) FROM ${tableName} WHERE coupon_template_id = ${templateId}")
    Long countByTableName(@Param("tableName") String tableName,@Param("templateId") Long templateId);
}
