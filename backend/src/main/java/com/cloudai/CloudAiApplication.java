package com.cloudai;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
@MapperScan(basePackages = "com.cloudai", annotationClass = org.apache.ibatis.annotations.Mapper.class)
public class CloudAiApplication {
    public static void main(String[] args) {
        SpringApplication.run(CloudAiApplication.class, args);
    }
}
