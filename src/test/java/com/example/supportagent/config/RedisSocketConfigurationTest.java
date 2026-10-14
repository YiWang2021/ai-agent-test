package com.example.supportagent.config;

import io.lettuce.core.SocketOptions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RedisSocketConfigurationTest {
    @Test
    void unsupportedExtensionsFallBackWithoutDisablingKeepAliveOrChangingTimeout() {
        SocketOptions original = SocketOptions.builder()
                .connectTimeout(Duration.ofSeconds(7)).tcpNoDelay(false)
                .keepAlive(SocketOptions.KeepAliveOptions.builder().enable().build()).build();
        SocketOptions compatible = RedisSocketConfiguration.compatibleOptions(original, Set.of("SO_KEEPALIVE"));
        assertThat(compatible.isKeepAlive()).isTrue();
        assertThat(compatible.getConnectTimeout()).isEqualTo(Duration.ofSeconds(7));
        assertThat(compatible.isTcpNoDelay()).isFalse();
        // Lettuce exposes this flag only at package scope; it controls whether its
        // ConnectionBuilder sends the three unsupported channel options to Netty.
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(compatible, "isExtendedKeepAlive")).isFalse();
    }

    @Test
    void supportedPlatformRetainsCustomExtendedSettings() {
        SocketOptions original = SocketOptions.builder().keepAlive(SocketOptions.KeepAliveOptions.builder()
                .enable().idle(Duration.ofSeconds(30)).interval(Duration.ofSeconds(5)).count(4).build()).build();
        assertThat(RedisSocketConfiguration.compatibleOptions(original,
                Set.of("TCP_KEEPCOUNT", "TCP_KEEPIDLE", "TCP_KEEPINTERVAL"))).isSameAs(original);
    }

    @Test
    void explicitlyDisabledKeepAliveRemainsDisabled() {
        SocketOptions original = SocketOptions.builder().keepAlive(false).build();
        assertThat(RedisSocketConfiguration.compatibleOptions(original, Set.of()).isKeepAlive()).isFalse();
    }
}
