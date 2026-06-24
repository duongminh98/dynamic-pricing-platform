package dpp.customer.controller;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.customer.entity.Account;
import dpp.customer.repository.AccountRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

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
    
    @GetMapping("/{id}")
    public Map<String, Object> getCustomer(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Account not found", null));
                
        if (!account.getKeycloakSubject().equals(jwt.getSubject())) {
             throw new ServiceException(ErrorCode.FORBIDDEN_RESOURCE, "Cannot access other customer's data", null);
        }
        
        return Map.of(
            "accountId", account.getAccountId(),
            "email", account.getEmail()
        );
    }
}

