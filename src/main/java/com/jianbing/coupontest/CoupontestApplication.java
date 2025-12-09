package com.jianbing.coupontest;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.jianbing.coupontest.dao.mapper")
public class CoupontestApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoupontestApplication.class, args);
    }

}
