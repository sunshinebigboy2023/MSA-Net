package com.yupi.usercenter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "msa")
public class MsaProperties {

    private String baseUrl = "http://127.0.0.1:8000";

    private String uploadDir = "runtime/uploads";

    private Boolean asyncEnabled = true;

    private Long maxVideoSizeMb = 200L;

    private Integer submitLimit = 20;

    private Integer submitWindowSeconds = 60;

    private Integer maxRetryCount = 3;

    private Long staleTaskSeconds = 300L;

    private Integer retryDelayMs = 30000;

    private String callbackToken;
}
