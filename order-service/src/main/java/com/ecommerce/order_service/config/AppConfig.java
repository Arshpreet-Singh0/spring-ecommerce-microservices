package com.ecommerce.order_service.config;

import org.modelmapper.ModelMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class AppConfig {


    @Bean
    public ModelMapper modelMapper() {
        return new ModelMapper();
    }

    /** Single time source (JWT issue/expiry checks); tests replace it with a fixed clock. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
