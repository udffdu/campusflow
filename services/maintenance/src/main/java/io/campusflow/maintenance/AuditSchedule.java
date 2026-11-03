package io.campusflow.maintenance;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Component
@ConditionalOnProperty(name="maintenance.audit-enabled",havingValue="true")
class AuditSchedule {
 private final AuditDelivery delivery;AuditSchedule(AuditDelivery delivery){this.delivery=delivery;}
 @Scheduled(fixedDelayString="${maintenance.audit-delay-ms:5000}") void tick(){delivery.deliver();}
}
