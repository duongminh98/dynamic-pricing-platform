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
        
        return Map.of(
            "accountId", account.getAccountId(),
            "email", account.getEmail(),
            "keycloakSubject", account.getKeycloakSubject()
        );
    }
}

