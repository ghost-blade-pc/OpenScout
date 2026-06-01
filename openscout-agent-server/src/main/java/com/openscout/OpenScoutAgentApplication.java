package com.openscout;

import com.openscout.config.OpenScoutProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(OpenScoutProperties.class)
@MapperScan("com.openscout.persistence")
@EnableScheduling
public class OpenScoutAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpenScoutAgentApplication.class, args);
    }
}
