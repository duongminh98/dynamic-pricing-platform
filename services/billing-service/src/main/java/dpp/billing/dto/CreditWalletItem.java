package dpp.billing.dto;

import dpp.billing.entity.CreditStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class CreditWalletItem {
    private UUID creditId;
    private UUID policyId;
    private UUID sourceEndorsementId;
    private long originalAmountVnd;
    private long remainingAmountVnd;
    private CreditStatus status;
    private OffsetDateTime createdAt;
    private List<CreditApplicationView> applications;
}
