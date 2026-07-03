package dpp.product.repository;

import dpp.product.entity.CostIndexRow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CostIndexRowRepository extends JpaRepository<CostIndexRow, UUID> {
    List<CostIndexRow> findByVersionIdOrderByMonthStartDesc(UUID versionId);
    void deleteByVersionId(UUID versionId);
}
