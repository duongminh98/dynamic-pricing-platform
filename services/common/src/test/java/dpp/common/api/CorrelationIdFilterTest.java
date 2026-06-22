package dpp.common.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Test: CorrelationIdFilter reads/generates X-Correlation-Id, places in MDC,
 * and echoes it in response header.
 *
 * Feature: dynamic-pricing-platform
 * Validates: R19.5
 */
class CorrelationIdFilterTest {

    private CorrelationIdFilter filter;

    @BeforeEach
    void setUp() {
        filter = new CorrelationIdFilter();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void shouldGenerateCorrelationIdWhenMissing() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        String cid = response.getHeader(CorrelationIdFilter.HEADER_NAME);
        assertNotNull(cid, "Correlation ID should be generated");
        assertDoesNotThrow(() -> UUID.fromString(cid), "Should be valid UUID");
    }

    @Test
    void shouldUseExistingCorrelationIdWhenPresent() throws ServletException, IOException {
        String existingCid = UUID.randomUUID().toString();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER_NAME, existingCid);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertEquals(existingCid, response.getHeader(CorrelationIdFilter.HEADER_NAME));
    }

    @Test
    void shouldPlaceCorrelationIdInMdc() throws ServletException, IOException {
        String existingCid = UUID.randomUUID().toString();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER_NAME, existingCid);
        MockHttpServletResponse response = new MockHttpServletResponse();

        // Use a filter chain that checks MDC during execution
        filter.doFilterInternal(request, response, (req, res) -> {
            assertEquals(existingCid, MDC.get(CorrelationIdFilter.MDC_KEY));
        });

        // After filter, MDC should be cleaned up
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    @Test
    void shouldNotPropagateBlankCorrelationId() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        String cid = response.getHeader(CorrelationIdFilter.HEADER_NAME);
        assertNotNull(cid, "Should generate new ID for blank value");
        assertDoesNotThrow(() -> UUID.fromString(cid));
    }
}
