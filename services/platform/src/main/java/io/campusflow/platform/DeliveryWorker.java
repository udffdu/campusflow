package io.campusflow.platform;

import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DeliveryWorker {
    private final NotificationService notifications;
    DeliveryWorker(NotificationService notifications) { this.notifications=notifications; }
    @Scheduled(fixedDelayString="${platform.delivery-delay-ms}",initialDelayString="${platform.delivery-delay-ms}")
    public void tick() {
        try { notifications.deliver(); }
        catch(DataAccessException ex) {
            // Transaction rollback leaves pending tasks durable. Avoid logging SQL values or notification bodies.
            LoggerFactory.getLogger(DeliveryWorker.class).warn("Notification storage unavailable; pending tasks will be retried on the next run");
        }
    }
}
