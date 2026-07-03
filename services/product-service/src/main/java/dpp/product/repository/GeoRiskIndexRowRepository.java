package dpp.product.repository;

import dpp.product.entity.GeoRiskIndexRow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GeoRiskIndexRowRepository extends JpaRepository<GeoRiskIndexRow, UUID> {
    List<GeoRiskIndexRow> findByVersionIdOrderByProvince(UUID versionId);
    void deleteByVersionId(UUID versionId);
}
