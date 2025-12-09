package com.jianbing.coupontest.utils;

/**
 * 分库分表路由工具类
 * 用于生成特定落库的 ID，确保自动化测试能查到数据
 */
public class ShardingUtil {

    // 总分片数 (根据 onecoupon.sql 确认共有 32 张表)
    private static final int SHARDING_COUNT = 32;

    /**
     * 生成一个必定落在 t_user_coupon_0 表的 userId
     * 原理：暴力计算，直到找到一个 id % 32 == 0
     */
    public static String generateUserIdForTable0() {
        long baseId = System.nanoTime();
        while (true) {
            // 假设后端算法是简单的取模 (实际需根据 TableHashModShardingAlgorithm 调整)
            // 根据源码: Math.abs(id.hashCode()) % 32
            // 这里我们直接用 long 模拟，确保生成的 ID 是纯数字字符串
            if (Math.abs(Long.valueOf(baseId).hashCode()) % SHARDING_COUNT == 0) {
                return String.valueOf(baseId);
            }
            baseId++;
        }
    }
}