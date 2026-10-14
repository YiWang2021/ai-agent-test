package com.example.supportagent.config;

import com.example.supportagent.domain.Device;
import com.example.supportagent.domain.DeviceRepository;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;

@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="support.seed.enabled", havingValue="true", matchIfMissing=true)
public class DemoDataConfiguration {

    @Bean
    ApplicationRunner seedDevices(DeviceRepository repository) {
        return args -> {
            if (repository.count() == 0) {
                repository.save(new Device("device-001", "tenant-a", "user-001", "CleanBot X1",
                        "OFFLINE", 18, 12, "E03", Instant.now().minusSeconds(900)));
                repository.save(new Device("device-002", "tenant-a", "user-001", "CleanBot X2",
                        "CHARGING", 76, 68, null, Instant.now().minusSeconds(20)));
                repository.save(new Device("device-900", "tenant-b", "user-900", "CleanBot Pro",
                        "CLEANING", 54, 80, null, Instant.now().minusSeconds(5)));
            }
        };
    }
}
