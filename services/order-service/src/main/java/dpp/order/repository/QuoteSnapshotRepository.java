package dpp.order.repository;

import dpp.order.entity.QuoteSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface QuoteSnapshotRepository extends JpaRepository<QuoteSnapshot, UUID> {
}
