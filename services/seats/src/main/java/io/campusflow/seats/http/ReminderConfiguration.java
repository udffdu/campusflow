package io.campusflow.seats.http;

import io.campusflow.seats.*;
import java.time.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="m1.reminders.enabled",havingValue="true")
class ReminderConfiguration {
    @Bean ReminderStore reminderStore(DataSource source,Clock clock){return new ReminderStore(source,clock);}
    @Bean ReminderWorker reminderWorker(ReminderStore store,DQueryClient d,
            @Value("${m1.reminders.lease}") Duration lease,
            @Value("${m1.reminders.retry-base}") Duration retryBase,
            @Value("${m1.reminders.retry-max}") Duration retryMax,
            @Value("${m1.reminders.scan-limit}") int scanLimit,
            @Value("${m1.reminders.delivery-limit}") int deliveryLimit){
        if(scanLimit<1||scanLimit>1000||deliveryLimit<1||deliveryLimit>100||retryMax.compareTo(retryBase)<0)
            throw new IllegalArgumentException("M1提醒配置无效");
        return new ReminderWorker(store,d,lease,retryBase,retryMax,scanLimit,deliveryLimit);
    }
}
