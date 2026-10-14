package com.example.supportagent.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {
    @Test
    void unexpectedFailureLogsStackAndReturnsTraceWithoutInternalDetails() {
        Logger logger = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try (var ignored = MDC.putCloseable("traceId", "error-test-trace")) {
            var response = new ApiExceptionHandler().internal(
                    new IllegalStateException("internal database detail"));
            assertThat(response.getStatusCode().value()).isEqualTo(500);
            assertThat(response.getBody()).containsEntry("traceId", "error-test-trace")
                    .containsEntry("message", "request failed");
            assertThat(response.getBody().toString()).doesNotContain("internal database detail");
            var event = appender.list.get(0);
            assertThat(event.getMDCPropertyMap()).containsEntry("traceId", "error-test-trace");
            assertThat(event.getThrowableProxy().getMessage()).isEqualTo("internal database detail");
            assertThat(event.getThrowableProxy().getStackTraceElementProxyArray()).isNotEmpty();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
