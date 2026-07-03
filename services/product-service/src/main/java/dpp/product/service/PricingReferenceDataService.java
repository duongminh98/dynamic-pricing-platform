package dpp.product.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.common.outbox.OutboxPublisher;
import dpp.product.dto.*;
import dpp.product.entity.*;
import dpp.product.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

@Service
@Transactional
public class PricingReferenceDataService {
    private static final String ACTIVE = "ACTIVE";
    private static final String RETIRED = "RETIRED";

    private final GeoRiskVersionRepository geoVersions;
    private final GeoRiskIndexRowRepository geoRows;
    private final CostIndexVersionRepository costVersions;
    private final CostIndexRowRepository costRows;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private OutboxPublisher outboxPublisher;

    public PricingReferenceDataService(GeoRiskVersionRepository geoVersions,
                                       GeoRiskIndexRowRepository geoRows,
                                       CostIndexVersionRepository costVersions,
                                       CostIndexRowRepository costRows) {
        this.geoVersions = geoVersions;
        this.geoRows = geoRows;
        this.costVersions = costVersions;
        this.costRows = costRows;
    }

    public ReferenceDataVersionResponse<GeoRiskRowResponse> replaceGeoRisk(GeoRiskVersionRequest request, String actor) {
        validateGeoRows(request.getRows());
        String checksum = checksum(request.getRows());
        geoVersions.findFirstByStatusOrderByActivatedAtDesc(ACTIVE).ifPresent(v -> {
            v.setStatus(RETIRED);
            geoVersions.save(v);
        });
        Instant now = Instant.now();
        GeoRiskVersion version = geoVersions.save(GeoRiskVersion.builder()
                .status(ACTIVE).effectiveFrom(now).createdBy(actor).approvedBy(actor)
                .changeReason(request.getChangeReason()).checksum(checksum).createdAt(now).activatedAt(now).build());
        geoVersions.flush();
        request.getRows().forEach(row -> geoRows.save(toGeoEntity(version.getVersionId(), row)));
        geoRows.flush();
        ReferenceDataVersionResponse<GeoRiskRowResponse> response = getActiveGeoRisk();
        enqueueGeoRiskActivated(response);
        return response;
    }

    public ReferenceDataVersionResponse<CostIndexRowResponse> replaceCostIndices(CostIndexVersionRequest request, String actor) {
        validateCostRows(request.getRows());
        String checksum = checksum(request.getRows());
        costVersions.findFirstByStatusOrderByActivatedAtDesc(ACTIVE).ifPresent(v -> {
            v.setStatus(RETIRED);
            costVersions.save(v);
        });
        Instant now = Instant.now();
        CostIndexVersion version = costVersions.save(CostIndexVersion.builder()
                .status(ACTIVE).effectiveFrom(now).createdBy(actor).approvedBy(actor)
                .changeReason(request.getChangeReason()).checksum(checksum).createdAt(now).activatedAt(now).build());
        costVersions.flush();
        request.getRows().forEach(row -> costRows.save(toCostEntity(version.getVersionId(), row)));
        costRows.flush();
        ReferenceDataVersionResponse<CostIndexRowResponse> response = getActiveCostIndices();
        enqueueCostIndexActivated(response);
        return response;
    }

