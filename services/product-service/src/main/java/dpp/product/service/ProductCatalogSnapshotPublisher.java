package dpp.product.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ProductCatalogSnapshotPublisher {

    private final ProductService productService;

    public ProductCatalogSnapshotPublisher(ProductService productService) {
        this.productService = productService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void publishSnapshot() {
        int count = productService.publishProductCatalogSnapshot();
        log.info("Published ProductUpdated snapshot events for {} products", count);
    }
}
