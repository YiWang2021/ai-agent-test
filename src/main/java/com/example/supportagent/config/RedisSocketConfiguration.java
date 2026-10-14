package com.example.supportagent.config;

import io.lettuce.core.SocketOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.io.IOException;
import java.net.SocketOption;
import java.nio.channels.SocketChannel;
import java.util.Set;
import java.util.stream.Collectors;

@Configuration(proxyBeanMethods = false)
@Profile("redis")
public class RedisSocketConfiguration {
    private static final Logger log = LoggerFactory.getLogger(RedisSocketConfiguration.class);
    private static final Set<String> EXTENDED_KEEP_ALIVE =
            Set.of("TCP_KEEPCOUNT", "TCP_KEEPIDLE", "TCP_KEEPINTERVAL");

    @Bean
    LettuceClientOptionsBuilderCustomizer compatibleRedisKeepAlive() throws IOException {
        // Probe the actual JDK socket, not merely whether ExtendedSocketOptions exists.
        // Opening an unconnected channel does not contact Redis or any remote host.
        Set<String> supported;
        try (SocketChannel channel = SocketChannel.open()) {
            supported = channel.supportedOptions().stream()
                    .map(SocketOption::name).collect(Collectors.toSet());
        }
        if (!supported.containsAll(EXTENDED_KEEP_ALIVE)) {
            log.info("Redis TCP keepalive: extended options unavailable; using standard SO_KEEPALIVE with OS defaults");
        }
        return builder -> {
            SocketOptions current = builder.build().getSocketOptions();
            builder.socketOptions(compatibleOptions(current, supported));
        };
    }

    static SocketOptions compatibleOptions(SocketOptions current, Set<String> supported) {
        if (supported.containsAll(EXTENDED_KEEP_ALIVE)) return current;
        // Lettuce's boolean overload disables only extended tuning. Preserve the existing
        // enabled flag, connection timeout, TCP_NODELAY and other socket settings.
        return current.mutate().keepAlive(current.isKeepAlive()).build();
    }
}
