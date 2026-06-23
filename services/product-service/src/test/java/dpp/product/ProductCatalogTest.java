package dpp.product;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.product.entity.Product;
import dpp.product.repository.CoverageOptionRepository;
import dpp.product.repository.ProductRepository;
import dpp.product.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the public product catalog: distinguishes 404 not-found,
 * 400 invalid-line, and 200 empty-list for a valid line (design 3.2, 7.2).
 * Requirements: R3.3, R3.6, R3.7. Not a property-based test.
 */
class ProductCatalogTest {

    private ProductRepository productRepository;
    private CoverageOptionRepository coverageOptionRepository;
    private ProductService productService;

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        coverageOptionRepository = mock(CoverageOptionRepository.class);
        productService = new ProductService(productRepository, coverageOptionRepository);
    }

    @Test
    void getProduct_nonExistent_throwsServiceExceptionWithNotFoundDetails() {
        when(productRepository.findByProductIdAndActiveTrue("UNKNOWN")).thenReturn(Optional.empty());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> productService.getProduct("UNKNOWN"));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        Object details = ex.getDetails();
        assertInstanceOf(Map.class, details);
        Map<?, ?> map = (Map<?, ?>) details;
        assertEquals("UNKNOWN", map.get("product_id"));
        assertEquals("not found", map.get("reason"));
    }

    @Test
    void listProducts_invalidLine_throwsServiceExceptionDescribingValidLines() {
        when(productRepository.findByCategoryAndActiveTrue("invalid_line")).thenReturn(List.of());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> productService.listActiveProducts("invalid_line"));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        Object details = ex.getDetails();
        assertInstanceOf(Map.class, details);
        Map<?, ?> map = (Map<?, ?>) details;
        assertEquals("invalid_line", map.get("line"));
        assertInstanceOf(Set.class, map.get("valid_lines"));
    }

    @Test
    void listProducts_validLineWithNoProducts_returnsEmptyList() {
        when(productRepository.findByCategoryAndActiveTrue("travel")).thenReturn(List.of());

        List<?> result = productService.listActiveProducts("travel");

        assertNotNull(result);
        assertTrue(result.isEmpty(), "A valid line with no products must return 200 []");
    }

    @Test
    void listProducts_nullLine_returnsAllActive() {
        Product p = Product.builder()
                .productId("HEALTH_BASIC")
                .category("health")
                .productName("Basic Health")
                .coverageAmountVnd(100_000_000L)
                .deductibleVnd(0L)
                .basePremiumVnd(2_200_000L)
                .adminFeeVnd(500_000L)
                .active(true)
                .build();
        when(productRepository.findByActiveTrue()).thenReturn(List.of(p));

        List<?> result = productService.listActiveProducts(null);

        assertEquals(1, result.size());
    }

    @Test
    void getCoverageOptions_invalidLine_throwsServiceException() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> productService.getCoverageOptions("no_such_line"));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        verify(coverageOptionRepository, never()).findAll();
    }
}
