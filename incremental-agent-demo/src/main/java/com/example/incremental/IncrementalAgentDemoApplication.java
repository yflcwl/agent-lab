package com.example.incremental;

import com.example.incremental.config.DemoProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(DemoProperties.class)
public class IncrementalAgentDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(IncrementalAgentDemoApplication.class, args);
    }
}

