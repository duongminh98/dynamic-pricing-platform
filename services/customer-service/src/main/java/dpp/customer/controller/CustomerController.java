package dpp.customer.controller;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.customer.entity.Account;
import dpp.customer.repository.AccountRepository;
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

    private final AccountRepository accountRepository;

    public CustomerController(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @GetMapping("/me")
    public Map<String, Object> getMe(@AuthenticationPrincipal Jwt jwt) {
        Account account = accountRepository.findByKeycloakSubject(jwt.getSubject())
                .orElseThrow(() -> new ServiceException(ErrorCode.UNAUTHENTICATED, "Account not found for subject", null));

        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        List<String> roles = realmAccess != null ? (List<String>) realmAccess.get("roles") : List.of();

        Map<String, Object> result = new HashMap<>();
        result.put("accountId", account.getAccountId());
        result.put("email", account.getEmail());
        result.put("keycloakSubject", account.getKeycloakSubject());
        result.put("roles", roles);
        return result;
    }
}

