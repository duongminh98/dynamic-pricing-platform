package dpp.product.service;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.common.outbox.OutboxPublisher;
import dpp.product.dto.LoadingFactorResponse;
import dpp.product.dto.RateVersionResponse;
import dpp.product.entity.LoadingFactor;
import dpp.product.entity.RateVersion;
import dpp.product.repository.LoadingFactorRepository;
import dpp.product.repository.RateVersionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
@Slf4j
public class RateVersionService {

    public RateVersionService(RateVersionRepository rateVersionRepository,
                              LoadingFactorRepository loadingFactorRepository) {
        this.rateVersionRepository = rateVersionRepository;
        this.loadingFactorRepository = loadingFactorRepository;
    }

    private static final Set<String> VALID_LINES = Set.of(
            "health", "motorbike", "car", "home", "accident", "travel"
    );

    private final RateVersionRepository rateVersionRepository;
    private final LoadingFactorRepository loadingFactorRepository;
    private OutboxPublisher outboxPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RateVersion createNewRateVersion(String createdBy) {
        RateVersion version = createNewRateVersion(createdBy, true);
        enqueueRateVersionActivated(version.getRateVersionId());
        return version;
    }

    private RateVersion createNewRateVersion(String createdBy, boolean flushNewVersion) {
        // Retire the previous current version FIRST and flush it, so the partial
        // unique index idx_rate_version_current (WHERE is_current=true) never sees
        // two current rows. Hibernate orders INSERTs before UPDATEs within a flush,
        // so without an explicit saveAndFlush the new current row would be inserted
        // while the old one is still is_current=true -> constraint violation.
        // Append-only: the old version is retired (is_current=false), never deleted.
        rateVersionRepository.findByIsCurrentTrue().ifPresent(rv -> {
            rv.setIsCurrent(false);
            rateVersionRepository.saveAndFlush(rv);
        });

        RateVersion newVersion = RateVersion.builder()
                .effectiveAt(Instant.now())
                .createdBy(createdBy)
                .isCurrent(true)
                .createdAt(Instant.now())
                .build();

        RateVersion saved = rateVersionRepository.save(newVersion);
        if (flushNewVersion) {
            rateVersionRepository.flush();
        }
        return saved;
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
        RateVersion version = createNewRateVersion(createdBy, false);
        LoadingFactor lf = LoadingFactor.builder()
                .rateVersionId(version.getRateVersionId())
                .line(line)
                .loadingValue(loadingValue)
                .build();
        LoadingFactor saved = loadingFactorRepository.save(lf);
        loadingFactorRepository.flush();
        enqueueRateVersionActivated(version.getRateVersionId());
        return saved;
    }

    // Eligibility rules have been removed from the product scope (R26 automatic
    // rules dropped). Rate_Version now covers loading factors + product config only.

    @Transactional(readOnly = true)
    public List<LoadingFactorResponse> getCurrentLoadingFactors() {
        return rateVersionRepository.findByIsCurrentTrue()
                .map(rv -> buildLoadingFactorResponses(rv.getRateVersionId()))
                .orElseGet(() -> buildDefaultLoadingFactorResponses(null));
    }

    private List<LoadingFactorResponse> buildLoadingFactorResponses(java.util.UUID rateVersionId) {
        List<LoadingFactor> factors = loadingFactorRepository.findByRateVersionId(rateVersionId);
        Map<String, Double> factorMap = new java.util.HashMap<>();
        for (LoadingFactor lf : factors) {
            factorMap.put(lf.getLine(), lf.getLoadingValue());
        }
        List<LoadingFactorResponse> responses = new ArrayList<>();
        for (String line : VALID_LINES) {
            Double value = factorMap.getOrDefault(line, 1.0);
            responses.add(LoadingFactorResponse.builder()
                    .rateVersionId(rateVersionId)
                    .line(line)
                    .loadingValue(value)
                    .build());
        }
        return responses;
    }

    private List<LoadingFactorResponse> buildDefaultLoadingFactorResponses(java.util.UUID rateVersionId) {
        List<LoadingFactorResponse> responses = new ArrayList<>();
        for (String line : VALID_LINES) {
            responses.add(LoadingFactorResponse.builder()
                    .rateVersionId(rateVersionId)
                    .line(line)
                    .loadingValue(1.0)
                    .build());
        }
        return responses;
    }

    @Autowired(required = false)
    public void setOutboxPublisher(OutboxPublisher outboxPublisher) {
        this.outboxPublisher = outboxPublisher;
    }

    private void enqueueRateVersionActivated(java.util.UUID rateVersionId) {
        if (outboxPublisher == null) {
            return;
        }
        String eventId = java.util.UUID.randomUUID().toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_id", eventId);
        payload.put("event_type", "RateVersionActivated");
        payload.put("schema_version", 1);
        payload.put("producer", "product-service");
        payload.put("rate_version_id", rateVersionId != null ? rateVersionId.toString() : null);
        payload.put("occurred_at", OffsetDateTime.now().toString());
        List<Map<String, Object>> factors = buildLoadingFactorResponses(rateVersionId).stream()
                .map(row -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("rate_version_id", row.getRateVersionId() != null ? row.getRateVersionId().toString() : null);
                    item.put("line", row.getLine());
                    item.put("loading_value", row.getLoadingValue());
                    return item;
                })
                .toList();
        payload.put("loading_factors", factors);
        try {
            outboxPublisher.enqueue(eventId, "RateVersionActivated", objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new RuntimeException("Failed to enqueue RateVersionActivated", e);
        }
    }
}



