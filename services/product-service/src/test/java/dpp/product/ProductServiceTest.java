package dpp.product;

import dpp.product.service.ProductService;
import dpp.product.repository.ProductRepository;
import dpp.common.api.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
public class ProductServiceTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository productRepository;

    @Test
    public void testListActiveProducts() {
        // Ensure at least one product exists from seed data
        var list = productService.listActiveProducts(null);
        assertFalse(list.isEmpty(), "Seeded products should be present");
    }

    @Test
    public void testGetProductValid() {
        var product = productService.getProduct("HEALTH_BASIC");
        assertEquals("HEALTH_BASIC", product.getProductId());
        assertEquals("health", product.getCategory());
    }

    @Test
    public void testGetProductInvalid() {
        assertThrows(ServiceException.class, () -> productService.getProduct("UNKNOWN"));
    }
}
