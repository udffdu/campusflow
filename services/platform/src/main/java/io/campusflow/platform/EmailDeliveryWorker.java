package io.campusflow.platform;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class EmailDeliveryWorker {
    private final EmailNotificationService emails;
    private final MailGateway mail;
    private final Duration lease;
    EmailDeliveryWorker(EmailNotificationService emails,MailGateway mail,
            @Value("${platform.mail.lease:PT1M}") Duration lease){
        if(lease.isNegative()||lease.isZero()||lease.compareTo(Duration.ofMinutes(10))>0)throw new IllegalArgumentException("邮件任务租约无效");
        this.emails=emails;this.mail=mail;this.lease=lease;
    }
    @Scheduled(fixedDelayString="${platform.email-delivery-delay-ms}",initialDelayString="${platform.email-delivery-delay-ms}")
    public void deliver(){
        for(var claim:emails.claim(25,lease)){
            if(!claim.recipientActive()){emails.failed(claim,"RECIPIENT_DISABLED");continue;}
            if(claim.email()==null||claim.email().isBlank()){emails.failed(claim,"RECIPIENT_EMAIL_MISSING");continue;}
            try{mail.send(claim.id(),claim.email(),claim.title(),claim.body());emails.delivered(claim.id());}
            catch(MailChannelException exception){emails.failed(claim,exception.reason());}
            catch(Exception exception){emails.failed(claim,"EMAIL_DELIVERY_FAILED");}
        }
    }
}
