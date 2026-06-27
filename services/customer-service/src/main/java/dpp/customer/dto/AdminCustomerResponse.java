package dpp.customer.dto;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class AdminCustomerResponse {
    private UUID customerId;
    private UUID accountId;
    private String email;
    private String keycloakSubject;
    private Integer age;
    private String gender;
    private String province;
    private String region;
    private String urbanTier;
    private String occupation;
    private String incomeLevel;
    private Long monthlyIncomeVnd;
    private String maritalStatus;
    private int failedLoginCount;
    private OffsetDateTime lockedUntil;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
