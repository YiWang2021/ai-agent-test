package com.example.supportagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import org.slf4j.MDC;

@Configuration
public class AsyncConfiguration {

    @Bean("agentExecutor")
    Executor agentExecutor(com.example.supportagent.concurrency.ConcurrencyProperties config) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(config.instanceLimit());
        executor.setMaxPoolSize(config.instanceLimit());
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("agent-stream-");
        executor.setTaskDecorator(task -> {
            var captured = MDC.getCopyOfContextMap();
            return () -> {
                var previous = MDC.getCopyOfContextMap();
                try {
                    if (captured == null) MDC.clear(); else MDC.setContextMap(captured);
                    task.run();
                } finally {
                    if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
                }
            };
        });
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
