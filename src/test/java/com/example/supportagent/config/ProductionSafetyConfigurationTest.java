package com.example.supportagent.config;

import com.example.supportagent.security.AuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ProductionSafetyConfigurationTest {
    @Test void refusesMissingDistributedDependenciesAndDemoCredentials() {
        var environment=new MockEnvironment();
        var auth=new AuthProperties(List.of(new AuthProperties.Account("demo-key","a","u","USER")));
        assertThatThrownBy(()->new ProductionSafetyConfiguration(environment,auth)).isInstanceOf(IllegalStateException.class);
        environment.setActiveProfiles("ai","postgres","redis","semantic");
        assertThatThrownBy(()->new ProductionSafetyConfiguration(environment,auth)).isInstanceOf(IllegalStateException.class);
        var strong=new AuthProperties(List.of(new AuthProperties.Account("test-only-long-credential-1234567890","a","u","USER")));
        assertThatCode(()->new ProductionSafetyConfiguration(environment,strong)).doesNotThrowAnyException();
    }
}
