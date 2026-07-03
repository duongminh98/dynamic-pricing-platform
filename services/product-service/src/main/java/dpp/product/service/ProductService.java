package dpp.product.service;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.common.outbox.OutboxPublisher;
import dpp.product.dto.CoverageOptionResponse;
import dpp.product.dto.ProductDetail;
import dpp.product.dto.ProductRequest;
import dpp.product.dto.ProductResponse;
import dpp.product.dto.ProductSummary;
import dpp.product.entity.CoverageOption;
import dpp.product.entity.Product;
import dpp.product.repository.CoverageOptionRepository;
import dpp.product.repository.ProductRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
@Slf4j
public class ProductService {

    public ProductService(ProductRepository productRepository,
                          CoverageOptionRepository coverageOptionRepository) {
        this.productRepository = productRepository;
        this.coverageOptionRepository = coverageOptionRepository;
    }

    private static final Set<String> VALID_LINES = Set.of(
            "health", "motorbike", "car", "home", "accident", "travel"
    );

    private final ProductRepository productRepository;
    private final CoverageOptionRepository coverageOptionRepository;
    private OutboxPublisher outboxPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

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

    @Transactional
    public int publishProductCatalogSnapshot() {
        if (outboxPublisher == null) {
            return 0;
        }
        List<Product> products = productRepository.findAll();
        products.forEach(this::enqueueProductUpdated);
        return products.size();
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
        enqueueProductUpdated(saved);
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
                .basePremiumVnd(p.getBasePremiumVnd())
                .adminFeeVnd(p.getAdminFeeVnd())
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

    @Autowired(required = false)
    public void setOutboxPublisher(OutboxPublisher outboxPublisher) {
        this.outboxPublisher = outboxPublisher;
    }

    private void enqueueProductUpdated(Product product) {
        if (outboxPublisher == null) {
            return;
        }
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_id", eventId);
        payload.put("event_type", "ProductUpdated");
        payload.put("schema_version", 1);
        payload.put("producer", "product-service");
        payload.put("product_id", product.getProductId());
        payload.put("category", product.getCategory());
        payload.put("product_name", product.getProductName());
        payload.put("coverage_amount_vnd", product.getCoverageAmountVnd());
        payload.put("deductible_vnd", product.getDeductibleVnd());
        payload.put("base_premium_vnd", product.getBasePremiumVnd());
        payload.put("admin_fee_vnd", product.getAdminFeeVnd());
        payload.put("active", Boolean.TRUE.equals(product.getActive()));
        payload.put("occurred_at", OffsetDateTime.now().toString());
        try {
            outboxPublisher.enqueue(eventId, "ProductUpdated", objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new RuntimeException("Failed to enqueue ProductUpdated", e);
        }
    }
}


