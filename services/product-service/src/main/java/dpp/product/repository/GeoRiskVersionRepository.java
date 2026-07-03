package dpp.product.repository;

import dpp.product.entity.GeoRiskVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GeoRiskVersionRepository extends JpaRepository<GeoRiskVersion, UUID> {
    Optional<GeoRiskVersion> findFirstByStatusOrderByActivatedAtDesc(String status);
    List<GeoRiskVersion> findAllByOrderByCreatedAtDesc();
}
