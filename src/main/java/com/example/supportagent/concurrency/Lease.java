package com.example.supportagent.concurrency;

/** A renewable permit, not a guarantee that an external side effect can be rolled back. */
public interface Lease extends AutoCloseable {
    default void checkValid() {}
    default void onLost(Runnable callback) {}
    @Override void close();
}

