package dpp.product.service;

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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Transactional(readOnly = true)
@Slf4j
@RequiredArgsConstructor
public class ProductService {

    private static final Set<String> VALID_LINES = Set.of(
            "health", "motorbike", "car", "home", "accident", "travel"
    );

    private final ProductRepository productRepository;
    private final CoverageOptionRepository coverageOptionRepository;

    public List<ProductSummary> listActiveProducts(String line) {
        if (line != null && !line.isBlank()) {
            validateLine(line);
            List<Product> products = productRepository.findByCategoryAndActiveTrue(line);
            return products.stream().map(this::toSummary).toList();
        }
        return productRepository.findByActiveTrue().stream().map(this::toSummary).toList();
    }

    public ProductDetail getProduct(String productId) {
        Product product = productRepository.findByProductIdAndActiveTrue(productId)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND,
                        Map.of("product_id", productId, "reason", "not found")));
        return toDetail(product);
    }

    public List<ProductResponse> listAllProducts() {
        return productRepository.findAll().stream()
                .map(ProductService::toProductResponse)
                .toList();
    }

    public ProductResponse getProductRaw(String productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND,
                        Map.of("product_id", productId, "reason", "not found")));
        return toProductResponse(product);
    }

    public List<CoverageOptionResponse> getCoverageOptions(String line) {
        validateLine(line);
        List<Product> products = productRepository.findByCategoryAndActiveTrue(line);
        List<String> productIds = products.stream().map(Product::getProductId).toList();
        return coverageOptionRepository.findAll().stream()
                .filter(co -> productIds.contains(co.getProductId()))
                .map(this::toCoverageOptionResponse)
                .toList();
    }

    @Transactional
    public ProductResponse saveProduct(ProductRequest request) {
        validateLine(request.getCategory());
        Product product = Product.builder()
                .productId(request.getProductId())
                .category(request.getCategory())
                .productName(request.getProductName())
                .coverageAmountVnd(request.getCoverageAmountVnd())
                .deductibleVnd(request.getDeductibleVnd())
                .basePremiumVnd(request.getBasePremiumVnd())
                .adminFeeVnd(request.getAdminFeeVnd())
                .active(request.getActive() == null ? Boolean.TRUE : request.getActive())
                .build();
        Product saved = productRepository.save(product);
        return toProductResponse(saved);
    }

    public static ProductResponse toProductResponse(Product p) {
        return ProductResponse.builder()
                .productId(p.getProductId())
                .category(p.getCategory())
                .productName(p.getProductName())
                .coverageAmountVnd(p.getCoverageAmountVnd())
                .deductibleVnd(p.getDeductibleVnd())
                .basePremiumVnd(p.getBasePremiumVnd())
                .adminFeeVnd(p.getAdminFeeVnd())
                .active(Boolean.TRUE.equals(p.getActive()))
                .build();
    }

    public void validateLine(String line) {
        if (!VALID_LINES.contains(line)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST,
                    Map.of("line", line, "valid_lines", VALID_LINES));
        }
    }

    public static Set<String> getValidLines() {
        return VALID_LINES;
    }

    private ProductSummary toSummary(Product p) {
        return ProductSummary.builder()
                .productId(p.getProductId())
                .line(p.getCategory())
                .productName(p.getProductName())
                .coverageAmountVnd(p.getCoverageAmountVnd())
                .deductibleVnd(p.getDeductibleVnd())
                .build();
    }

    private ProductDetail toDetail(Product p) {
        return ProductDetail.builder()
                .productId(p.getProductId())
                .category(p.getCategory())
                .productName(p.getProductName())
                .coverageAmountVnd(p.getCoverageAmountVnd())
                .deductibleVnd(p.getDeductibleVnd())
                .basePremiumVnd(p.getBasePremiumVnd())
                .adminFeeVnd(p.getAdminFeeVnd())
                .build();
    }

    private CoverageOptionResponse toCoverageOptionResponse(CoverageOption co) {
        return CoverageOptionResponse.builder()
                .coverageAmountVnd(co.getCoverageAmountVnd())
                .deductibleVnd(co.getDeductibleVnd())
                .basePremiumVnd(co.getBasePremiumVnd())
                .adminFeeVnd(co.getAdminFeeVnd())
                .build();
    }
}


