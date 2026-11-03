package io.campusflow.platform;

import java.util.UUID;

interface MailGateway {
    void send(UUID taskId, String recipient, String subject, String body);
}
