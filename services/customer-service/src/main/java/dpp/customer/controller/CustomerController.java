package dpp.customer.controller;

import dpp.customer.entity.Account;
import dpp.customer.service.ProfileService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/customers")
public class CustomerController {

    private final ProfileService profileService;

    public CustomerController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping("/me")
    public Map<String, Object> getMe(@AuthenticationPrincipal Jwt jwt) {
        Account account = profileService.ensureAccount(jwt.getSubject(), email(jwt), fullName(jwt));

        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        List<String> roles = realmAccess != null ? (List<String>) realmAccess.get("roles") : List.of();

        Map<String, Object> result = new HashMap<>();
        result.put("accountId", account.getAccountId());
        result.put("email", account.getEmail());
        result.put("fullName", account.getFullName());
        result.put("keycloakSubject", account.getKeycloakSubject());
        result.put("roles", roles);
        return result;
    }

    private String email(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        return email != null ? email : jwt.getClaimAsString("preferred_username");
    }

    /** Display name from Keycloak: the `name` claim, or given+family, as a fallback. */
    private String fullName(Jwt jwt) {
        String name = jwt.getClaimAsString("name");
        if (name != null && !name.isBlank()) {
            return name;
        }
        String given = jwt.getClaimAsString("given_name");
        String family = jwt.getClaimAsString("family_name");
        String combined = ((given != null ? given : "") + " " + (family != null ? family : "")).trim();
        return combined.isEmpty() ? null : combined;
    }
}
