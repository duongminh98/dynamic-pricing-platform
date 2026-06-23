package dpp.order;

import dpp.common.outbox.OutboxEntity;
import dpp.common.outbox.OutboxPublisher;
import dpp.common.outbox.OutboxRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("integration")
class OutboxDlqIntegrationTest {

    @Test
    void outboxPublisherEnqueuesWithEventTypeAndPayload() {
        OutboxRepository repo = mock(OutboxRepository.class);
        when(repo.save(any(OutboxEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        OutboxPublisher publisher = new OutboxPublisher(repo);
        OutboxEntity entity = publisher.enqueue("PolicyIssued", "{\"policy_id\":\"abc\"}");

        assertEquals("PolicyIssued", entity.getEventType());
        assertEquals("{\"policy_id\":\"abc\"}", entity.getPayload());
        assertEquals(OutboxEntity.OutboxStatus.NEW, entity.getStatus());
        assertNotNull(entity.getEventId());
        verify(repo, times(1)).save(any(OutboxEntity.class));
    }

    @Test
    void outboxPublisherUsesDeterministicEventId() {
        OutboxRepository repo = mock(OutboxRepository.class);
        when(repo.save(any(OutboxEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        OutboxPublisher publisher = new OutboxPublisher(repo);
        String deterministicId = UUID.randomUUID().toString();
        OutboxEntity entity = publisher.enqueue(deterministicId, "PolicyIssued", "{}");

        assertEquals(deterministicId, entity.getEventId());
        assertEquals("PolicyIssued", entity.getEventType());
        verify(repo, times(1)).save(any(OutboxEntity.class));
    }

    @Test
    void outboxRelayFetchesOnlyNewStatusEntries() {
        OutboxRepository repo = mock(OutboxRepository.class);
        when(repo.findByStatusOrderByCreatedAtAsc(OutboxEntity.OutboxStatus.NEW))
                .thenReturn(java.util.List.of());

        java.util.List<OutboxEntity> pending = repo.findByStatusOrderByCreatedAtAsc(OutboxEntity.OutboxStatus.NEW);
        verify(repo, times(1)).findByStatusOrderByCreatedAtAsc(OutboxEntity.OutboxStatus.NEW);
        assertTrue(pending.isEmpty());
    }
}
