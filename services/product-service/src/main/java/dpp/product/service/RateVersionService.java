package dpp.product.service;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.product.dto.RateVersionResponse;
import dpp.product.entity.LoadingFactor;
import dpp.product.entity.RateVersion;
import dpp.product.repository.LoadingFactorRepository;
import dpp.product.repository.RateVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * Append-only rate change (R32.2/R32.4): create a NEW current rate version and
     * attach the loading factor to it. The previous current version is retired
     * (is_current=false) but preserved for lineage. This is the production path
     * for {@code PUT /admin/loading-factors}.
     */
    public LoadingFactor addLoadingFactorAsNewVersion(String line, Double loadingValue, String createdBy) {
        if (!VALID_LINES.contains(line)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST,
                    Map.of("line", line, "valid_lines", VALID_LINES));
        }
        RateVersion version = createNewRateVersion(createdBy);
        LoadingFactor lf = LoadingFactor.builder()
                .rateVersionId(version.getRateVersionId())
                .line(line)
                .loadingValue(loadingValue)
                .build();
        return loadingFactorRepository.save(lf);
    }

    // Eligibility rules have been removed from the product scope (R26 automatic
    // rules dropped). Rate_Version now covers loading factors + product config only.
}


