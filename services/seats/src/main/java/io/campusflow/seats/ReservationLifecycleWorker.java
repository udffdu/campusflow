package io.campusflow.seats;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Multi-instance safe because each candidate is re-read under the shared occupancy locks. */
public final class ReservationLifecycleWorker {
    private static final Logger LOG=LoggerFactory.getLogger(ReservationLifecycleWorker.class);
    private final ReservationLifecycleStore store;
    private final int limit;
    public ReservationLifecycleWorker(ReservationLifecycleStore store,int limit){
        if(limit<1||limit>1000)throw new IllegalArgumentException("lifecycle scan limit");
        this.store=store;this.limit=limit;
    }
    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString="${m1.lifecycle.delay:PT30S}",initialDelayString="${m1.lifecycle.initial-delay:PT5S}")
    public void runCycle(){
        var result=store.processDue(limit);
        if(result.failures()>0)LOG.warn("M1 lifecycle cycle retained {} candidate(s) for retry",result.failures());
    }
}
