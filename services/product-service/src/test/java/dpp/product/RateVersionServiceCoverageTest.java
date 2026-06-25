package dpp.product;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.product.entity.LoadingFactor;
import dpp.product.entity.RateVersion;
import dpp.product.repository.LoadingFactorRepository;
import dpp.product.repository.RateVersionRepository;
import dpp.product.service.RateVersionService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RateVersionServiceCoverageTest {

    @Test
    void createNewRateVersionRetiresPreviousAndCreatesNew() {
        RateVersionRepository rvRepo = mock(RateVersionRepository.class);
        LoadingFactorRepository lfRepo = mock(LoadingFactorRepository.class);

        RateVersion oldCurrent = RateVersion.builder()
                .rateVersionId(UUID.randomUUID())
                .effectiveAt(Instant.now().minusSeconds(3600))
                .createdBy("admin")
                .isCurrent(true)
                .createdAt(Instant.now().minusSeconds(3600))
                .build();
        when(rvRepo.findByIsCurrentTrue()).thenReturn(Optional.of(oldCurrent));
        when(rvRepo.saveAndFlush(oldCurrent)).thenReturn(oldCurrent);
        when(rvRepo.save(any(RateVersion.class))).thenAnswer(inv -> inv.getArgument(0));

        RateVersionService svc = new RateVersionService(rvRepo, lfRepo);
        RateVersion result = svc.createNewRateVersion("admin2");

        assertNotNull(result);
        assertTrue(result.getIsCurrent());
        assertEquals("admin2", result.getCreatedBy());
        assertFalse(oldCurrent.getIsCurrent());
        verify(rvRepo, times(1)).saveAndFlush(oldCurrent);
    }

    @Test
    void createNewRateVersionWhenNoPrevious() {
        RateVersionRepository rvRepo = mock(RateVersionRepository.class);
        LoadingFactorRepository lfRepo = mock(LoadingFactorRepository.class);

        when(rvRepo.findByIsCurrentTrue()).thenReturn(Optional.empty());
        when(rvRepo.save(any(RateVersion.class))).thenAnswer(inv -> inv.getArgument(0));

        RateVersionService svc = new RateVersionService(rvRepo, lfRepo);
        RateVersion result = svc.createNewRateVersion("admin");

        assertTrue(result.getIsCurrent());
        verify(rvRepo, never()).saveAndFlush(any());
    }

    @Test
    void listRateVersionsReturnsAllOrdered() {
        RateVersionRepository rvRepo = mock(RateVersionRepository.class);
        LoadingFactorRepository lfRepo = mock(LoadingFactorRepository.class);

        RateVersion rv1 = RateVersion.builder()
                .rateVersionId(UUID.randomUUID())
                .effectiveAt(Instant.now())
                .createdBy("admin")
                .isCurrent(true)
                .createdAt(Instant.now())
                .build();
        when(rvRepo.findAllByOrderByEffectiveAtDesc()).thenReturn(List.of(rv1));

        RateVersionService svc = new RateVersionService(rvRepo, lfRepo);
        var result = svc.listRateVersions();

        assertEquals(1, result.size());
        assertEquals(rv1.getRateVersionId(), result.get(0).getRateVersionId());
        assertTrue(result.get(0).getIsCurrent());
    }

    @Test
    void addLoadingFactorAsNewVersionSucceeds() {
        RateVersionRepository rvRepo = mock(RateVersionRepository.class);
        LoadingFactorRepository lfRepo = mock(LoadingFactorRepository.class);

        when(rvRepo.findByIsCurrentTrue()).thenReturn(Optional.empty());
        when(rvRepo.save(any(RateVersion.class))).thenAnswer(inv -> inv.getArgument(0));
        when(lfRepo.save(any(LoadingFactor.class))).thenAnswer(inv -> inv.getArgument(0));

        RateVersionService svc = new RateVersionService(rvRepo, lfRepo);
        LoadingFactor result = svc.addLoadingFactorAsNewVersion("health", 1.5, "admin");

        assertNotNull(result);
        assertEquals("health", result.getLine());
        assertEquals(1.5, result.getLoadingValue());
        verify(lfRepo, times(1)).save(any());
    }

    @Test
    void addLoadingFactorRejectsInvalidLine() {
        RateVersionRepository rvRepo = mock(RateVersionRepository.class);
        LoadingFactorRepository lfRepo = mock(LoadingFactorRepository.class);

        RateVersionService svc = new RateVersionService(rvRepo, lfRepo);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.addLoadingFactorAsNewVersion("invalid", 1.0, "admin"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        verify(lfRepo, never()).save(any());
    }
}
