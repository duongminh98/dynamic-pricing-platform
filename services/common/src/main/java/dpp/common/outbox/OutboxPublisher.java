package dpp.common.outbox;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Helper to create outbox entries. Intended to be called <b>within the same
 * transaction</b> as the business write, guaranteeing atomicity (R19.4).
 *
 * <p>Usage in service layer:</p>
 * <pre>
 * &#64;Transactional
 * public void someBusinessMethod(...) {
 *     // ... business write ...
 *     outboxPublisher.enqueue("PolicyIssued", payload);
 *     // outbox INSERT in same TX Ã¢â‚¬â€ if business write fails, outbox rolls back too
 * }
 * </pre>
 *
 * Requirements: R10.1 (event_id unique), R10.5 (outbox pattern atomicity).
 */
@Component
public class OutboxPublisher {

    private final OutboxRepository outboxRepository;

    public OutboxPublisher(OutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    /**
     * Enqueue an outbox entry with an auto-generated event_id.
     * Call this inside the same transaction as the business write.
     */
    public OutboxEntity enqueue(String eventType, String payload) {
        String eventId = UUID.randomUUID().toString();
        return enqueue(eventId, eventType, payload);
    }

    /**
     * Enqueue an outbox entry with a specific event_id (for deterministic idempotency).
     * Call this inside the same transaction as the business write.
     */
    public OutboxEntity enqueue(String eventId, String eventType, String payload) {
        OutboxEntity entry = new OutboxEntity(eventId, eventType, payload);
        return outboxRepository.save(entry);
    }
}
