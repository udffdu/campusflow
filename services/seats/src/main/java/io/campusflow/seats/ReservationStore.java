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
import java.time.Instant;
import java.util.List;
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
    public record CheckInResult(ReservationRecord reservation, boolean idempotent) {}
    public record UsageResult(ReservationRecord reservation, boolean idempotent) {}
    public record UsageSession(ReservationRecord reservation, Instant checkedInAt,
                               Instant completedAt, Instant serverTime) {}
    public enum LifecycleResult { TRANSITIONED, NOT_DUE, NOT_APPLICABLE }

    private record CheckInAttempt(ReservationRecord reservation, boolean idempotent, CheckInException.Code error) {
        static CheckInAttempt failed(CheckInException.Code error) { return new CheckInAttempt(null, false, error); }
    }
    private record RateLimit(Instant windowStartedAt, int failures, Instant blockedUntil) {}

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

    /**
     * Authenticated owner check-in. The preliminary row only supplies immutable lock keys; ownership,
     * authorization, status, version and code are all checked again while holding the locks.
     */
    public CheckInResult checkIn(UUID userId, UUID reservationId, String code,
                                 DailyCheckInCode codes, Runnable authorize) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(codes, "codes");
        Objects.requireNonNull(authorize, "authorize");
        try {
            ReservationRecord preliminary = findForUser(userId, reservationId)
                    .orElseThrow(() -> new CheckInException(CheckInException.Code.RESERVATION_NOT_FOUND));
            CheckInAttempt attempt = transaction.execute(ignored -> {
                jdbc.queryForObject("select set_config('lock_timeout', ?, true)", String.class,
                        lockTimeoutMillis + "ms");
                locks.lockUserAndRoom(preliminary.userId(), preliminary.roomId());
                authorize.run();
                ReservationRecord current = findForUser(userId, reservationId)
                        .orElseThrow(() -> new CheckInException(CheckInException.Code.RESERVATION_NOT_FOUND));
                if (!current.roomId().equals(preliminary.roomId())) {
                    throw new IllegalStateException("预约不可变关联已改变");
                }
                if (current.status() == ReservationStatus.CHECKED_IN) {
                    return new CheckInAttempt(current, true, null);
                }
                if (current.status() != ReservationStatus.CONFIRMED) {
                    return CheckInAttempt.failed(CheckInException.Code.CHECK_IN_STATE_CONFLICT);
                }
                Instant now = clock.instant();
                if (now.isBefore(current.period().startAt().minus(Duration.ofMinutes(20)))) {
                    return CheckInAttempt.failed(CheckInException.Code.CHECK_IN_TOO_EARLY);
                }
                if (!now.isBefore(current.period().startAt().plus(Duration.ofMinutes(15)))) {
                    transitionNoShow(current, now);
                    return CheckInAttempt.failed(CheckInException.Code.CHECK_IN_DEADLINE_PASSED);
                }
                RateLimit limit = rateLimit(userId, reservationId);
                if (limit != null && limit.blockedUntil() != null && limit.blockedUntil().isAfter(now)) {
                    return CheckInAttempt.failed(CheckInException.Code.CHECK_IN_RATE_LIMITED);
                }
                if (!codes.matchesCurrent(current.roomId(), code)) {
                    return CheckInAttempt.failed(recordFailure(userId, reservationId, limit, now));
                }

                long nextVersion = current.version() + 1;
                int changed = jdbc.update("""
                        update m1.reservations set status='CHECKED_IN',version=?,updated_at=?
                        where id=? and user_id=? and status='CONFIRMED' and version=?
                        """, nextVersion, Timestamp.from(now), reservationId, userId, current.version());
                if (changed != 1) throw new IllegalStateException("签到状态条件更新失败");
                jdbc.update("""
                        insert into m1.reservation_transitions
                        (transition_id,reservation_id,from_status,to_status,reason,actor_user_id,reservation_version,occurred_at)
                        values (?,?,'CONFIRMED','CHECKED_IN','USER_CHECK_IN',?,?,?)
                        """, UUID.randomUUID(), reservationId, userId, nextVersion, Timestamp.from(now));
                jdbc.update("""
                        insert into m1.outbox_events(event_id,reservation_id,event_type,payload,created_at)
                        values (?,?,'RESERVATION_CHECKED_IN',jsonb_build_object(
                          'reservationId',cast(? as text),'userId',cast(? as text),'roomId',cast(? as text),
                          'seatId',cast(? as text),'checkedInAt',cast(? as text),'version',?),?)
                        """, UUID.randomUUID(), reservationId, reservationId.toString(), userId.toString(),
                        current.roomId().toString(), current.seatId().toString(), now.toString(), nextVersion,
                        Timestamp.from(now));
                jdbc.update("delete from m1.checkin_rate_limits where user_id=? and reservation_id=?",
                        userId, reservationId);
                supersedeReminders(reservationId, now);
                return new CheckInAttempt(findForUser(userId, reservationId).orElseThrow(), false, null);
            });
            if (attempt == null) throw new IllegalStateException("签到事务未返回结果");
            if (attempt.error() != null) throw new CheckInException(attempt.error());
            return new CheckInResult(attempt.reservation(), attempt.idempotent());
        } catch (DataAccessException exception) {
            throw persistenceFailure(exception);
        }
    }

    /** Authenticated owner ends an active seat session. The transition and lifecycle fact are atomic. */
    public UsageResult leaveSeat(UUID userId, UUID reservationId, Runnable authorize) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(authorize, "authorize");
        try {
            ReservationRecord preliminary = findForUser(userId, reservationId)
                    .orElseThrow(() -> new CheckInException(CheckInException.Code.RESERVATION_NOT_FOUND));
            UsageResult result = transaction.execute(ignored -> {
                jdbc.queryForObject("select set_config('lock_timeout', ?, true)", String.class,
                        lockTimeoutMillis + "ms");
                locks.lockUserAndRoom(preliminary.userId(), preliminary.roomId());
                authorize.run();
                ReservationRecord current = findForUser(userId, reservationId)
                        .orElseThrow(() -> new CheckInException(CheckInException.Code.RESERVATION_NOT_FOUND));
                if (!current.roomId().equals(preliminary.roomId())) {
                    throw new IllegalStateException("预约不可变关联已改变");
                }
                if (current.status() == ReservationStatus.COMPLETED) {
                    return new UsageResult(current, true);
                }
                if (current.status() != ReservationStatus.CHECKED_IN) {
                    throw new CheckInException(CheckInException.Code.LEAVE_SEAT_STATE_CONFLICT);
                }
                Instant now = clock.instant();
                long nextVersion = current.version() + 1;
                int changed = jdbc.update("""
                        update m1.reservations set status='COMPLETED',version=?,updated_at=?
                        where id=? and user_id=? and status='CHECKED_IN' and version=?
                        """, nextVersion, Timestamp.from(now), reservationId, userId, current.version());
                if (changed != 1) throw new IllegalStateException("退座状态条件更新失败");
                jdbc.update("""
                        insert into m1.reservation_transitions
                        (transition_id,reservation_id,from_status,to_status,reason,actor_user_id,reservation_version,occurred_at)
                        values (?,?,'CHECKED_IN','COMPLETED','USER_LEAVE_SEAT',?,?,?)
                        """, UUID.randomUUID(), reservationId, userId, nextVersion, Timestamp.from(now));
                jdbc.update("""
                        insert into m1.outbox_events(event_id,reservation_id,event_type,payload,created_at)
                        values (?,?,'RESERVATION_COMPLETED',jsonb_build_object(
                          'reservationId',cast(? as text),'userId',cast(? as text),'roomId',cast(? as text),
                          'seatId',cast(? as text),'completedAt',cast(? as text),'reason','USER_LEAVE_SEAT','version',?),?)
                        """, UUID.randomUUID(), reservationId, reservationId.toString(), userId.toString(),
                        current.roomId().toString(), current.seatId().toString(), now.toString(), nextVersion,
                        Timestamp.from(now));
                return new UsageResult(findForUser(userId, reservationId).orElseThrow(), false);
            });
            if (result == null) throw new IllegalStateException("退座事务未返回结果");
            return result;
        } catch (DataAccessException exception) {
            throw persistenceFailure(exception);
        }
    }

    /** Read the persisted usage timeline for the authenticated owner; no client clock is trusted. */
    public UsageSession usageForUser(UUID userId, UUID reservationId) {
        ReservationRecord reservation = findForUser(userId, reservationId)
                .orElseThrow(() -> new CheckInException(CheckInException.Code.RESERVATION_NOT_FOUND));
        if (reservation.status() != ReservationStatus.CHECKED_IN
                && reservation.status() != ReservationStatus.COMPLETED) {
            throw new CheckInException(CheckInException.Code.LEAVE_SEAT_STATE_CONFLICT);
        }
        var times = jdbc.queryForObject("""
                select max(occurred_at) filter (where to_status='CHECKED_IN') as checked_in_at,
                       max(occurred_at) filter (where to_status='COMPLETED') as completed_at
                from m1.reservation_transitions where reservation_id=?
                """, (row, index) -> new UsageTimes(
                row.getTimestamp("checked_in_at") == null ? null : row.getTimestamp("checked_in_at").toInstant(),
                row.getTimestamp("completed_at") == null ? null : row.getTimestamp("completed_at").toInstant()),
                reservationId);
        if (times == null || times.checkedInAt() == null) {
            throw new ReservationPersistenceException(STORAGE_UNAVAILABLE);
        }
        return new UsageSession(reservation, times.checkedInAt(), times.completedAt(), clock.instant());
    }

    /** Background/system transition. Candidate scans are advisory only; this method owns the lock-time decision. */
    public LifecycleResult markNoShowIfDue(UUID reservationId) {
        Objects.requireNonNull(reservationId, "reservationId");
        try {
            ReservationRecord preliminary = findById(reservationId).orElse(null);
            if (preliminary == null) return LifecycleResult.NOT_APPLICABLE;
            LifecycleResult result = transaction.execute(ignored -> {
                jdbc.queryForObject("select set_config('lock_timeout', ?, true)", String.class,
                        lockTimeoutMillis + "ms");
                locks.lockUserAndRoom(preliminary.userId(), preliminary.roomId());
                ReservationRecord current = findById(reservationId).orElse(null);
                if (current == null || !current.userId().equals(preliminary.userId())
                        || !current.roomId().equals(preliminary.roomId())) {
                    throw new IllegalStateException("预约不可变关联已改变");
                }
                if (current.status() != ReservationStatus.CONFIRMED) return LifecycleResult.NOT_APPLICABLE;
                Instant now = clock.instant();
                if (now.isBefore(current.period().startAt().plus(Duration.ofMinutes(15)))) {
                    return LifecycleResult.NOT_DUE;
                }
                transitionNoShow(current, now);
                return LifecycleResult.TRANSITIONED;
            });
            return Objects.requireNonNull(result, "未签到事务未返回结果");
        } catch (DataAccessException exception) {
            throw persistenceFailure(exception);
        }
    }

    /** Idempotently completes a checked-in reservation at its persisted end time. */
    public LifecycleResult completeIfDue(UUID reservationId) {
        Objects.requireNonNull(reservationId, "reservationId");
        try {
            ReservationRecord preliminary = findById(reservationId).orElse(null);
            if (preliminary == null) return LifecycleResult.NOT_APPLICABLE;
            LifecycleResult result = transaction.execute(ignored -> {
                jdbc.queryForObject("select set_config('lock_timeout', ?, true)", String.class,
                        lockTimeoutMillis + "ms");
                locks.lockUserAndRoom(preliminary.userId(), preliminary.roomId());
                ReservationRecord current = findById(reservationId).orElse(null);
                if (current == null || !current.userId().equals(preliminary.userId())
                        || !current.roomId().equals(preliminary.roomId())) {
                    throw new IllegalStateException("预约不可变关联已改变");
                }
                if (current.status() != ReservationStatus.CHECKED_IN) return LifecycleResult.NOT_APPLICABLE;
                Instant now = clock.instant();
                if (now.isBefore(current.period().endAt())) return LifecycleResult.NOT_DUE;
                long nextVersion = current.version() + 1;
                int changed = jdbc.update("""
                        update m1.reservations set status='COMPLETED',version=?,updated_at=?
                        where id=? and status='CHECKED_IN' and version=?
                        """, nextVersion, Timestamp.from(now), reservationId, current.version());
                if (changed != 1) throw new IllegalStateException("预约结束状态条件更新失败");
                jdbc.update("""
                        insert into m1.reservation_transitions
                        (transition_id,reservation_id,from_status,to_status,reason,actor_user_id,reservation_version,occurred_at)
                        values (?,?,'CHECKED_IN','COMPLETED','RESERVATION_END',null,?,?)
                        """, UUID.randomUUID(), reservationId, nextVersion, Timestamp.from(now));
                jdbc.update("""
                        insert into m1.outbox_events(event_id,reservation_id,event_type,payload,created_at)
                        values (?,?,'RESERVATION_COMPLETED',jsonb_build_object(
                          'reservationId',cast(? as text),'userId',cast(? as text),'roomId',cast(? as text),
                          'seatId',cast(? as text),'completedAt',cast(? as text),'reason','RESERVATION_END','version',?),?)
                        """, UUID.randomUUID(), reservationId, reservationId.toString(), current.userId().toString(),
                        current.roomId().toString(), current.seatId().toString(), now.toString(), nextVersion,
                        Timestamp.from(now));
                return LifecycleResult.TRANSITIONED;
            });
            return Objects.requireNonNull(result, "预约结束事务未返回结果");
        } catch (DataAccessException exception) {
            throw persistenceFailure(exception);
        }
    }

    private void transitionNoShow(ReservationRecord current, Instant now) {
        long nextVersion = current.version() + 1;
        int changed = jdbc.update("""
                update m1.reservations set status='NO_SHOW',version=?,updated_at=?
                where id=? and status='CONFIRMED' and version=?
                """, nextVersion, Timestamp.from(now), current.id(), current.version());
        if (changed != 1) throw new IllegalStateException("未签到状态条件更新失败");
        jdbc.update("""
                insert into m1.reservation_transitions
                (transition_id,reservation_id,from_status,to_status,reason,actor_user_id,reservation_version,occurred_at)
                values (?,?,'CONFIRMED','NO_SHOW','CHECK_IN_TIMEOUT',null,?,?)
                """, UUID.randomUUID(), current.id(), nextVersion, Timestamp.from(now));
        jdbc.update("""
                insert into m1.reservation_violations
                (violation_id,reservation_id,user_id,room_id,seat_id,start_at,end_at,reason,recorded_at,reservation_version)
                values (?,?,?,?,?,?,?,'NO_SHOW',?,?)
                """, UUID.randomUUID(), current.id(), current.userId(), current.roomId(), current.seatId(),
                Timestamp.from(current.period().startAt()), Timestamp.from(current.period().endAt()),
                Timestamp.from(now), nextVersion);
        jdbc.update("""
                insert into m1.outbox_events(event_id,reservation_id,event_type,payload,created_at)
                values (?,?,'RESERVATION_NO_SHOW',jsonb_build_object(
                  'reservationId',cast(? as text),'userId',cast(? as text),'roomId',cast(? as text),
                  'seatId',cast(? as text),'startAt',cast(? as text),'endAt',cast(? as text),
                  'recordedAt',cast(? as text),'reason','NO_SHOW','version',?),?)
                """, UUID.randomUUID(), current.id(), current.id().toString(), current.userId().toString(),
                current.roomId().toString(), current.seatId().toString(), current.period().startAt().toString(),
                current.period().endAt().toString(), now.toString(), nextVersion, Timestamp.from(now));
        jdbc.update("delete from m1.checkin_rate_limits where reservation_id=?", current.id());
        supersedeReminders(current.id(), now);
    }

    private void supersedeReminders(UUID reservationId, Instant now) {
        jdbc.update("""
                update m1.outbox_events set status='SUPERSEDED',claimed_until=null,last_error=null,superseded_at=?
                where reservation_id=? and event_type in ('RESERVATION_START_REMINDER','RESERVATION_LATE_REMINDER')
                  and status in ('PENDING','DELIVERING')
                """, Timestamp.from(now), reservationId);
    }

    private RateLimit rateLimit(UUID userId, UUID reservationId) {
        return jdbc.query("""
                select window_started_at,failures,blocked_until from m1.checkin_rate_limits
                where user_id=? and reservation_id=? for update
                """, (row, index) -> new RateLimit(row.getTimestamp(1).toInstant(), row.getInt(2),
                        row.getTimestamp(3) == null ? null : row.getTimestamp(3).toInstant()),
                userId, reservationId).stream().findFirst().orElse(null);
    }

    private CheckInException.Code recordFailure(UUID userId, UUID reservationId, RateLimit previous, Instant now) {
        Instant windowStart = now;
        int failures = 1;
        if (previous != null && previous.windowStartedAt().isAfter(now.minus(Duration.ofMinutes(15)))) {
            windowStart = previous.windowStartedAt();
            failures = previous.failures() + 1;
        }
        failures = Math.min(failures, 5);
        Instant blockedUntil = failures >= 5 ? now.plus(Duration.ofMinutes(15)) : null;
        jdbc.update("""
                insert into m1.checkin_rate_limits
                    (user_id,reservation_id,window_started_at,failures,blocked_until,updated_at)
                values (?,?,?,?,?,?)
                on conflict (user_id,reservation_id) do update set
                    window_started_at=excluded.window_started_at,failures=excluded.failures,
                    blocked_until=excluded.blocked_until,updated_at=excluded.updated_at
                """, userId, reservationId, Timestamp.from(windowStart), failures,
                blockedUntil == null ? null : Timestamp.from(blockedUntil), Timestamp.from(now));
        return blockedUntil == null ? CheckInException.Code.CHECK_IN_CODE_INVALID
                : CheckInException.Code.CHECK_IN_RATE_LIMITED;
    }

    private static ReservationPersistenceException persistenceFailure(DataAccessException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && ("55P03".equals(sql.getSQLState())
                    || "40P01".equals(sql.getSQLState()) || "40001".equals(sql.getSQLState()))) {
                return new ReservationPersistenceException(STORAGE_UNAVAILABLE, exception);
            }
        }
        return new ReservationPersistenceException(STORAGE_UNAVAILABLE, exception);
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

    private Optional<ReservationRecord> findById(UUID reservationId) {
        return jdbc.query("select * from m1.reservations where id=?", ReservationStore::map,
                reservationId).stream().findFirst();
    }

    public record HistoryPosition(Instant createdAt, UUID id) {
        public HistoryPosition { Objects.requireNonNull(createdAt); Objects.requireNonNull(id); }
    }
    public record OwnHistory(List<ReservationRecord> items, HistoryPosition next) {}
    public record HistoryEntry(ReservationRecord reservation, Instant checkedInAt, Instant completedAt,
                               long usageDurationSeconds, String violationReason) {}
    public record DetailedOwnHistory(List<HistoryEntry> items, HistoryPosition next, Instant serverTime) {}
    public record HistoryFilter(ReservationStatus status, Instant from, Instant to) {
        public HistoryFilter {
            if ((from == null) != (to == null)) throw new IllegalArgumentException("History date range must be complete");
            if (from != null && (!from.isBefore(to) || Duration.between(from,to).compareTo(Duration.ofDays(366)) > 0)) {
                throw new IllegalArgumentException("History date range must be between 1 second and 366 days");
            }
        }
        public static HistoryFilter none() { return new HistoryFilter(null,null,null); }
    }

    /** Keyset pagination over the authenticated user's rows, including every lifecycle state. Never writes. */
    public OwnHistory historyForUser(UUID userId, HistoryPosition before, int size) {
        Objects.requireNonNull(userId);
        if(size<1||size>50)throw new IllegalArgumentException("History size must be 1..50");
        var args=new java.util.ArrayList<Object>();args.add(userId);
        String boundary="";
        if(before!=null){boundary=" and (created_at,id) < (?,?)";args.add(Timestamp.from(before.createdAt()));args.add(before.id());}
        args.add(size+1);
        var rows=jdbc.query("select * from m1.reservations where user_id=?"+boundary+" order by created_at desc,id desc limit ?",
                ReservationStore::map,args.toArray());
        boolean more=rows.size()>size;var items=List.copyOf(rows.subList(0,Math.min(size,rows.size())));
        var last=items.isEmpty()?null:items.getLast();
        return new OwnHistory(items,more?new HistoryPosition(last.createdAt(),last.id()):null);
    }

    /** One-snapshot history read; lifecycle details are aggregated in SQL to avoid per-row follow-up calls. */
    public DetailedOwnHistory detailedHistoryForUser(UUID userId, HistoryPosition before, int size) {
        return detailedHistoryForUser(userId,HistoryFilter.none(),before,size);
    }

    /** Filtered history remains a server-side, owner-scoped keyset query; dates apply to reservation start time. */
    public DetailedOwnHistory detailedHistoryForUser(UUID userId, HistoryFilter filter, HistoryPosition before, int size) {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(filter);
        if(size<1||size>50)throw new IllegalArgumentException("History size must be 1..50");
        Instant serverTime=clock.instant();
        var args=new java.util.ArrayList<Object>();args.add(userId);
        String predicates="";
        if(filter.status()!=null){predicates+=" and r.status=?";args.add(filter.status().name());}
        if(filter.from()!=null){predicates+=" and r.start_at>=? and r.start_at<?";args.add(Timestamp.from(filter.from()));args.add(Timestamp.from(filter.to()));}
        String boundary="";
        if(before!=null){boundary=" and (r.created_at,r.id) < (?,?)";args.add(Timestamp.from(before.createdAt()));args.add(before.id());}
        args.add(size+1);
        var rows=jdbc.query("""
                select r.*,
                  max(t.occurred_at) filter (where t.to_status='CHECKED_IN') as checked_in_at,
                  max(t.occurred_at) filter (where t.to_status='COMPLETED') as completed_at,
                  max(v.reason) as violation_reason
                from m1.reservations r
                left join m1.reservation_transitions t on t.reservation_id=r.id
                left join m1.reservation_violations v on v.reservation_id=r.id
                where r.user_id=?
                """+predicates+boundary+"""

                group by r.id order by r.created_at desc,r.id desc limit ?
                """,(row,index)->{
                    ReservationRecord reservation=map(row,index);
                    Timestamp checked=row.getTimestamp("checked_in_at"),completed=row.getTimestamp("completed_at");
                    Instant checkedAt=checked==null?null:checked.toInstant();
                    Instant completedAt=completed==null?null:completed.toInstant();
                    long seconds=checkedAt==null?0:Math.max(0,Duration.between(checkedAt,
                            completedAt==null?serverTime:completedAt).getSeconds());
                    return new HistoryEntry(reservation,checkedAt,completedAt,seconds,row.getString("violation_reason"));
                },args.toArray());
        boolean more=rows.size()>size;var items=List.copyOf(rows.subList(0,Math.min(size,rows.size())));
        var last=items.isEmpty()?null:items.getLast().reservation();
        return new DetailedOwnHistory(items,more?new HistoryPosition(last.createdAt(),last.id()):null,serverTime);
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
    private record UsageTimes(Instant checkedInAt, Instant completedAt) {}

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
