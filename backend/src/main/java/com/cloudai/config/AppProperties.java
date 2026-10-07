package com.cloudai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@Data
@ConfigurationProperties(prefix = "cloudai")
public class AppProperties {
    private String dataDir = "../data";
    private String executorUrl = "http://127.0.0.1:8590";
    private String backendUrl = "http://127.0.0.1:8580";
    private String publicUrl = "http://localhost:5573";
    private String secret;
    private String internalToken;
    private String timezone = "Asia/Shanghai";

    public Path dataRoot() {
        return Path.of(dataDir).toAbsolutePath().normalize();
    }
}
