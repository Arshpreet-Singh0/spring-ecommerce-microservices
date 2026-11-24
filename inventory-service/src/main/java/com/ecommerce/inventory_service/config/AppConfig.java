package com.ecommerce.inventory_service.config;

import org.modelmapper.ModelMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.EnableResilientMethods;

import java.time.Clock;

/** {@code @EnableResilientMethods} activates {@code @Retryable} (see InventoryReservationFacade). */
@Configuration
@EnableResilientMethods
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
