package dpp.product.controller;

import dpp.product.dto.CoverageOptionResponse;
import dpp.product.dto.ProductDetail;
import dpp.product.dto.ProductSummary;
import dpp.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping
    public List<ProductSummary> listProducts(
            @RequestParam(value = "line", required = false) String line) {
        return productService.listActiveProducts(line);
    }

    @GetMapping("/{productId}")
    public ResponseEntity<ProductDetail> getProduct(@PathVariable String productId) {
        return ResponseEntity.ok(productService.getProduct(productId));
    }

    @GetMapping("/lines/{line}/coverage-options")
    public List<CoverageOptionResponse> getCoverageOptions(@PathVariable String line) {
        return productService.getCoverageOptions(line);
    }
}


