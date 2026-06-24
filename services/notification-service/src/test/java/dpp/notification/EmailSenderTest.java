package dpp.notification;

import dpp.notification.service.EmailSender;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the email-channel delivery adapter (R7.2, task 20.25).
 * Verifies that EmailSender delegates to JavaMailSender and returns false on
 * failure (so the retry loop in NotificationService can record failed without
 * throwing / infinite redelivery, R7.3/R7.5).
 */
class EmailSenderTest {

    @Test
    void sendDelegatesToJavaMailSender() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        EmailSender sender = new EmailSender(mailSender, "noreply@dynamic-pricing.local");

        boolean result = sender.send("user@example.com", "Subject", "Body");

        assertTrue(result);
        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
    }

    @Test
    void sendReturnsFalseOnMailException() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        doThrow(new MailSendException("SMTP refused"))
                .when(mailSender).send(any(SimpleMailMessage.class));
        EmailSender sender = new EmailSender(mailSender, "noreply@dynamic-pricing.local");

        boolean result = sender.send("user@example.com", "Subject", "Body");

        assertFalse(result);
        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
    }

    @Test
    void sendUsesConfiguredFromAddress() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        EmailSender sender = new EmailSender(mailSender, "custom@from.com");

        sender.send("user@example.com", "Subject", "Body");

        org.mockito.ArgumentCaptor<SimpleMailMessage> captor =
                org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertEquals("custom@from.com", captor.getValue().getFrom());
        assertEquals("user@example.com", captor.getValue().getTo()[0]);
        assertEquals("Subject", captor.getValue().getSubject());
        assertEquals("Body", captor.getValue().getText());
    }
}
