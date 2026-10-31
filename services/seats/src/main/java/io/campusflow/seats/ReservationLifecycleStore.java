package io.campusflow.seats;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Bounded candidate scans; ReservationStore performs every authoritative lock-time transition. */
public final class ReservationLifecycleStore {
    public record CycleResult(int noShows,int completed,int failures) {}
    private final JdbcTemplate jdbc;
    private final ReservationStore reservations;
    private final Clock clock;

    public ReservationLifecycleStore(DataSource source,ReservationStore reservations,Clock clock){
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(source));
        this.reservations=Objects.requireNonNull(reservations);this.clock=Objects.requireNonNull(clock);
    }

    public CycleResult processDue(int limit){
        if(limit<1||limit>1000)throw new IllegalArgumentException("lifecycle scan limit");
        Instant now=clock.instant();
        var noShowCandidates=jdbc.queryForList("""
                select id from m1.reservations
                where status='CONFIRMED' and start_at<=?
                order by start_at,id limit ?
                """,UUID.class,Timestamp.from(now.minus(Duration.ofMinutes(15))),limit);
        int noShows=0,completed=0,failures=0;
        for(UUID id:noShowCandidates){
            try{if(reservations.markNoShowIfDue(id)==ReservationStore.LifecycleResult.TRANSITIONED)noShows++;}
            catch(RuntimeException exception){failures++;}
        }
        var completionCandidates=jdbc.queryForList("""
                select id from m1.reservations
                where status='CHECKED_IN' and end_at<=?
                order by end_at,id limit ?
                """,UUID.class,Timestamp.from(now),limit);
        for(UUID id:completionCandidates){
            try{if(reservations.completeIfDue(id)==ReservationStore.LifecycleResult.TRANSITIONED)completed++;}
            catch(RuntimeException exception){failures++;}
        }
        return new CycleResult(noShows,completed,failures);
    }
}
