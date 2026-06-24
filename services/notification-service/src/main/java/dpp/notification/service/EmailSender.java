package dpp.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Email-channel delivery adapter (R7.2). Wraps {@link JavaMailSender} to send a
 * plain-text email. Each call is a single attempt; the caller ({@link NotificationService})
 * handles the retry loop. Returns {@code false} on failure so the caller can record
 * {@code failed} without throwing (R7.3/R7.5 ? no infinite redelivery).
 */
@Component
public class EmailSender {

    private static final Logger log = LoggerFactory.getLogger(EmailSender.class);

    private final JavaMailSender mailSender;
    private final String from;

    public EmailSender(JavaMailSender mailSender,
                       @Value("${notification.email.from:noreply@dynamic-pricing.local}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    /**
     * Send a plain-text email. Returns true on success, false on failure.
     */
    public boolean send(String to, String subject, String body) {
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(from);
            msg.setTo(to);
            msg.setSubject(subject);
            msg.setText(body);
            mailSender.send(msg);
            return true;
        } catch (MailException e) {
            log.warn("Email send failed to {}: {}", to, e.getMessage());
            return false;
        }
    }
}
