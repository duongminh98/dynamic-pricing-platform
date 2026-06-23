package dpp.product.repository;

import dpp.product.entity.EligibilityRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EligibilityRuleRepository extends JpaRepository<EligibilityRule, UUID> {

    List<EligibilityRule> findByRateVersionId(UUID rateVersionId);

    List<EligibilityRule> findByRateVersionIdAndLine(UUID rateVersionId, String line);
}


