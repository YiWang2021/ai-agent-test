package com.example.supportagent.rag;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Serialize an entire update INCLUDING commit. PostgreSQL lock is shared by all application instances. */
@Component
public class KnowledgeWriteCoordinator {
    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbc;
    private final boolean postgres;
    private final ReentrantLock[] stripes = new ReentrantLock[64];
    public KnowledgeWriteCoordinator(PlatformTransactionManager manager, JdbcTemplate jdbc, Environment environment) {
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setTimeout(30);
        this.jdbc = jdbc;
        this.postgres = environment.acceptsProfiles(Profiles.of("postgres"));
        for (int i=0;i<stripes.length;i++) stripes[i] = new ReentrantLock();
    }
    public <T> T write(String documentKey, Supplier<T> action) {
        ReentrantLock local = stripes[Math.floorMod(documentKey.hashCode(), stripes.length)];
        if (!local.tryLock()) throw new org.springframework.dao.CannotAcquireLockException("Knowledge document is being updated");
        try {
            return transactions.execute(status -> {
                if (postgres) {
                    jdbc.queryForObject("select set_config('lock_timeout', '3s', true)", String.class);
                    jdbc.queryForObject("select set_config('statement_timeout', '30s', true)", String.class);
                    jdbc.queryForObject("select pg_advisory_xact_lock(?)", Object.class, lockId(documentKey));
                }
                return action.get();
            });
        } finally { local.unlock(); }
    }
    static long lockId(String key) {
        try {
            return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(
                    ("support:knowledge:" + key).getBytes(StandardCharsets.UTF_8))).getLong();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
