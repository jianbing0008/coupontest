package com.jianbing.coupontest.utils;

import com.github.javafaker.Faker;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@Slf4j
public class UserDataGenerator {

    private static final String CSV_FILE_PATH = "users_5w.csv";
    private static final int USER_COUNT = 50000;

    public static void generateCsv() {
        // 使用中文 Locale，虽然生成 ID 用不到，但好习惯
        Faker faker = new Faker(new Locale("zh-CN"));
        Set<String> userIds = new HashSet<>(USER_COUNT);

        log.info(">>> 开始生成 {} 个唯一用户ID...", USER_COUNT);

        while (userIds.size() < USER_COUNT) {
            // 生成 19 位纯数字 ID，模拟雪花算法
            long uid = faker.number().randomNumber(19, true);
            userIds.add(String.valueOf(uid));
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(CSV_FILE_PATH))) {
            writer.write("userId");
            writer.newLine();
            for (String uid : userIds) {
                writer.write(uid);
                writer.newLine();
            }
            log.info(">>> CSV生成成功！路径: {}", CSV_FILE_PATH);
        } catch (IOException e) {
            log.error("生成CSV失败", e);
            throw new RuntimeException(e);
        }
    }
}