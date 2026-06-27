package dpp.product;

import dpp.common.config.CommonAutoConfiguration;
import dpp.product.controller.InternalProductController;
import dpp.product.dto.LoadingFactorResponse;
import dpp.product.dto.ProductResponse;
import dpp.product.service.ProductService;
import dpp.product.service.RateVersionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that /internal/** endpoints serialize JSON with snake_case keys
 * (matching the global Jackson SNAKE_CASE strategy in CommonAutoConfiguration).
 *
 * This test uses MockMvc to perform real HTTP serialization, catching any
 * mismatch between the wire format and what the pricing service expects.
 */
@WebMvcTest(InternalProductController.class)
@Import(CommonAutoConfiguration.class)
@AutoConfigureMockMvc(addFilters = false)
class InternalProductWireFormatTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    ProductService productService;

    @MockBean
    RateVersionService rateVersionService;

    @Test
    void productsEndpointSerializesSnakeCase() throws Exception {
        when(productService.listAllProducts()).thenReturn(List.of(
            ProductResponse.builder()
                .productId("HEALTH_BASIC")
                .category("health")
                .productName("Health Basic")
                .coverageAmountVnd(100_000_000L)
                .deductibleVnd(0L)
                .basePremiumVnd(2_200_000L)
                .adminFeeVnd(500_000L)
                .active(true)
                .build()));

        mockMvc.perform(get("/internal/products"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].product_id").value("HEALTH_BASIC"))
            .andExpect(jsonPath("$[0].category").value("health"))
            .andExpect(jsonPath("$[0].product_name").value("Health Basic"))
            .andExpect(jsonPath("$[0].coverage_amount_vnd").value(100000000))
            .andExpect(jsonPath("$[0].deductible_vnd").value(0))
            .andExpect(jsonPath("$[0].base_premium_vnd").value(2200000))
            .andExpect(jsonPath("$[0].admin_fee_vnd").value(500000))
            .andExpect(jsonPath("$[0].active").value(true))
            .andExpect(jsonPath("$[0].productId").doesNotExist())
            .andExpect(jsonPath("$[0].coverageAmountVnd").doesNotExist())
            .andExpect(jsonPath("$[0].adminFeeVnd").doesNotExist());
    }

    @Test
    void loadingFactorsEndpointSerializesSnakeCase() throws Exception {
        UUID rvId = UUID.randomUUID();
        when(rateVersionService.getCurrentLoadingFactors()).thenReturn(List.of(
            LoadingFactorResponse.builder()
                .line("health")
                .loadingValue(1.2)
                .rateVersionId(rvId)
                .build()));

        mockMvc.perform(get("/internal/loading-factors/current"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].line").value("health"))
            .andExpect(jsonPath("$[0].loading_value").value(1.2))
            .andExpect(jsonPath("$[0].rate_version_id").exists())
            .andExpect(jsonPath("$[0].loadingValue").doesNotExist())
            .andExpect(jsonPath("$[0].rateVersionId").doesNotExist());
    }
}
