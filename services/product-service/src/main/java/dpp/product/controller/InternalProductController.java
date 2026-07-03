package dpp.product.controller;

import dpp.product.dto.CostIndexRowResponse;
import dpp.product.dto.GeoRiskRowResponse;
import dpp.product.dto.LoadingFactorResponse;
import dpp.product.dto.ProductResponse;
import dpp.product.dto.ReferenceDataVersionResponse;
import dpp.product.service.PricingReferenceDataService;
import dpp.product.service.ProductService;
import dpp.product.service.RateVersionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalProductController {

    private final ProductService productService;
    private final RateVersionService rateVersionService;
    private final PricingReferenceDataService referenceDataService;

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

    @GetMapping("/pricing-reference/geo-risk/active")
    public ReferenceDataVersionResponse<GeoRiskRowResponse> getActiveGeoRisk() {
        return referenceDataService.getActiveGeoRisk();
    }

    @GetMapping("/pricing-reference/cost-indices/active")
    public ReferenceDataVersionResponse<CostIndexRowResponse> getActiveCostIndices() {
        return referenceDataService.getActiveCostIndices();
    }
}
