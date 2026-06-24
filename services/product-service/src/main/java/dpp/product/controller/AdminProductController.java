package dpp.product.controller;

import dpp.product.dto.RateVersionResponse;
import dpp.product.entity.LoadingFactor;
import dpp.product.entity.Product;
import dpp.product.entity.RateVersion;
import dpp.product.repository.ProductRepository;
import dpp.product.service.ProductService;
import dpp.product.service.RateVersionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
            @AuthenticationPrincipal Jwt jwt, @RequestBody Map<String, Object> body) {
        // R32.2/R32.4: a rate change creates a NEW append-only rate version; the
        // loading factor attaches to that new version (no editing an existing one).
        String line = (String) body.get("line");
        Double loadingValue = ((Number) body.get("loading_value")).doubleValue();
        String createdBy = jwt != null ? jwt.getSubject() : "admin";
        LoadingFactor lf = rateVersionService.addLoadingFactorAsNewVersion(line, loadingValue, createdBy);
        return ResponseEntity.ok(lf);
    }

    @PostMapping("/rate-versions")
    public ResponseEntity<RateVersionResponse> createRateVersion(@AuthenticationPrincipal Jwt jwt) {
        String createdBy = jwt != null ? jwt.getSubject() : "admin";
        RateVersion rv = rateVersionService.createNewRateVersion(createdBy);
        RateVersionResponse resp = RateVersionResponse.builder()
                .rateVersionId(rv.getRateVersionId())
                .effectiveAt(rv.getEffectiveAt())
                .createdBy(rv.getCreatedBy())
                .isCurrent(rv.getIsCurrent())
                .createdAt(rv.getCreatedAt())
                .build();
        return ResponseEntity.status(201).body(resp);
    }

    @GetMapping("/rate-versions")
    public List<RateVersionResponse> listRateVersions() {
        return rateVersionService.listRateVersions();
    }
}


