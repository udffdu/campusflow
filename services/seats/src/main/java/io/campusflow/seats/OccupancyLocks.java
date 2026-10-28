package io.campusflow.seats;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.stream.LongStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Package-private: all effective occupancy mutations must follow this database lock protocol. */
final class OccupancyLocks {
    private final JdbcTemplate jdbc;

    OccupancyLocks(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    void lockUserAndRoom(UUID userId, UUID roomId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Occupancy locks require an active transaction");
        }
        LongStream.of(key("user", userId), roomKey(roomId)).distinct().sorted().forEach(this::acquire);
    }

    private void acquire(long key) {
        jdbc.query("select pg_advisory_xact_lock(?)", (RowCallbackHandler) row -> {}, key);
    }

    static long roomKey(UUID roomId) { return key("room", roomId); }

    private static long key(String type, UUID id) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("campusflow:M1:" + type + ":" + id).getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest).getLong();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
