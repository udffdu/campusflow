package io.campusflow.seats;

import static io.campusflow.seats.ReservationPersistenceException.Code.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** M1-owned persistence only; this is not an authenticated public API or a complete availability check. */
public final class ReservationStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final OccupancyLocks locks;
    private final ReservationPolicy policy;
    private final Clock clock;
    private final long lockTimeoutMillis;

    public ReservationStore(DataSource dataSource, ReservationPolicy policy, Clock clock, Duration lockTimeout) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
        this.locks = new OccupancyLocks(jdbc);
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lockTimeoutMillis = Objects.requireNonNull(lockTimeout, "lockTimeout").toMillis();
        if (lockTimeoutMillis <= 0 || lockTimeoutMillis > 30000) {
            throw new IllegalArgumentException("lockTimeout must be between 1ms and 30s");
        }
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public ReservationRecord create(CreateReservation request, String idempotencyKey) {
        Objects.requireNonNull(request, "request");
        return createValidated(new ReservationIntent(request.userId(),request.roomId(),request.seatId(),request.period()),
                idempotencyKey, () -> {}, request::resourceVersion).reservation();
    }

    public record CreationResult(ReservationRecord reservation,boolean replayed) {}

    /** Authorization runs under the locks even on replay; fresh metadata validation runs only on new requests. */
    public CreationResult createValidated(ReservationIntent request,String idempotencyKey,Runnable authorize,LongSupplier validateNew) {
        Objects.requireNonNull(request);Objects.requireNonNull(authorize);Objects.requireNonNull(validateNew);
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("Invalid idempotency key");
        }
        String hash = requestHash(request);
        try {
            return transaction.execute(ignored -> {
                jdbc.queryForObject("select set_config('lock_timeout', ?, true)", String.class,
                        lockTimeoutMillis + "ms");
                locks.lockUserAndRoom(request.userId(), request.roomId());
                authorize.run();
                var previous = jdbc.query("select request_hash, reservation_id from m1.reservation_requests "
                                + "where user_id=? and idempotency_key=?",
                        (row, index) -> new PriorRequest(row.getString(1), row.getObject(2, UUID.class)),
                        request.userId(), idempotencyKey);
                if (!previous.isEmpty()) {
                    var prior = previous.getFirst();
                    if (!prior.hash().equals(hash)) throw new ReservationPersistenceException(IDEMPOTENCY_CONFLICT);
                    return new CreationResult(findForUser(request.userId(), prior.id()).orElseThrow(),true);
                }
                long resourceVersion=validateNew.getAsLong();
                if(resourceVersion<1)throw new IllegalArgumentException("Invalid trusted resource version");
                policy.validate(request.period());
                if (conflicts("user_id", request.userId(), request.period())) {
                    throw new ReservationPersistenceException(USER_TIME_CONFLICT);
                }
                if (conflicts("seat_id", request.seatId(), request.period())) {
                    throw new ReservationPersistenceException(SEAT_TIME_CONFLICT);
                }
                UUID id = UUID.randomUUID();
                var createdAt = clock.instant();
                jdbc.update("""
                        insert into m1.reservations
                        (id,user_id,room_id,seat_id,start_at,end_at,status,source,resource_version,version,created_at,updated_at)
                        values (?,?,?,?,?,?,'CONFIRMED','M1',?,1,?,?)
                        """, id, request.userId(), request.roomId(), request.seatId(),
                        Timestamp.from(request.period().startAt()), Timestamp.from(request.period().endAt()),
                        resourceVersion, Timestamp.from(createdAt), Timestamp.from(createdAt));
                jdbc.update("insert into m1.reservation_requests values (?,?,?,?)",
                        request.userId(), idempotencyKey, hash, id);
                jdbc.update("""
                        insert into m1.outbox_events(event_id,reservation_id,event_type,payload,created_at)
                        values (?,?,'RESERVATION_CREATED',jsonb_build_object(
                          'reservationId',cast(? as text),'userId',cast(? as text),'roomId',cast(? as text),
                          'seatId',cast(? as text),'startAt',cast(? as text),'endAt',cast(? as text)),?)
                        """, UUID.randomUUID(), id, id.toString(), request.userId().toString(),
                        request.roomId().toString(), request.seatId().toString(),
                        request.period().startAt().toString(), request.period().endAt().toString(), Timestamp.from(createdAt));
                return new CreationResult(findForUser(request.userId(), id).orElseThrow(),false);
            });
        } catch (DataAccessException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && ("55P03".equals(sql.getSQLState())
                        || "40P01".equals(sql.getSQLState()) || "40001".equals(sql.getSQLState()))) {
                    throw new ReservationPersistenceException(STORAGE_UNAVAILABLE, exception);
                }
            }
            throw exception;
        }
    }

    /** The caller still must authenticate this user; a missing or foreign record is indistinguishable. */
    public Optional<ReservationRecord> findForUser(UUID userId, UUID reservationId) {
        return jdbc.query("select * from m1.reservations where user_id=? and id=?", ReservationStore::map,
                userId, reservationId).stream().findFirst();
    }

    public Optional<ReservationRecord> findForRequest(UUID userId,String key) {
        return jdbc.query("select r.* from m1.reservations r join m1.reservation_requests q on r.id=q.reservation_id "
                +"where q.user_id=? and q.idempotency_key=? and r.user_id=?",ReservationStore::map,userId,key,userId).stream().findFirst();
    }

    /**
     * Read-only M1 personal occupancy, one SQL statement/snapshot. The caller must authenticate userId.
     * No D, M2 or M3 guarantees; this read does not reserve seats or acquire transaction locks.
     */
    public PersonalOccupancy personalOccupancy(UUID userId, UUID roomId, TimeRange period) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(period, "period");
        try {
            var facts = jdbc.query("""
                    select user_id=? as user_conflict, case when room_id=? then seat_id end as occupied_seat
                    from m1.reservations
                    where (user_id=? or room_id=?) and status in ('CONFIRMED','CHECKED_IN')
                      and start_at < ? and end_at > ?
                    """, (row, index) -> new OccupancyFact(row.getBoolean("user_conflict"),
                            row.getObject("occupied_seat", UUID.class)),
                    userId, roomId, userId, roomId, Timestamp.from(period.endAt()), Timestamp.from(period.startAt()));
            return new PersonalOccupancy(facts.stream().anyMatch(OccupancyFact::userConflict),
                    facts.stream().map(OccupancyFact::seatId).filter(Objects::nonNull).collect(Collectors.toSet()));
        } catch (DataAccessException exception) {
            throw new ReservationPersistenceException(STORAGE_UNAVAILABLE, exception);
        }
    }

    public record PersonalOccupancy(boolean userConflict, Set<UUID> occupiedSeatIds) {
        public PersonalOccupancy {
            occupiedSeatIds = Set.copyOf(occupiedSeatIds);
        }
    }

    private record OccupancyFact(boolean userConflict, UUID seatId) {}

    private boolean conflicts(String field, UUID value, TimeRange period) {
        if (!field.equals("user_id") && !field.equals("seat_id")) throw new IllegalArgumentException("Invalid field");
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from m1.reservations where "
                        + field + "=? and status in ('CONFIRMED','CHECKED_IN') and start_at < ? and end_at > ?)",
                Boolean.class, value, Timestamp.from(period.endAt()), Timestamp.from(period.startAt())));
    }

    private static ReservationRecord map(ResultSet row, int index) throws SQLException {
        return new ReservationRecord(row.getObject("id", UUID.class), row.getObject("user_id", UUID.class),
                row.getObject("room_id", UUID.class), row.getObject("seat_id", UUID.class),
                new TimeRange(row.getTimestamp("start_at").toInstant(), row.getTimestamp("end_at").toInstant()),
                ReservationStatus.valueOf(row.getString("status")), row.getString("source"),
                row.getLong("resource_version"), row.getLong("version"), row.getTimestamp("created_at").toInstant());
    }

    private static String requestHash(ReservationIntent request) {
        String canonical = request.userId() + "\n" + request.roomId() + "\n" + request.seatId() + "\n"
                + request.period().startAt() + "\n" + request.period().endAt();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private record PriorRequest(String hash, UUID id) {}
}
