package dpp.notification;

import dpp.notification.entity.CustomerEmailProjection;
import dpp.notification.repository.CustomerEmailProjectionRepository;
import dpp.notification.repository.NotificationRepository;
import dpp.notification.service.EmailSender;
import dpp.notification.service.NotificationService;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class NotificationEmailProjectionTest {

    @Test
    void emailDeliveryUsesProjectionInsteadOfSyncCustomerLookup() {
        NotificationRepository repo = mock(NotificationRepository.class);
        CustomerEmailProjectionRepository projectionRepo = mock(CustomerEmailProjectionRepository.class);
        EmailSender sender = mock(EmailSender.class);
        when(repo.findByEventIdAndChannel(anyString(), any())).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sender.send(anyString(), anyString(), anyString())).thenReturn(true);

        UUID customerId = UUID.randomUUID();
        CustomerEmailProjection projection = new CustomerEmailProjection();
        projection.setCustomerId(customerId);
        projection.setEmail("user@example.com");
        projection.setUpdatedAt(OffsetDateTime.now());
        when(projectionRepo.findById(customerId)).thenReturn(Optional.of(projection));

        NotificationService service = new NotificationService(repo, sender, projectionRepo, true);
        service.createNotification(UUID.randomUUID().toString(), customerId, UUID.randomUUID(), "PolicyIssued", "msg");

        verify(projectionRepo, atLeastOnce()).findById(customerId);
        verify(sender).send(eq("user@example.com"), anyString(), eq("msg"));
    }
}
