package dpp.product;

import dpp.product.service.RateVersionService;
import dpp.product.repository.RateVersionRepository;
import dpp.product.repository.LoadingFactorRepository;
import dpp.product.entity.LoadingFactor;
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

    @Autowired
    private LoadingFactorRepository loadingFactorRepository;

    @Property(tries = 100)
    @Tag("Feature: dynamic-pricing-platform, Property 19")
    public void loadingFactorChangeCreatesNewRateVersionAppendOnly(
            @ForAll("operations") int numOperations) {
        // Production path (PUT /admin/loading-factors): each change must append a
        // NEW current rate version, retire the previous, and attach the factor to it.
        long initialVersions = rateVersionRepository.count();
        String[] lines = {"health", "motorbike", "car", "home", "accident", "travel"};
        for (int i = 0; i < numOperations; i++) {
            LoadingFactor lf = rateVersionService.addLoadingFactorAsNewVersion(
                    lines[i % lines.length], 1.0 + i * 0.01, "user_" + i);
            // factor is attached to the now-current version
            assertThat(rateVersionRepository.findByIsCurrentTrue())
                    .isPresent()
                    .get()
                    .extracting(rv -> rv.getRateVersionId())
                    .isEqualTo(lf.getRateVersionId());
        }
        assertThat(rateVersionRepository.count()).isEqualTo(initialVersions + numOperations);
        assertThat(rateVersionRepository.findByIsCurrentTrue().stream().count()).isEqualTo(1L);
    }

    @Provide
    Arbitrary<Integer> operations() {
        return Arbitraries.integers().between(1, 10);
    }
}
