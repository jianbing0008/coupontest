package com.jianbing.coupontest.req;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 优惠券推送任务请求参数 (PO模式)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CouponTaskReq {
    /**
     * 任务名称
     */
    private String taskName;

    /**
     * Excel 文件绝对路径
     */
    private String fileAddress;

    /**
     * 优惠券模板ID
     */
    private String couponTemplateId;

    /**
     * 通知方式: 0-站内信, 1-弹窗, 2-邮箱， 3-手机短信...
     */
    @Builder.Default
    private String notifyType = "0,3";

    /**
     * 发送类型: 0-立即发送, 1-定时发送
     */
    @Builder.Default
    private Integer sendType = 0;

    /**
     * 定时发送时间 (仅sendType=1时有效)
     */
    private String sendTime;
}