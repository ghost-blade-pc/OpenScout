package com.openscout;

import com.openscout.config.OpenScoutProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(OpenScoutProperties.class)
public class OpenScoutAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpenScoutAgentApplication.class, args);
    }
}
