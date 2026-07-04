package dpp.order;

import dpp.order.controller.AdminEndorsementController;
import dpp.order.controller.AdminOrderController;
import dpp.order.controller.AdminPolicyController;
import dpp.order.controller.OrderController;
import dpp.order.dto.CancelRequest;
import dpp.order.dto.CancelResponse;
import dpp.order.dto.CreateOrderRequest;
import dpp.order.dto.EndorsementRequestResponse;
import dpp.order.dto.ExtendDueDateRequest;
import dpp.order.dto.OrderResponse;
import dpp.order.dto.PageResponse;
import dpp.order.dto.PolicyDetailResponse;
import dpp.order.dto.PolicyResponse;
import dpp.order.dto.RejectRequest;
import dpp.order.dto.ReviewQueueItem;
import dpp.order.entity.EndorsementStatus;
import dpp.order.entity.OrderStatus;
import dpp.order.entity.PolicyStatus;
import dpp.order.service.OrderService;
import dpp.order.service.PolicyLifecycleService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Delegation tests for the thin order-service controllers. Each verifies the
 * controller forwards to the right service method with the right arguments and
 * returns the service result. Uses mocked services — no web layer, no DB.
 */
@Tag("Feature: dynamic-pricing-platform")
class OrderControllersDelegationTest {

    private Jwt jwt(String subject) {
        return Jwt.withTokenValue("t").header("alg", "none").subject(subject).build();
    }

    // ── OrderController ──

    @Test
    void orderControllerDelegates() {
        OrderService service = mock(OrderService.class);
        OrderController controller = new OrderController(service);
        Jwt jwt = jwt("customer-1");
        UUID orderId = UUID.randomUUID();

        CreateOrderRequest createReq = new CreateOrderRequest();
        OrderResponse resp = new OrderResponse();
        when(service.createOrder(eq("customer-1"), any())).thenReturn(resp);
        when(service.myOrders("customer-1")).thenReturn(List.of(resp));
        when(service.getMyOrder("customer-1", orderId)).thenReturn(resp);

        assertSame(resp, controller.createOrder(jwt, createReq));
        assertEquals(1, controller.myOrders(jwt).size());
        assertSame(resp, controller.getMyOrder(jwt, orderId));

        verify(service).createOrder("customer-1", createReq);
        verify(service).myOrders("customer-1");
        verify(service).getMyOrder("customer-1", orderId);
    }

    // ── AdminOrderController ──

    @Test
    void adminOrderControllerDelegates() {
        OrderService service = mock(OrderService.class);
        AdminOrderController controller = new AdminOrderController(service);
        Jwt jwt = jwt("admin-1");
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        Page<ReviewQueueItem> reviewPage = new PageImpl<>(List.of());
        Page<OrderResponse> ordersPage = new PageImpl<>(List.of());
        OrderResponse resp = new OrderResponse();
        when(service.reviewQueue(0, 20, "health")).thenReturn(reviewPage);
        when(service.adminListOrders(OrderStatus.PENDING_REVIEW, customerId, "health", 0, 20)).thenReturn(ordersPage);
        when(service.getOrder(orderId)).thenReturn(resp);
        when(service.approve(orderId, "admin-1")).thenReturn(resp);
        when(service.reject(eq(orderId), eq("bad"), eq("admin-1"))).thenReturn(resp);

        assertNotNull(controller.reviewQueue(0, 20, "health"));
        assertNotNull(controller.listAll(0, 20, OrderStatus.PENDING_REVIEW, customerId, "health"));
        assertSame(resp, controller.getOrder(orderId));
        assertSame(resp, controller.approve(orderId, jwt));
        RejectRequest reject = new RejectRequest();
        reject.setReason("bad");
        assertSame(resp, controller.reject(orderId, reject, jwt));

        verify(service).approve(orderId, "admin-1");
        verify(service).reject(orderId, "bad", "admin-1");
    }

    // ── AdminPolicyController ──

    @Test
    void adminPolicyControllerDelegates() {
        PolicyLifecycleService lifecycle = mock(PolicyLifecycleService.class);
        AdminPolicyController controller = new AdminPolicyController(lifecycle);
        UUID policyId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        PageResponse<PolicyResponse> page = new PageResponse<>();
        PolicyDetailResponse detail = new PolicyDetailResponse();
        CancelResponse cancel = new CancelResponse();
        when(lifecycle.adminListPoliciesPaged(eq(PolicyStatus.active), eq(customerId), eq("health"), any())).thenReturn(page);
        when(lifecycle.adminGetPolicyDetail(policyId)).thenReturn(detail);
        when(lifecycle.adminCancelPolicy(eq(policyId), any())).thenReturn(cancel);

        assertSame(page, controller.listAll(PolicyStatus.active, customerId, "health", 0, 20));
        assertSame(detail, controller.getPolicyDetail(policyId));
        assertSame(cancel, controller.cancelPolicy(policyId, null));

        CancelRequest req = new CancelRequest();
        controller.cancelPolicy(policyId, req);
        verify(lifecycle, times(2)).adminCancelPolicy(eq(policyId), any());
    }

    // ── AdminEndorsementController ──

    @Test
    void adminEndorsementControllerDelegates() {
        PolicyLifecycleService lifecycle = mock(PolicyLifecycleService.class);
        AdminEndorsementController controller = new AdminEndorsementController(lifecycle);
        Jwt jwt = jwt("admin-2");
        UUID endorsementId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();

        PageResponse<EndorsementRequestResponse> page = new PageResponse<>();
        EndorsementRequestResponse resp = new EndorsementRequestResponse();
        PolicyResponse policyResp = new PolicyResponse();
        when(lifecycle.adminEndorsementQueuePaged(eq(EndorsementStatus.PENDING_REVIEW), eq(customerId), eq(policyId), any())).thenReturn(page);
        when(lifecycle.endorsementReviewQueue()).thenReturn(List.of(resp));
        when(lifecycle.pendingPaymentQueue()).thenReturn(List.of(resp));
        when(lifecycle.voidedEndorsements()).thenReturn(List.of(resp));
        when(lifecycle.adminGetEndorsementDetail(endorsementId)).thenReturn(resp);
        when(lifecycle.approveEndorsement(endorsementId, "admin-2")).thenReturn(resp);
        when(lifecycle.rejectEndorsement(eq(endorsementId), eq("no"), eq("admin-2"))).thenReturn(resp);
        when(lifecycle.extendDueDate(endorsementId, 5)).thenReturn(resp);
        when(lifecycle.cancelPolicyFromEndorsement(endorsementId, "admin-2")).thenReturn(policyResp);

        assertSame(page, controller.listEndorsements(EndorsementStatus.PENDING_REVIEW, customerId, policyId, 0, 20));
        assertEquals(1, controller.reviewQueue().size());
        assertEquals(1, controller.pendingPaymentQueue().size());
        assertEquals(1, controller.voidedEndorsements().size());
        assertSame(resp, controller.getDetail(endorsementId));
        assertSame(resp, controller.approve(endorsementId, jwt));

        RejectRequest reject = new RejectRequest();
        reject.setReason("no");
        assertSame(resp, controller.reject(endorsementId, reject, jwt));

        ExtendDueDateRequest extend = new ExtendDueDateRequest();
        extend.setExtraDays(5);
        assertSame(resp, controller.extendDueDate(endorsementId, extend));

        assertSame(policyResp, controller.cancelPolicyFromEndorsement(endorsementId, jwt));

        verify(lifecycle).approveEndorsement(endorsementId, "admin-2");
        verify(lifecycle).rejectEndorsement(endorsementId, "no", "admin-2");
    }
}
