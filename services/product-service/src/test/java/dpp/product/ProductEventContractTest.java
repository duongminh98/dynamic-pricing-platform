package dpp.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dpp.common.outbox.OutboxPublisher;
import dpp.product.dto.ProductRequest;
import dpp.product.entity.LoadingFactor;
import dpp.product.entity.Product;
import dpp.product.entity.RateVersion;
import dpp.product.entity.GeoRiskVersion;
import dpp.product.entity.CostIndexVersion;
import dpp.product.repository.GeoRiskVersionRepository;
import dpp.product.repository.GeoRiskIndexRowRepository;
import dpp.product.repository.CostIndexVersionRepository;
import dpp.product.repository.CostIndexRowRepository;
import dpp.product.dto.GeoRiskVersionRequest;
import dpp.product.dto.GeoRiskRowRequest;
import dpp.product.dto.CostIndexVersionRequest;
import dpp.product.dto.CostIndexRowRequest;
import dpp.product.service.PricingReferenceDataService;
import dpp.product.repository.CoverageOptionRepository;
import dpp.product.repository.LoadingFactorRepository;
import dpp.product.repository.ProductRepository;
import dpp.product.repository.RateVersionRepository;
import dpp.product.service.ProductService;
import dpp.product.service.RateVersionService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProductEventContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void saveProductEmitsProductUpdatedEvent() throws Exception {
        ProductRepository productRepository = mock(ProductRepository.class);
        CoverageOptionRepository coverageRepository = mock(CoverageOptionRepository.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductService service = new ProductService(productRepository, coverageRepository);
        service.setOutboxPublisher(outbox);
        ProductRequest request = new ProductRequest();
        request.setProductId("CAR_BASIC");
        request.setCategory("car");
        request.setProductName("Car Basic");
        request.setCoverageAmountVnd(500_000_000L);
        request.setDeductibleVnd(1_000_000L);
        request.setBasePremiumVnd(3_000_000L);
        request.setAdminFeeVnd(100_000L);
        request.setActive(true);

        service.saveProduct(request);

        var payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(outbox).enqueue(anyString(), eq("ProductUpdated"), payload.capture());
        JsonNode node = objectMapper.readTree(payload.getValue());
        assertTrue(node.has("event_id"));
        assertEquals("ProductUpdated", node.get("event_type").asText());
        assertEquals("CAR_BASIC", node.get("product_id").asText());
        assertEquals("car", node.get("category").asText());
        assertEquals(500_000_000L, node.get("coverage_amount_vnd").asLong());
        assertTrue(node.get("active").asBoolean());
    }

    @Test
    void snapshotPublishesAllProductsAsProductUpdatedEvents() {
        ProductRepository productRepository = mock(ProductRepository.class);
        CoverageOptionRepository coverageRepository = mock(CoverageOptionRepository.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);
        when(productRepository.findAll()).thenReturn(List.of(
                Product.builder()
                        .productId("HEALTH_BASIC")
                        .category("health")
                        .productName("Health Basic")
                        .coverageAmountVnd(100_000_000L)
                        .deductibleVnd(0L)
                        .basePremiumVnd(500_000L)
                        .adminFeeVnd(50_000L)
                        .active(true)
                        .build(),
                Product.builder()
                        .productId("CAR_TPL")
                        .category("car")
                        .productName("Car TPL")
                        .coverageAmountVnd(300_000_000L)
                        .deductibleVnd(0L)
                        .basePremiumVnd(480_000L)
                        .adminFeeVnd(80_000L)
                        .active(true)
                        .build()
        ));

        ProductService service = new ProductService(productRepository, coverageRepository);
        service.setOutboxPublisher(outbox);

        int count = service.publishProductCatalogSnapshot();

        assertEquals(2, count);
        verify(outbox, times(2)).enqueue(anyString(), eq("ProductUpdated"), anyString());
    }

    @Test
    void loadingFactorChangeEmitsRateVersionActivatedEvent() throws Exception {
        RateVersionRepository rateRepository = mock(RateVersionRepository.class);
        LoadingFactorRepository loadingRepository = mock(LoadingFactorRepository.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);
        UUID rateVersionId = UUID.randomUUID();
        RateVersion savedVersion = RateVersion.builder()
                .rateVersionId(rateVersionId)
                .effectiveAt(Instant.now())
                .createdBy("admin")
                .isCurrent(true)
                .createdAt(Instant.now())
                .build();
        when(rateRepository.findByIsCurrentTrue()).thenReturn(Optional.empty());
        when(rateRepository.save(any(RateVersion.class))).thenReturn(savedVersion);
        when(loadingRepository.save(any(LoadingFactor.class))).thenAnswer(inv -> inv.getArgument(0));
        when(loadingRepository.findByRateVersionId(rateVersionId)).thenReturn(List.of(
                LoadingFactor.builder().rateVersionId(rateVersionId).line("health").loadingValue(1.5).build()
        ));

        RateVersionService service = new RateVersionService(rateRepository, loadingRepository);
        service.setOutboxPublisher(outbox);
        service.addLoadingFactorAsNewVersion("health", 1.5, "admin");

        var payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(outbox).enqueue(anyString(), eq("RateVersionActivated"), payload.capture());
        JsonNode node = objectMapper.readTree(payload.getValue());
        assertTrue(node.has("event_id"));
        assertEquals("RateVersionActivated", node.get("event_type").asText());
        assertEquals(rateVersionId.toString(), node.get("rate_version_id").asText());
        assertTrue(node.get("loading_factors").isArray());
        JsonNode health = null;
        for (JsonNode factor : node.get("loading_factors")) {
            if ("health".equals(factor.get("line").asText())) {
                health = factor;
                break;
            }
        }
        assertNotNull(health);
        assertEquals(1.5, health.get("loading_value").asDouble(), 0.0001);
    }
    @Test
    void replaceGeoRiskEmitsGeoRiskVersionActivatedEvent() throws Exception {
        GeoRiskVersionRepository versionRepository = mock(GeoRiskVersionRepository.class);
        GeoRiskIndexRowRepository rowRepository = mock(GeoRiskIndexRowRepository.class);
        CostIndexVersionRepository costVersionRepository = mock(CostIndexVersionRepository.class);
        CostIndexRowRepository costRowRepository = mock(CostIndexRowRepository.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);
        UUID versionId = UUID.randomUUID();
        GeoRiskVersion savedVersion = GeoRiskVersion.builder()
                .versionId(versionId)
                .status("ACTIVE")
                .effectiveFrom(Instant.now())
                .createdBy("admin")
                .approvedBy("admin")
                .changeReason("test")
                .checksum("abc")
                .createdAt(Instant.now())
                .activatedAt(Instant.now())
                .build();
        when(versionRepository.findFirstByStatusOrderByActivatedAtDesc("ACTIVE")).thenReturn(Optional.empty(), Optional.of(savedVersion));
        when(versionRepository.save(any(GeoRiskVersion.class))).thenReturn(savedVersion);
        when(rowRepository.findByVersionIdOrderByProvince(versionId)).thenReturn(List.of());

        PricingReferenceDataService service = new PricingReferenceDataService(versionRepository, rowRepository, costVersionRepository, costRowRepository);
        service.setOutboxPublisher(outbox);
        service.replaceGeoRisk(new GeoRiskVersionRequest("test", List.of(
                new GeoRiskRowRequest("Ha Noi", "Red River Delta", "tier1", 0.9, 0.3, 0.5, 0.4, 0.2, 0.1, 0.6, 0.8, 1.1, 1.2, 1.3)
        )), "admin");

        var payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(outbox).enqueue(anyString(), eq("GeoRiskVersionActivated"), payload.capture());
        JsonNode node = objectMapper.readTree(payload.getValue());
        assertTrue(node.has("event_id"));
        assertEquals("GeoRiskVersionActivated", node.get("event_type").asText());
        assertEquals(versionId.toString(), node.get("version_id").asText());
    }

    @Test
    void replaceCostIndicesEmitsCostIndexVersionActivatedEvent() throws Exception {
        GeoRiskVersionRepository versionRepository = mock(GeoRiskVersionRepository.class);
        GeoRiskIndexRowRepository rowRepository = mock(GeoRiskIndexRowRepository.class);
        CostIndexVersionRepository costVersionRepository = mock(CostIndexVersionRepository.class);
        CostIndexRowRepository costRowRepository = mock(CostIndexRowRepository.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);
        UUID versionId = UUID.randomUUID();
        CostIndexVersion savedVersion = CostIndexVersion.builder()
                .versionId(versionId)
                .status("ACTIVE")
                .effectiveFrom(Instant.now())
                .createdBy("admin")
                .approvedBy("admin")
                .changeReason("test")
                .checksum("abc")
                .createdAt(Instant.now())
                .activatedAt(Instant.now())
                .build();
        when(costVersionRepository.findFirstByStatusOrderByActivatedAtDesc("ACTIVE")).thenReturn(Optional.empty(), Optional.of(savedVersion));
        when(costVersionRepository.save(any(CostIndexVersion.class))).thenReturn(savedVersion);
        when(costRowRepository.findByVersionIdOrderByMonthStartDesc(versionId)).thenReturn(List.of());

        PricingReferenceDataService service = new PricingReferenceDataService(versionRepository, rowRepository, costVersionRepository, costRowRepository);
        service.setOutboxPublisher(outbox);
        service.replaceCostIndices(new CostIndexVersionRequest("test", List.of(
                new CostIndexRowRequest(2026, 7, "2026-07-01", 1.02, 1.03, 1.04, 1.05, 1.06)
        )), "admin");

        var payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(outbox).enqueue(anyString(), eq("CostIndexVersionActivated"), payload.capture());
        JsonNode node = objectMapper.readTree(payload.getValue());
        assertTrue(node.has("event_id"));
        assertEquals("CostIndexVersionActivated", node.get("event_type").asText());
        assertEquals(versionId.toString(), node.get("version_id").asText());
    }

}

