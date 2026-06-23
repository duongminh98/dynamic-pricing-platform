package dpp.product.repository;

import dpp.product.entity.RateVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RateVersionRepository extends JpaRepository<RateVersion, UUID> {

    Optional<RateVersion> findByIsCurrentTrue();

    List<RateVersion> findAllByOrderByEffectiveAtDesc();
}