    @Transactional(readOnly = true)
    public ReferenceDataVersionResponse<GeoRiskRowResponse> getActiveGeoRisk() {
        GeoRiskVersion version = geoVersions.findFirstByStatusOrderByActivatedAtDesc(ACTIVE)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("reference_type", "geo_risk")));
        return toGeoResponse(version, geoRows.findByVersionIdOrderByProvince(version.getVersionId()));
    }

    @Transactional(readOnly = true)
    public ReferenceDataVersionResponse<CostIndexRowResponse> getActiveCostIndices() {
        CostIndexVersion version = costVersions.findFirstByStatusOrderByActivatedAtDesc(ACTIVE)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("reference_type", "cost_indices")));
        return toCostResponse(version, costRows.findByVersionIdOrderByMonthStartDesc(version.getVersionId()));
    }

    @Transactional(readOnly = true)
    public List<ReferenceDataVersionResponse<GeoRiskRowResponse>> listGeoRiskVersions() {
        return geoVersions.findAllByOrderByCreatedAtDesc().stream()
                .map(v -> toGeoResponse(v, geoRows.findByVersionIdOrderByProvince(v.getVersionId()))).toList();
    }

    @Transactional(readOnly = true)
    public List<ReferenceDataVersionResponse<CostIndexRowResponse>> listCostIndexVersions() {
        return costVersions.findAllByOrderByCreatedAtDesc().stream()
                .map(v -> toCostResponse(v, costRows.findByVersionIdOrderByMonthStartDesc(v.getVersionId()))).toList();
    }

    @Autowired(required = false)
    public void setOutboxPublisher(OutboxPublisher outboxPublisher) {
        this.outboxPublisher = outboxPublisher;
    }

    private void validateGeoRows(List<GeoRiskRowRequest> rows) {
        if (rows == null || rows.isEmpty()) throw new ServiceException(ErrorCode.BAD_REQUEST, Map.of("reason", "rows required"));
        Set<String> provinces = new HashSet<>();
        for (GeoRiskRowRequest row : rows) {
            if (row.getProvince() == null || row.getProvince().isBlank() || !provinces.add(row.getProvince())) {
                throw new ServiceException(ErrorCode.BAD_REQUEST, Map.of("reason", "province missing or duplicated"));
            }
        }
    }

    private void validateCostRows(List<CostIndexRowRequest> rows) {
        if (rows == null || rows.isEmpty()) throw new ServiceException(ErrorCode.BAD_REQUEST, Map.of("reason", "rows required"));
        Set<String> months = new HashSet<>();
        for (CostIndexRowRequest row : rows) {
            if (row.getMonthStart() == null || !months.add(row.getMonthStart())) {
                throw new ServiceException(ErrorCode.BAD_REQUEST, Map.of("reason", "month_start missing or duplicated"));
            }
        }
    }

    private GeoRiskIndexRow toGeoEntity(UUID versionId, GeoRiskRowRequest r) {
        return GeoRiskIndexRow.builder().versionId(versionId).province(r.getProvince()).region(r.getRegion()).urbanTierGeo(r.getUrbanTierGeo())
                .trafficDensityScore(n(r.getTrafficDensityScore())).vehicleTheftRiskScore(n(r.getVehicleTheftRiskScore()))
                .accidentFrequencyIndex(n(r.getAccidentFrequencyIndex())).floodRiskScore(n(r.getFloodRiskScore()))
                .stormRiskScore(n(r.getStormRiskScore())).fireRiskScore(n(r.getFireRiskScore())).crimeRiskScore(n(r.getCrimeRiskScore()))
                .healthcareAccessScore(n(r.getHealthcareAccessScore())).hospitalCostIndex(n(r.getHospitalCostIndex()))
                .repairCostIndex(n(r.getRepairCostIndex())).constructionCostIndex(n(r.getConstructionCostIndex())).build();
    }

    private CostIndexRow toCostEntity(UUID versionId, CostIndexRowRequest r) {
        LocalDate monthStart = LocalDate.parse(r.getMonthStart());
        return CostIndexRow.builder().versionId(versionId).year(r.getYear()).month(r.getMonth()).monthStart(monthStart)
                .medicalInflationIndex(n(r.getMedicalInflationIndex())).vehicleRepairInflationIndex(n(r.getVehicleRepairInflationIndex()))
                .constructionInflationIndex(n(r.getConstructionInflationIndex())).travelMedicalCostIndex(n(r.getTravelMedicalCostIndex()))
                .generalExpenseIndex(n(r.getGeneralExpenseIndex())).build();
    }

    private double n(Double value) { return value == null ? 0.0 : value; }

    private ReferenceDataVersionResponse<GeoRiskRowResponse> toGeoResponse(GeoRiskVersion v, List<GeoRiskIndexRow> rows) {
        return ReferenceDataVersionResponse.<GeoRiskRowResponse>builder().versionId(v.getVersionId()).referenceType("geo_risk")
                .status(v.getStatus()).effectiveFrom(v.getEffectiveFrom()).createdBy(v.getCreatedBy()).approvedBy(v.getApprovedBy())
                .changeReason(v.getChangeReason()).checksum(v.getChecksum()).createdAt(v.getCreatedAt()).activatedAt(v.getActivatedAt())
                .rows(rows.stream().map(this::toGeoRow).toList()).build();
    }

    private ReferenceDataVersionResponse<CostIndexRowResponse> toCostResponse(CostIndexVersion v, List<CostIndexRow> rows) {
        return ReferenceDataVersionResponse.<CostIndexRowResponse>builder().versionId(v.getVersionId()).referenceType("cost_indices")
                .status(v.getStatus()).effectiveFrom(v.getEffectiveFrom()).createdBy(v.getCreatedBy()).approvedBy(v.getApprovedBy())
                .changeReason(v.getChangeReason()).checksum(v.getChecksum()).createdAt(v.getCreatedAt()).activatedAt(v.getActivatedAt())
                .rows(rows.stream().map(this::toCostRow).toList()).build();
    }

    private GeoRiskRowResponse toGeoRow(GeoRiskIndexRow r) {
        return GeoRiskRowResponse.builder().province(r.getProvince()).region(r.getRegion()).urbanTierGeo(r.getUrbanTierGeo())
                .trafficDensityScore(r.getTrafficDensityScore()).vehicleTheftRiskScore(r.getVehicleTheftRiskScore())
                .accidentFrequencyIndex(r.getAccidentFrequencyIndex()).floodRiskScore(r.getFloodRiskScore()).stormRiskScore(r.getStormRiskScore())
                .fireRiskScore(r.getFireRiskScore()).crimeRiskScore(r.getCrimeRiskScore()).healthcareAccessScore(r.getHealthcareAccessScore())
                .hospitalCostIndex(r.getHospitalCostIndex()).repairCostIndex(r.getRepairCostIndex()).constructionCostIndex(r.getConstructionCostIndex()).build();
    }

    private CostIndexRowResponse toCostRow(CostIndexRow r) {
        return CostIndexRowResponse.builder().year(r.getYear()).month(r.getMonth()).monthStart(r.getMonthStart().toString())
                .medicalInflationIndex(r.getMedicalInflationIndex()).vehicleRepairInflationIndex(r.getVehicleRepairInflationIndex())
                .constructionInflationIndex(r.getConstructionInflationIndex()).travelMedicalCostIndex(r.getTravelMedicalCostIndex())
                .generalExpenseIndex(r.getGeneralExpenseIndex()).build();
    }

    private String checksum(Object rows) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = objectMapper.writeValueAsString(rows).getBytes(StandardCharsets.UTF_8);
            StringBuilder out = new StringBuilder();
            for (byte b : digest.digest(bytes)) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute reference data checksum", e);
        }
    }

    private void enqueueGeoRiskActivated(ReferenceDataVersionResponse<GeoRiskRowResponse> response) {
        enqueue("GeoRiskVersionActivated", response);
    }

    private void enqueueCostIndexActivated(ReferenceDataVersionResponse<CostIndexRowResponse> response) {
        enqueue("CostIndexVersionActivated", response);
    }

    private void enqueue(String eventType, ReferenceDataVersionResponse<?> response) {
        if (outboxPublisher == null) return;
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_id", eventId);
        payload.put("event_type", eventType);
        payload.put("schema_version", 1);
        payload.put("producer", "product-service");
        payload.put("version_id", response.getVersionId().toString());
        payload.put("reference_type", response.getReferenceType());
        payload.put("status", response.getStatus());
        payload.put("effective_from", response.getEffectiveFrom().toString());
        payload.put("checksum", response.getChecksum());
        payload.put("change_reason", response.getChangeReason());
        payload.put("occurred_at", OffsetDateTime.now().toString());
        payload.put("rows", response.getRows());
        try {
            outboxPublisher.enqueue(eventId, eventType, objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new RuntimeException("Failed to enqueue " + eventType, e);
        }
    }
}
