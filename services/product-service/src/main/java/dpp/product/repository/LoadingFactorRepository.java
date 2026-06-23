package dpp.product.repository;

import dpp.product.entity.LoadingFactor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LoadingFactorRepository extends JpaRepository<LoadingFactor, UUID> {

    List<LoadingFactor> findByRateVersionId(UUID rateVersionId);

    List<LoadingFactor> findByRateVersionIdAndLine(UUID rateVersionId, String line);
}


