package dpp.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import dpp.common.outbox.OutboxPublisher;
import dpp.order.consumer.InvoiceCreatedListener;
import dpp.order.consumer.QuoteCreatedListener;
import dpp.order.consumer.RepriceCompletedListener;
import dpp.order.entity.EndorsementRequestEntity;
import dpp.order.entity.EndorsementStatus;
import dpp.order.entity.OrderEntity;
import dpp.order.entity.OrderStatus;
import dpp.order.entity.Policy;
import dpp.order.entity.QuoteSnapshot;
import dpp.order.repository.EndorsementRequestRepository;
import dpp.order.repository.OrderRepository;
import dpp.order.repository.PolicyRepository;
import dpp.order.repository.QuoteSnapshotRepository;
import dpp.order.service.InvoiceReconciliationJob;
import dpp.order.service.PolicyLifecycleService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the order-service RabbitMQ consumers and the scheduled invoice
 * reconciliation job. All use mocked repositories / services — no broker, no DB.
 */
@Tag("Feature: dynamic-pricing-platform")
class OrderConsumersAndReconciliationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── QuoteCreatedListener ──

    @Test
    void quoteCreatedUpsertsSnapshot() {
        QuoteSnapshotRepoStub repo = new QuoteSnapshotRepoStub();
        QuoteCreatedListener listener = new QuoteCreatedListener(repo, objectMapper);

        UUID quoteId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        String msg = """
            {"quote_id":"%s","customer_id":"%s","product_id":"HEALTH_BASIC","line":"health",
             "trip_duration_days":null,"coverage_amount_vnd":100000000,"deductible_vnd":0,
             "profile":{"age":30},"final_premium_vnd":298000,
             "expires_at":"2026-07-10T00:00:00+00:00","created_at":"2026-07-03T00:00:00+00:00"}
            """.formatted(quoteId, customerId);

        listener.onQuoteCreated(msg);

        QuoteSnapshot saved = repo.saved;
        assertNotNull(saved);
        assertEquals(quoteId, saved.getQuoteId());
        assertEquals(customerId, saved.getCustomerId());
        assertEquals("HEALTH_BASIC", saved.getProductId());
        assertEquals(298000L, saved.getFinalPremiumVnd());
        assertNotNull(saved.getProfile());
    }

    @Test
    void quoteCreatedWrapsErrors() {
        QuoteSnapshotRepoStub repo = new QuoteSnapshotRepoStub();
        QuoteCreatedListener listener = new QuoteCreatedListener(repo, objectMapper);
        assertThrows(RuntimeException.class, () -> listener.onQuoteCreated("not-json"));
    }

    // ── InvoiceCreatedListener ──

    @Test
    void invoiceCreatedSetsInvoiceIdOnOrder() {
        OrderRepository orderRepo = mock(OrderRepository.class);
        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        InvoiceCreatedListener listener = new InvoiceCreatedListener(orderRepo, endRepo);

        UUID orderId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        OrderEntity order = new OrderEntity();
        order.setOrderId(orderId);
        when(orderRepo.findById(orderId)).thenReturn(Optional.of(order));

        listener.onInvoiceCreated("""
            {"invoice_id":"%s","order_id":"%s"}
            """.formatted(invoiceId, orderId), "evt-1");

        assertEquals(invoiceId, order.getInvoiceId());
        verify(orderRepo).save(order);
    }

    @Test
    void invoiceCreatedIgnoresOrderThatAlreadyHasInvoice() {
        OrderRepository orderRepo = mock(OrderRepository.class);
        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        InvoiceCreatedListener listener = new InvoiceCreatedListener(orderRepo, endRepo);

        UUID orderId = UUID.randomUUID();
        OrderEntity order = new OrderEntity();
        order.setOrderId(orderId);
        order.setInvoiceId(UUID.randomUUID());
        when(orderRepo.findById(orderId)).thenReturn(Optional.of(order));

        listener.onInvoiceCreated("""
            {"invoice_id":"%s","order_id":"%s"}
            """.formatted(UUID.randomUUID(), orderId), "evt-2");

        verify(orderRepo, never()).save(any());
    }

    @Test
    void invoiceCreatedSetsInvoiceIdOnEndorsement() {
        OrderRepository orderRepo = mock(OrderRepository.class);
        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        InvoiceCreatedListener listener = new InvoiceCreatedListener(orderRepo, endRepo);

        UUID endorsementId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        EndorsementRequestEntity req = new EndorsementRequestEntity();
        req.setEndorsementRequestId(endorsementId);
        when(endRepo.findById(endorsementId)).thenReturn(Optional.of(req));

        listener.onInvoiceCreated("""
            {"invoice_id":"%s","endorsement_request_id":"%s"}
            """.formatted(invoiceId, endorsementId), "evt-3");

        assertEquals(invoiceId, req.getInvoiceId());
        verify(endRepo).save(req);
        verify(orderRepo, never()).findById(any());
    }

    @Test
    void invoiceCreatedWrapsErrors() {
        InvoiceCreatedListener listener = new InvoiceCreatedListener(
                mock(OrderRepository.class), mock(EndorsementRequestRepository.class));
        assertThrows(RuntimeException.class, () -> listener.onInvoiceCreated("bad", "e"));
    }

    // ── RepriceCompletedListener ──

    @Test
    void repriceCompletedDelegatesToLifecycle() {
        PolicyLifecycleService lifecycle = mock(PolicyLifecycleService.class);
        RepriceCompletedListener listener = new RepriceCompletedListener(lifecycle, objectMapper);

        listener.onRepriceCompleted("""
            {"pricing_request_id":"pr-1","workflow":"ENDORSEMENT","final_premium_vnd":350000}
            """);

        verify(lifecycle).handleRepriceCompleted("pr-1", "ENDORSEMENT", 350000L, null);
    }

    @Test
    void repriceCompletedPassesFailureReason() {
        PolicyLifecycleService lifecycle = mock(PolicyLifecycleService.class);
        RepriceCompletedListener listener = new RepriceCompletedListener(lifecycle, objectMapper);

        listener.onRepriceCompleted("""
            {"pricing_request_id":"pr-2","workflow":"RENEWAL","failure_reason":"missing champion"}
            """);

        verify(lifecycle).handleRepriceCompleted("pr-2", "RENEWAL", null, "missing champion");
    }

    @Test
    void repriceCompletedWrapsErrors() {
        RepriceCompletedListener listener = new RepriceCompletedListener(
                mock(PolicyLifecycleService.class), objectMapper);
        assertThrows(RuntimeException.class, () -> listener.onRepriceCompleted("bad"));
    }

    // ── InvoiceReconciliationJob ──

    @Test
    void reconcileReEnqueuesStaleOrdersAndEndorsements() throws Exception {
        OrderRepository orderRepo = mock(OrderRepository.class);
        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        PolicyRepository policyRepo = mock(PolicyRepository.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);

        OrderEntity order = new OrderEntity();
        order.setOrderId(UUID.randomUUID());
        order.setCustomerId(UUID.randomUUID());
        order.setProductId("HEALTH_BASIC");
        order.setLine("health");
        order.setFinalPremiumVnd(298000L);
        order.setStatus(OrderStatus.PENDING_PAYMENT);

        UUID policyId = UUID.randomUUID();
        EndorsementRequestEntity req = new EndorsementRequestEntity();
        req.setEndorsementRequestId(UUID.randomUUID());
        req.setCustomerId(UUID.randomUUID());
        req.setPolicyId(policyId);
        req.setQuotedPremiumVnd(75000L);
        req.setDueDate(OffsetDateTime.now().plusDays(3));

        Policy policy = new Policy();
        policy.setOrderId(UUID.randomUUID());

        when(orderRepo.findStaleWithoutInvoice(eq(OrderStatus.PENDING_PAYMENT), any()))
                .thenReturn(List.of(order));
        when(endRepo.findStaleWithoutInvoice(eq(EndorsementStatus.APPROVED_PENDING_PAYMENT), any()))
                .thenReturn(List.of(req));
        when(policyRepo.findById(policyId)).thenReturn(Optional.of(policy));

        InvoiceReconciliationJob job = new InvoiceReconciliationJob(orderRepo, endRepo, policyRepo, outbox);
        ReflectionTestUtils.setField(job, "staleMinutes", 15);

        job.reconcileStaleInvoices();

        ArgumentCaptor<String> types = ArgumentCaptor.forClass(String.class);
        verify(outbox, times(2)).enqueue(types.capture(), anyString());
        assertTrue(types.getAllValues().contains("OrderApproved"));
        assertTrue(types.getAllValues().contains("EndorsementPendingPayment"));
    }

    @Test
    void reconcileNoStaleEntitiesPublishesNothing() {
        OrderRepository orderRepo = mock(OrderRepository.class);
        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        PolicyRepository policyRepo = mock(PolicyRepository.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);

        when(orderRepo.findStaleWithoutInvoice(any(), any())).thenReturn(List.of());
        when(endRepo.findStaleWithoutInvoice(any(), any())).thenReturn(List.of());

        InvoiceReconciliationJob job = new InvoiceReconciliationJob(orderRepo, endRepo, policyRepo, outbox);
        ReflectionTestUtils.setField(job, "staleMinutes", 15);

        job.reconcileStaleInvoices();

        verifyNoInteractions(outbox);
    }

    // Minimal in-memory stub for QuoteSnapshotRepository (findById + save only).
    private static class QuoteSnapshotRepoStub implements dpp.order.repository.QuoteSnapshotRepository {
        QuoteSnapshot saved;

        @Override
        public <S extends QuoteSnapshot> S save(S entity) {
            this.saved = entity;
            return entity;
        }

        @Override
        public Optional<QuoteSnapshot> findById(UUID uuid) {
            return Optional.empty();
        }

        // ── unused JpaRepository surface ──
        @Override public <S extends QuoteSnapshot> List<S> saveAll(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public List<QuoteSnapshot> findAll() { throw new UnsupportedOperationException(); }
        @Override public List<QuoteSnapshot> findAllById(Iterable<UUID> uuids) { throw new UnsupportedOperationException(); }
        @Override public List<QuoteSnapshot> findAll(org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
        @Override public org.springframework.data.domain.Page<QuoteSnapshot> findAll(org.springframework.data.domain.Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public boolean existsById(UUID uuid) { throw new UnsupportedOperationException(); }
        @Override public long count() { throw new UnsupportedOperationException(); }
        @Override public void deleteById(UUID uuid) { throw new UnsupportedOperationException(); }
        @Override public void delete(QuoteSnapshot entity) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllById(Iterable<? extends UUID> uuids) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll(Iterable<? extends QuoteSnapshot> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll() { throw new UnsupportedOperationException(); }
        @Override public void flush() { throw new UnsupportedOperationException(); }
        @Override public <S extends QuoteSnapshot> S saveAndFlush(S entity) { throw new UnsupportedOperationException(); }
        @Override public <S extends QuoteSnapshot> List<S> saveAllAndFlush(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch(Iterable<QuoteSnapshot> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllByIdInBatch(Iterable<UUID> uuids) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch() { throw new UnsupportedOperationException(); }
        @Override public QuoteSnapshot getOne(UUID uuid) { throw new UnsupportedOperationException(); }
        @Override public QuoteSnapshot getById(UUID uuid) { throw new UnsupportedOperationException(); }
        @Override public QuoteSnapshot getReferenceById(UUID uuid) { throw new UnsupportedOperationException(); }
        @Override public <S extends QuoteSnapshot> Optional<S> findOne(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends QuoteSnapshot> List<S> findAll(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends QuoteSnapshot> List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
        @Override public <S extends QuoteSnapshot> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public <S extends QuoteSnapshot> long count(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends QuoteSnapshot> boolean exists(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends QuoteSnapshot, R> R findBy(org.springframework.data.domain.Example<S> example, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { throw new UnsupportedOperationException(); }
    }
}
