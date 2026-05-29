package com.openscout.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    SimpleClientHttpRequestFactory clientHttpRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(8000);
        return factory;
    }

    @Bean
    RestClient.Builder restClientBuilder(SimpleClientHttpRequestFactory clientHttpRequestFactory) {
        return RestClient.builder().requestFactory(clientHttpRequestFactory);
    }
}
