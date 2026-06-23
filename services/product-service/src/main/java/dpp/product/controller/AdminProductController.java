package dpp.product.controller;

import dpp.product.dto.RateVersionResponse;
import dpp.product.entity.LoadingFactor;
import dpp.product.entity.Product;
import dpp.product.repository.ProductRepository;
import dpp.product.service.ProductService;
import dpp.product.service.RateVersionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminProductController {

    private final ProductRepository productRepository;
    private final ProductService productService;
    private final RateVersionService rateVersionService;

    @PostMapping("/products")
    public ResponseEntity<Product> createProduct(@RequestBody Product product) {
        productService.validateLine(product.getCategory());
        return ResponseEntity.ok(productRepository.save(product));
    }

    @PutMapping("/products")
    public ResponseEntity<Product> updateProduct(@RequestBody Product product) {
        productService.validateLine(product.getCategory());
        return ResponseEntity.ok(productRepository.save(product));
    }

    @PutMapping("/loading-factors")
    public ResponseEntity<LoadingFactor> updateLoadingFactor(
            @RequestBody Map<String, Object> body) {
        UUID rateVersionId = UUID.fromString((String) body.get("rate_version_id"));
        String line = (String) body.get("line");
        Double loadingValue = ((Number) body.get("loading_value")).doubleValue();
        LoadingFactor lf = rateVersionService.addLoadingFactor(rateVersionId, line, loadingValue);
        return ResponseEntity.ok(lf);
    }

    @GetMapping("/rate-versions")
    public List<RateVersionResponse> listRateVersions() {
        return rateVersionService.listRateVersions();
    }
}


