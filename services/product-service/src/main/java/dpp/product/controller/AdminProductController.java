package dpp.product.controller;

import dpp.product.dto.LoadingFactorRequest;
import dpp.product.dto.LoadingFactorResponse;
import dpp.product.dto.ProductRequest;
import dpp.product.dto.ProductResponse;
import dpp.product.dto.RateVersionResponse;
import dpp.product.entity.LoadingFactor;
import dpp.product.entity.RateVersion;
import dpp.product.service.ProductService;
import dpp.product.service.RateVersionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminProductController {

    private final ProductService productService;
    private final RateVersionService rateVersionService;

    @PostMapping("/products")
    public ResponseEntity<ProductResponse> createProduct(@Valid @RequestBody ProductRequest request) {
        return ResponseEntity.status(201).body(productService.saveProduct(request));
    }

    @PutMapping("/products")
    public ResponseEntity<ProductResponse> updateProduct(@Valid @RequestBody ProductRequest request) {
        return ResponseEntity.ok(productService.saveProduct(request));
    }

    @PutMapping("/loading-factors")
    public ResponseEntity<LoadingFactorResponse> updateLoadingFactor(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody LoadingFactorRequest request) {
        // R32.2/R32.4: a rate change creates a NEW append-only rate version; the
        // loading factor attaches to that new version (no editing an existing one).
        String createdBy = jwt != null ? jwt.getSubject() : "admin";
        LoadingFactor lf = rateVersionService.addLoadingFactorAsNewVersion(
                request.getLine(), request.getLoadingValue(), createdBy);
        return ResponseEntity.ok(toLoadingFactorResponse(lf));
    }

    @PostMapping("/rate-versions")
    public ResponseEntity<RateVersionResponse> createRateVersion(@AuthenticationPrincipal Jwt jwt) {
        String createdBy = jwt != null ? jwt.getSubject() : "admin";
        RateVersion rv = rateVersionService.createNewRateVersion(createdBy);
        return ResponseEntity.status(201).body(toRateVersionResponse(rv));
    }

    @GetMapping("/rate-versions")
    public List<RateVersionResponse> listRateVersions() {
        return rateVersionService.listRateVersions();
    }

    private LoadingFactorResponse toLoadingFactorResponse(LoadingFactor lf) {
        return LoadingFactorResponse.builder()
                .loadingFactorId(lf.getLoadingFactorId())
                .rateVersionId(lf.getRateVersionId())
                .line(lf.getLine())
                .loadingValue(lf.getLoadingValue())
                .build();
    }

    private RateVersionResponse toRateVersionResponse(RateVersion rv) {
        return RateVersionResponse.builder()
                .rateVersionId(rv.getRateVersionId())
                .effectiveAt(rv.getEffectiveAt())
                .createdBy(rv.getCreatedBy())
                .isCurrent(rv.getIsCurrent())
                .createdAt(rv.getCreatedAt())
                .build();
    }
}
