package dpp.common.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for outbox entries. Provides batch fetch for the relay poller.
 */
public interface OutboxRepository extends JpaRepository<OutboxEntity, UUID> {

    /**
     * Fetch all outbox entries still pending publication, ordered by creation time.
     */
    List<OutboxEntity> findByStatusOrderByCreatedAtAsc(OutboxEntity.OutboxStatus status);
}
