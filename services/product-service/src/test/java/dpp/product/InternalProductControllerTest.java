package dpp.product;

import dpp.common.api.ServiceException;
import dpp.product.controller.InternalProductController;
import dpp.product.dto.LoadingFactorResponse;
import dpp.product.dto.ProductResponse;
import dpp.product.entity.LoadingFactor;
import dpp.product.entity.Product;
import dpp.product.entity.RateVersion;
import dpp.product.repository.LoadingFactorRepository;
import dpp.product.repository.ProductRepository;
import dpp.product.repository.RateVersionRepository;
import dpp.product.service.ProductService;
import dpp.product.service.RateVersionService;
import dpp.product.repository.CoverageOptionRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InternalProductControllerTest {

    private Product sampleProduct(String id, String category, boolean active) {
        return Product.builder()
                .productId(id)
                .category(category)
                .productName("Test " + id)
                .coverageAmountVnd(100_000_000L)
                .deductibleVnd(500_000L)
                .basePremiumVnd(1_000_000L)
                .adminFeeVnd(20_000L)
                .active(active)
                .build();
    }

    private ProductService productServiceWith(ProductRepository repo) {
        return new ProductService(repo, mock(CoverageOptionRepository.class));
    }

    // ── GET /internal/products returns all (active + inactive) ──

    @Test
    void listAllProductsReturnsActiveAndInactive() {
        ProductRepository repo = mock(ProductRepository.class);
        Product p1 = sampleProduct("health-basic", "health", true);
        Product p2 = sampleProduct("motor-old", "motorbike", false);
        when(repo.findAll()).thenReturn(List.of(p1, p2));

        ProductService svc = productServiceWith(repo);
        InternalProductController controller = new InternalProductController(svc, mock(RateVersionService.class));

        List<ProductResponse> result = controller.listAllProducts();

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(r -> r.getProductId().equals("health-basic") && r.isActive()));
        assertTrue(result.stream().anyMatch(r -> r.getProductId().equals("motor-old") && !r.isActive()));
    }

    // ── GET /internal/products/{id} returns 200 ──

    @Test
    void getProductReturnsProduct() {
        ProductRepository repo = mock(ProductRepository.class);
        Product p = sampleProduct("car-premium", "car", true);
        when(repo.findById("car-premium")).thenReturn(Optional.of(p));

        ProductService svc = productServiceWith(repo);
        InternalProductController controller = new InternalProductController(svc, mock(RateVersionService.class));

        ProductResponse result = controller.getProduct("car-premium");

        assertEquals("car-premium", result.getProductId());
        assertEquals("car", result.getCategory());
    }

    // ── GET /internal/products/{id} returns 404 for missing ──

    @Test
    void getProductReturns404WhenMissing() {
        ProductRepository repo = mock(ProductRepository.class);
        when(repo.findById("nonexistent")).thenReturn(Optional.empty());

        ProductService svc = productServiceWith(repo);
        InternalProductController controller = new InternalProductController(svc, mock(RateVersionService.class));

        assertThrows(ServiceException.class, () -> controller.getProduct("nonexistent"));
    }

    // ── GET /internal/products/{id} returns inactive product too ──

    @Test
    void getProductReturnsInactiveProduct() {
        ProductRepository repo = mock(ProductRepository.class);
        Product p = sampleProduct("home-deprecated", "home", false);
        when(repo.findById("home-deprecated")).thenReturn(Optional.of(p));

        ProductService svc = productServiceWith(repo);
        InternalProductController controller = new InternalProductController(svc, mock(RateVersionService.class));

        ProductResponse result = controller.getProduct("home-deprecated");

        assertEquals("home-deprecated", result.getProductId());
        assertFalse(result.isActive());
    }

    // ── getCurrentLoadingFactors: current version with factors ──

    @Test
    void getCurrentLoadingFactorsWithExistingFactors() {
        UUID rvId = UUID.randomUUID();
        RateVersion rv = RateVersion.builder()
                .rateVersionId(rvId)
                .effectiveAt(Instant.now())
                .createdBy("admin")
                .isCurrent(true)
                .createdAt(Instant.now())
                .build();

        RateVersionRepository rvRepo = mock(RateVersionRepository.class);
        when(rvRepo.findByIsCurrentTrue()).thenReturn(Optional.of(rv));

        LoadingFactorRepository lfRepo = mock(LoadingFactorRepository.class);
        LoadingFactor lfHealth = LoadingFactor.builder()
                .loadingFactorId(UUID.randomUUID())
                .rateVersionId(rvId)
                .line("health")
                .loadingValue(1.2)
                .build();
        when(lfRepo.findByRateVersionId(rvId)).thenReturn(List.of(lfHealth));

        RateVersionService rvSvc = new RateVersionService(rvRepo, lfRepo);
        InternalProductController controller = new InternalProductController(mock(ProductService.class), rvSvc);

        List<LoadingFactorResponse> result = controller.getCurrentLoadingFactors();

        assertEquals(6, result.size());
        assertTrue(result.stream().anyMatch(r -> r.getLine().equals("health") && r.getLoadingValue() == 1.2));
        assertTrue(result.stream().allMatch(r -> r.getRateVersionId().equals(rvId)));
    }

    // ── getCurrentLoadingFactors: missing lines default to 1.0 ──

    @Test
    void getCurrentLoadingFactorsMissingLinesDefaultTo1() {
        UUID rvId = UUID.randomUUID();
        RateVersion rv = RateVersion.builder()
                .rateVersionId(rvId)
                .effectiveAt(Instant.now())
                .createdBy("admin")
                .isCurrent(true)
                .createdAt(Instant.now())
                .build();

        RateVersionRepository rvRepo = mock(RateVersionRepository.class);
        when(rvRepo.findByIsCurrentTrue()).thenReturn(Optional.of(rv));

        LoadingFactorRepository lfRepo = mock(LoadingFactorRepository.class);
        when(lfRepo.findByRateVersionId(rvId)).thenReturn(List.of());

        RateVersionService rvSvc = new RateVersionService(rvRepo, lfRepo);
        InternalProductController controller = new InternalProductController(mock(ProductService.class), rvSvc);

        List<LoadingFactorResponse> result = controller.getCurrentLoadingFactors();

        assertEquals(6, result.size());
        assertTrue(result.stream().allMatch(r -> r.getLoadingValue() == 1.0));
    }

    // ── getCurrentLoadingFactors: no current version → 6 lines all 1.0 ──

    @Test
    void getCurrentLoadingFactorsNoCurrentVersion() {
        RateVersionRepository rvRepo = mock(RateVersionRepository.class);
        when(rvRepo.findByIsCurrentTrue()).thenReturn(Optional.empty());

        LoadingFactorRepository lfRepo = mock(LoadingFactorRepository.class);

        RateVersionService rvSvc = new RateVersionService(rvRepo, lfRepo);
        InternalProductController controller = new InternalProductController(mock(ProductService.class), rvSvc);

        List<LoadingFactorResponse> result = controller.getCurrentLoadingFactors();

        assertEquals(6, result.size());
        assertTrue(result.stream().allMatch(r -> r.getLoadingValue() == 1.0));
        assertTrue(result.stream().allMatch(r -> r.getRateVersionId() == null));
    }
}
