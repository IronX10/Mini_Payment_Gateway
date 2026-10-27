package com.dixansh.mini_payment_gateway.config;

import com.dixansh.mini_payment_gateway.service.SystemAccountService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One-time startup wiring. Warms the INR settlement/funding account so the very first top-up
 * (and any concurrent burst of them) finds it already present rather than racing to create it.
 * Runs after Flyway has migrated the schema.
 */
@Configuration
public class BootstrapConfig {

    @Bean
    CommandLineRunner warmFundingAccount(SystemAccountService systemAccountService) {
        return args -> systemAccountService.fundingAccount("INR");
    }
}
