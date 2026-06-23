package dpp.product.repository;

import dpp.product.entity.CoverageOption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CoverageOptionRepository extends JpaRepository<CoverageOption, UUID> {

    List<CoverageOption> findByProductId(String productId);
}


