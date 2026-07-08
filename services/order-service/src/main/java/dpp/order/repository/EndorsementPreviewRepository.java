package dpp.order.repository;

import dpp.order.entity.EndorsementPreviewEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface EndorsementPreviewRepository extends JpaRepository<EndorsementPreviewEntity, UUID> {
    List<EndorsementPreviewEntity> findByCreatedAtBefore(OffsetDateTime threshold);
}
