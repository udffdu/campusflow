package io.campusflow.rooms;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="rooms.workers-enabled",havingValue="true",matchIfMissing=true)
public class RecoveryWorker {
    private final ApplicationService service;
    RecoveryWorker(ApplicationService service) {this.service=service;}
    @Scheduled(fixedDelayString="${rooms.worker-delay-ms}",initialDelayString="${rooms.worker-delay-ms}")
    public void run() {
        for(Runnable task:new Runnable[]{service::expire,service::reconcile,service::dispatch}) {
            try{task.run();}catch(Exception ex){LoggerFactory.getLogger(RecoveryWorker.class).warn("M2 recovery batch rolled back: {}",ex.getClass().getSimpleName());}
        }
    }
}
