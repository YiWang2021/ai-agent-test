package com.example.supportagent.memory;

import com.example.supportagent.security.RequestIdentity;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class ConversationMemoryTest {
    @Test void sameConversationIdCannotReadAnotherUsersOrTenantsHistory() {
        var memory = new InMemoryConversationMemory();
        memory.appendExchange("a", "u1", "same", "private question", "private answer");
        assertThat(memory.recent("a", "u1", "same", 12)).hasSize(2);
        assertThat(memory.recent("a", "u2", "same", 12)).isEmpty();
        assertThat(memory.recent("b", "u1", "same", 12)).isEmpty();
    }
    @Test void concurrentRequestsAreRejectedAndLeaseReleaseAllowsNextRequest() {
        var guard = new InMemoryConversationGuard();
        var user = new RequestIdentity("a", "u1", "USER");
        var first = guard.acquire(user, "same", Duration.ofSeconds(30));
        assertThatThrownBy(() -> guard.acquire(user, "same", Duration.ofSeconds(30))).isInstanceOf(ConversationBusyException.class);
        first.close(); first.close();
        try (var next = guard.acquire(user, "same", Duration.ofSeconds(30))) { assertThat(next).isNotNull(); }
    }
}
