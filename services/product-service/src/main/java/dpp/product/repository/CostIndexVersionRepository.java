package dpp.product.repository;

import dpp.product.entity.CostIndexVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CostIndexVersionRepository extends JpaRepository<CostIndexVersion, UUID> {
    Optional<CostIndexVersion> findFirstByStatusOrderByActivatedAtDesc(String status);
    List<CostIndexVersion> findAllByOrderByCreatedAtDesc();
}
