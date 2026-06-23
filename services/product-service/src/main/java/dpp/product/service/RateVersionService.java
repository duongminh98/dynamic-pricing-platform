package dpp.product.service;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.product.dto.RateVersionResponse;
import dpp.product.entity.LoadingFactor;
import dpp.product.entity.RateVersion;
import dpp.product.repository.LoadingFactorRepository;
import dpp.product.repository.RateVersionRepository;
import dpp.product.repository.EligibilityRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class RateVersionService {

    private static final Set<String> VALID_LINES = Set.of(
            "health", "motorbike", "car", "home", "accident", "travel"
    );

    private final RateVersionRepository rateVersionRepository;
    private final LoadingFactorRepository loadingFactorRepository;
    private final EligibilityRuleRepository eligibilityRuleRepository;

    public RateVersion createNewRateVersion(String createdBy) {
        // Set all current versions to false (append-only: never DELETE old ones)
        rateVersionRepository.findByIsCurrentTrue()
                .ifPresent(rv -> rv.setIsCurrent(false));

        RateVersion newVersion = RateVersion.builder()
                .effectiveAt(Instant.now())
                .createdBy(createdBy)
                .isCurrent(true)
                .createdAt(Instant.now())
                .build();

        return rateVersionRepository.save(newVersion);
    }

    public List<RateVersionResponse> listRateVersions() {
        return rateVersionRepository.findAllByOrderByEffectiveAtDesc().stream()
                .map(rv -> RateVersionResponse.builder()
                        .rateVersionId(rv.getRateVersionId())
                        .effectiveAt(rv.getEffectiveAt())
                        .createdBy(rv.getCreatedBy())
                        .isCurrent(rv.getIsCurrent())
                        .createdAt(rv.getCreatedAt())
                        .build())
                .toList();
    }

    public LoadingFactor addLoadingFactor(UUID rateVersionId, String line, Double loadingValue) {
        if (!VALID_LINES.contains(line)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST,
                    Map.of("line", line, "valid_lines", VALID_LINES));
        }
        rateVersionRepository.findById(rateVersionId)
                .orElseThrow(() -> new ServiceException(ErrorCode.BAD_REQUEST,
                        Map.of("rate_version_id", rateVersionId.toString(), "reason", "not found")));

        LoadingFactor lf = LoadingFactor.builder()
                .rateVersionId(rateVersionId)
                .line(line)
                .loadingValue(loadingValue)
                .build();
        return loadingFactorRepository.save(lf);
    }

    public void addEligibilityRule(UUID rateVersionId, String line, String ruleType,
                                    String params, String action) {
        if (!VALID_LINES.contains(line)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST,
                    Map.of("line", line, "valid_lines", VALID_LINES));
        }
        if (!Set.of("age_limit", "coverage_cap", "health_combo", "vehicle_limit").contains(ruleType)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST,
                    Map.of("rule_type", ruleType, "reason", "invalid rule type"));
        }
        if (!Set.of("ACCEPT", "REFER", "DECLINE").contains(action)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST,
                    Map.of("action", action, "reason", "invalid action"));
        }

        rateVersionRepository.findById(rateVersionId)
                .orElseThrow(() -> new ServiceException(ErrorCode.BAD_REQUEST,
                        Map.of("rate_version_id", rateVersionId.toString(), "reason", "not found")));
    }
}


