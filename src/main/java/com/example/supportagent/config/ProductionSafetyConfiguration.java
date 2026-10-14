package com.example.supportagent.config;

import com.example.supportagent.security.AuthProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration
@Profile("production")
public class ProductionSafetyConfiguration {
    public ProductionSafetyConfiguration(Environment environment, AuthProperties auth) {
        for (String required : new String[]{"ai", "postgres", "redis", "semantic"}) {
            if (!environment.acceptsProfiles(Profiles.of(required))) {
                throw new IllegalStateException("production requires ai,postgres,redis,semantic profiles");
            }
        }
        if (auth.accounts() == null || auth.accounts().stream().noneMatch(a -> a.apiKey() != null && !a.apiKey().isBlank())) {
            throw new IllegalStateException("production requires configured credentials");
        }
        for (var account : auth.accounts()) {
            if (account.apiKey() != null && !account.apiKey().isBlank() && account.apiKey().length() < 32) {
                throw new IllegalStateException("production requires distinct randomly generated credentials of at least 32 characters");
            }
        }
    }
}
