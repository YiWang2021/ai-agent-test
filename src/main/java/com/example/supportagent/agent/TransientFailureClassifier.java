package com.example.supportagent.agent;

import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeoutException;

public final class TransientFailureClassifier {
    private TransientFailureClassifier() {}
    public static boolean isTransient(Throwable error) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = error; current != null && visited.add(current); current = current.getCause()) {
            if (current instanceof HttpStatusCodeException http) return retryStatus(http.getStatusCode().value());
            if (current instanceof WebClientResponseException http) return retryStatus(http.getStatusCode().value());
            if (current instanceof IOException || current instanceof TimeoutException) return true;
        }
        return false;
    }
    private static boolean retryStatus(int status) {
        return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }
}
