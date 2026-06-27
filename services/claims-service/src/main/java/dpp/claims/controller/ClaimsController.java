package dpp.claims.controller;

import dpp.claims.dto.*;
import dpp.claims.entity.ClaimStatus;
import dpp.claims.service.ClaimsService;
import dpp.common.dto.PageResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/claims")
public class ClaimsController {

    private final ClaimsService claimsService;

    public ClaimsController(ClaimsService claimsService) {
        this.claimsService = claimsService;
    }

    @PostMapping("/fnol")
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimResponse fnol(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody FnolRequest request) {
        return claimsService.fnol(jwt.getSubject(), request);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimResponse submitClaim(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody FnolRequest request) {
        return claimsService.fnol(jwt.getSubject(), request);
    }

    @GetMapping
    public PageResponse<ClaimResponse> myClaims(@AuthenticationPrincipal Jwt jwt,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        return claimsService.myClaims(jwt.getSubject(), page, size);
    }

    @GetMapping("/{id}")
    public ClaimResponse getClaim(@AuthenticationPrincipal Jwt jwt, Authentication authentication,
                                  @PathVariable UUID id) {
        boolean isAdmin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_Administrator"::equals);
        return claimsService.getClaim(jwt.getSubject(), id, isAdmin);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('Administrator')")
    public ClaimResponse approve(@PathVariable UUID id, @Valid @RequestBody ApproveClaimRequest request) {
        return claimsService.approve(id, request);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('Administrator')")
    public ClaimResponse reject(@PathVariable UUID id, @Valid @RequestBody RejectClaimRequest request) {
        return claimsService.reject(id, request);
    }

    @PostMapping("/{id}/misrepresentation")
    @PreAuthorize("hasRole('Administrator')")
    public ClaimResponse misrepresentation(@PathVariable UUID id, @Valid @RequestBody MisrepresentationRequest request) {
        return claimsService.misrepresentation(id, request);
    }

    @PostMapping("/{id}/sanction")
    @PreAuthorize("hasRole('Administrator')")
    public ClaimResponse sanction(@PathVariable UUID id, @Valid @RequestBody MisrepresentationRequest request) {
        return claimsService.misrepresentation(id, request);
    }
}