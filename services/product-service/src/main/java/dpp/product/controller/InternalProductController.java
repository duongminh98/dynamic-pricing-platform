package dpp.product.controller;

import dpp.product.dto.LoadingFactorResponse;
import dpp.product.dto.ProductResponse;
import dpp.product.service.ProductService;
import dpp.product.service.RateVersionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Internal service-to-service endpoints (NOT exposed through Kong).
 * Used by the pricing service to load product catalog and loading factors.
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalProductController {

    private final ProductService productService;
    private final RateVersionService rateVersionService;

    @GetMapping("/products")
    public List<ProductResponse> listAllProducts() {
        return productService.listAllProducts();
    }

    @GetMapping("/products/{productId}")
    public ProductResponse getProduct(@PathVariable String productId) {
        return productService.getProductRaw(productId);
    }

    @GetMapping("/loading-factors/current")
    public List<LoadingFactorResponse> getCurrentLoadingFactors() {
        return rateVersionService.getCurrentLoadingFactors();
    }
}
