package io.campusflow.platform;

import jakarta.mail.internet.InternetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;

@Configuration(proxyBeanMethods=false)
class MailConfiguration {
    @Bean MailGateway mailGateway(
            @Value("${platform.mail.enabled}") boolean enabled,
            @Value("${platform.mail.host}") String host,
            @Value("${platform.mail.port}") int port,
            @Value("${platform.mail.username}") String username,
            @Value("${platform.mail.password}") String password,
            @Value("${platform.mail.from}") String from,
            @Value("${platform.mail.starttls}") boolean startTls,
            @Value("${platform.mail.ssl}") boolean ssl,
            @Value("${platform.mail.connect-timeout}") Duration connectTimeout,
            @Value("${platform.mail.read-timeout}") Duration readTimeout) {
        if(!enabled)return (id,to,subject,body)->{throw new MailChannelException("EMAIL_CHANNEL_DISABLED");};
        if(host.isBlank()||port<1||port>65535||from.isBlank()||startTls&&ssl
                ||username.isBlank()!=password.isBlank()||connectTimeout.isNegative()||connectTimeout.isZero()
                ||readTimeout.isNegative()||readTimeout.isZero())throw new IllegalArgumentException("邮件通道配置无效");
        try { new InternetAddress(from,true); }
        catch(Exception exception) { throw new IllegalArgumentException("邮件发件地址无效",exception); }
        var sender=new JavaMailSenderImpl();sender.setHost(host);sender.setPort(port);
        if(!username.isBlank()){sender.setUsername(username);sender.setPassword(password);}
        Properties properties=sender.getJavaMailProperties();
        properties.setProperty("mail.smtp.auth",Boolean.toString(!username.isBlank()));
        properties.setProperty("mail.smtp.starttls.enable",Boolean.toString(startTls));
        properties.setProperty("mail.smtp.starttls.required",Boolean.toString(startTls));
        properties.setProperty("mail.smtp.ssl.enable",Boolean.toString(ssl));
        properties.setProperty("mail.smtp.connectiontimeout",Long.toString(connectTimeout.toMillis()));
        properties.setProperty("mail.smtp.timeout",Long.toString(readTimeout.toMillis()));
        properties.setProperty("mail.smtp.writetimeout",Long.toString(readTimeout.toMillis()));
        return (id,to,subject,body)->{
            try {
                var message=sender.createMimeMessage();
                var helper=new MimeMessageHelper(message,false,StandardCharsets.UTF_8.name());
                helper.setFrom(from);helper.setTo(to);helper.setSubject(subject);helper.setText(body,false);
                message.setHeader("Message-ID","<campusflow-"+id+"@notifications.local>");
                sender.send(message);
            } catch(Exception exception) { throw new MailChannelException("EMAIL_DELIVERY_FAILED",exception); }
        };
    }
}

final class MailChannelException extends RuntimeException {
    private final String reason;
    MailChannelException(String reason){super(reason);this.reason=reason;}
    MailChannelException(String reason,Throwable cause){super(reason,cause);this.reason=reason;}
    String reason(){return reason;}
}
