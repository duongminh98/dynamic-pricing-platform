package dpp.product;

import dpp.product.service.RateVersionService;
import dpp.product.repository.RateVersionRepository;
import net.jqwik.api.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class RateVersionPropertyTest {

    @Autowired
    private RateVersionService rateVersionService;

    @Autowired
    private RateVersionRepository rateVersionRepository;

    @Property(tries = 100)
    @Tag("Feature: dynamic-pricing-platform, Property 19")
    public void appendOnlyRateVersionProperty(
            @ForAll("operations") int numOperations) {
        
        long initialCount = rateVersionRepository.count();
        
        for (int i = 0; i < numOperations; i++) {
            rateVersionService.createNewRateVersion("user_" + i);
        }
        
        long finalCount = rateVersionRepository.count();
        assertThat(finalCount).isEqualTo(initialCount + numOperations);
        
        long currentCount = rateVersionRepository.findByIsCurrentTrue().stream().count();
        assertThat(currentCount).isEqualTo(1L);
    }

    @Provide
    Arbitrary<Integer> operations() {
        return Arbitraries.integers().between(1, 10);
    }
}
