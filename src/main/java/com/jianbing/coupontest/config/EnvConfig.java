package com.jianbing.coupontest.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "test-config")
public class EnvConfig {
    private String merchantUrl;
    private String engineUrl;
    private int timeout;
}
