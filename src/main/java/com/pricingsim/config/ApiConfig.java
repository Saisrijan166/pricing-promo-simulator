package com.pricingsim.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

import com.pricingsim.persistence.DecisionQueryDao;
import com.pricingsim.persistence.ExceptionDao;
import com.pricingsim.persistence.PricingInputDao;
import com.pricingsim.persistence.RunDao;
import com.pricingsim.pricing.PricingEngine;

/**
 * Beans for the REST API. Not active under the {@code offline} profile, where there is no database (generate, export,
 * repro-check).
 */
@Configuration
@Profile("!offline")
public class ApiConfig {

    @Bean
    PricingEngine pricingEngine(PricingPolicyProperties policy) {
        return new PricingEngine(policy.toPolicy());
    }

    @Bean
    PricingInputDao pricingInputDao(JdbcTemplate jdbc) {
        return new PricingInputDao(jdbc);
    }

    @Bean
    DecisionQueryDao decisionQueryDao(JdbcTemplate jdbc) {
        return new DecisionQueryDao(jdbc);
    }

    @Bean
    ExceptionDao exceptionDao(JdbcTemplate jdbc) {
        return new ExceptionDao(jdbc);
    }

    @Bean
    RunDao runDao(JdbcTemplate jdbc) {
        return new RunDao(jdbc);
    }
}
