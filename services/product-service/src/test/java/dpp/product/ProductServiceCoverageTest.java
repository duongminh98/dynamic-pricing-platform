package dpp.product;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.product.dto.CoverageOptionResponse;
import dpp.product.dto.ProductDetail;
import dpp.product.dto.ProductRequest;
import dpp.product.dto.ProductResponse;
import dpp.product.dto.ProductSummary;
import dpp.product.entity.CoverageOption;
import dpp.product.entity.Product;
import dpp.product.repository.CoverageOptionRepository;
import dpp.product.repository.ProductRepository;
import dpp.product.service.ProductService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductServiceCoverageTest {

    private Product product(String id, String category, boolean active) {
        return Product.builder()
                .productId(id)
                .category(category)
                .productName("Test Product")
                .coverageAmountVnd(100_000_000L)
                .deductibleVnd(0L)
                .basePremiumVnd(2_000_000L)
                .adminFeeVnd(500_000L)
                .active(active)
                .build();
    }

    @Test
    void listActiveProductsByLineReturnsProducts() {
        ProductRepository repo = mock(ProductRepository.class);
        when(repo.findByCategoryAndActiveTrue("health"))
                .thenReturn(List.of(product("HEALTH_BASIC", "health", true)));

        ProductService svc = new ProductService(repo, mock(CoverageOptionRepository.class));
        List<ProductSummary> result = svc.listActiveProducts("health");

        assertEquals(1, result.size());
        assertEquals("HEALTH_BASIC", result.get(0).getProductId());
        assertEquals("health", result.get(0).getLine());
    }

    @Test
    void listActiveProductsWithBlankLineReturnsAll() {
        ProductRepository repo = mock(ProductRepository.class);
        when(repo.findByActiveTrue())
                .thenReturn(List.of(product("HEALTH_BASIC", "health", true)));

        ProductService svc = new ProductService(repo, mock(CoverageOptionRepository.class));
        List<ProductSummary> result = svc.listActiveProducts("");

        assertEquals(1, result.size());
    }

    @Test
    void getProductReturnsDetail() {
        ProductRepository repo = mock(ProductRepository.class);
        when(repo.findByProductIdAndActiveTrue("HEALTH_BASIC"))
                .thenReturn(Optional.of(product("HEALTH_BASIC", "health", true)));

        ProductService svc = new ProductService(repo, mock(CoverageOptionRepository.class));
        ProductDetail detail = svc.getProduct("HEALTH_BASIC");

        assertEquals("HEALTH_BASIC", detail.getProductId());
        assertEquals("health", detail.getCategory());
        assertEquals(2_000_000L, detail.getBasePremiumVnd());
    }

    @Test
    void getCoverageOptionsReturnsFilteredOptions() {
        ProductRepository repo = mock(ProductRepository.class);
        CoverageOptionRepository coRepo = mock(CoverageOptionRepository.class);

        when(repo.findByCategoryAndActiveTrue("motorbike"))
                .thenReturn(List.of(product("MOTO_BASIC", "motorbike", true)));

        CoverageOption co1 = CoverageOption.builder()
                .coverageOptionId(UUID.randomUUID())
                .productId("MOTO_BASIC")
                .coverageAmountVnd(50_000_000L)
                .deductibleVnd(500_000L)
                .basePremiumVnd(800_000L)
                .adminFeeVnd(100_000L)
                .build();
        CoverageOption co2 = CoverageOption.builder()
                .coverageOptionId(UUID.randomUUID())
                .productId("HEALTH_BASIC")
                .coverageAmountVnd(100_000_000L)
                .deductibleVnd(0L)
                .basePremiumVnd(2_000_000L)
                .adminFeeVnd(500_000L)
                .build();
        when(coRepo.findAll()).thenReturn(List.of(co1, co2));

        ProductService svc = new ProductService(repo, coRepo);
        List<CoverageOptionResponse> result = svc.getCoverageOptions("motorbike");

        assertEquals(1, result.size());
        assertEquals(50_000_000L, result.get(0).getCoverageAmountVnd());
    }

    @Test
    void saveProductCreatesProduct() {
        ProductRepository repo = mock(ProductRepository.class);
        when(repo.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductService svc = new ProductService(repo, mock(CoverageOptionRepository.class));
        ProductRequest req = new ProductRequest();
        req.setProductId("NEW_PRODUCT");
        req.setCategory("travel");
        req.setProductName("Travel Basic");
        req.setCoverageAmountVnd(50_000_000L);
        req.setDeductibleVnd(1_000_000L);
        req.setBasePremiumVnd(500_000L);
        req.setAdminFeeVnd(50_000L);
        req.setActive(true);

        ProductResponse resp = svc.saveProduct(req);

        assertEquals("NEW_PRODUCT", resp.getProductId());
        assertEquals("travel", resp.getCategory());
        assertTrue(resp.isActive());
    }

    @Test
    void saveProductDefaultsActiveToTrue() {
        ProductRepository repo = mock(ProductRepository.class);
        when(repo.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductService svc = new ProductService(repo, mock(CoverageOptionRepository.class));
        ProductRequest req = new ProductRequest();
        req.setProductId("NEW_PRODUCT_2");
        req.setCategory("home");
        req.setProductName("Home Basic");
        req.setCoverageAmountVnd(500_000_000L);
        req.setDeductibleVnd(5_000_000L);
        req.setBasePremiumVnd(3_000_000L);
        req.setAdminFeeVnd(200_000L);
        req.setActive(null);

        ProductResponse resp = svc.saveProduct(req);

        assertTrue(resp.isActive());
    }

    @Test
    void saveProductRejectsInvalidLine() {
        ProductRepository repo = mock(ProductRepository.class);
        ProductService svc = new ProductService(repo, mock(CoverageOptionRepository.class));
        ProductRequest req = new ProductRequest();
        req.setProductId("BAD");
        req.setCategory("invalid");
        req.setProductName("Bad");
        req.setCoverageAmountVnd(0L);
        req.setDeductibleVnd(0L);
        req.setBasePremiumVnd(0L);
        req.setAdminFeeVnd(0L);

        ServiceException ex = assertThrows(ServiceException.class, () -> svc.saveProduct(req));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    void validateLineRejectsInvalid() {
        ProductService svc = new ProductService(mock(ProductRepository.class), mock(CoverageOptionRepository.class));
        ServiceException ex = assertThrows(ServiceException.class, () -> svc.validateLine("bad"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    void validateLineAcceptsValid() {
        ProductService svc = new ProductService(mock(ProductRepository.class), mock(CoverageOptionRepository.class));
        assertDoesNotThrow(() -> svc.validateLine("health"));
    }

    @Test
    void getValidLinesReturnsAllLines() {
        assertEquals(6, ProductService.getValidLines().size());
        assertTrue(ProductService.getValidLines().contains("health"));
        assertTrue(ProductService.getValidLines().contains("motorbike"));
    }
}
