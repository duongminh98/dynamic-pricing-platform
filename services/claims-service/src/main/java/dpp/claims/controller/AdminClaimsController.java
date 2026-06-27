package dpp.claims.controller;

import dpp.claims.dto.ClaimResponse;
import dpp.claims.entity.ClaimStatus;
import dpp.claims.service.ClaimsService;
import dpp.common.dto.PageResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/admin/claims")
@PreAuthorize("hasRole('Administrator')")
public class AdminClaimsController {

    private final ClaimsService claimsService;

    public AdminClaimsController(ClaimsService claimsService) {
        this.claimsService = claimsService;
    }

    @GetMapping
    public PageResponse<ClaimResponse> listClaims(
            @RequestParam(required = false) ClaimStatus status,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) UUID policyId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return claimsService.adminListClaims(status, customerId, policyId, page, size);
    }

    @GetMapping("/{id}")
    public ClaimResponse getClaim(@PathVariable UUID id) {
        return claimsService.getClaim(null, id, true);
    }
}
