package dpp.customer.dto;

import lombok.Data;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
public class ProfileResponse {
    private UUID customerId;
    private int age;
    private String gender;
    private String province;
    private String region;
    private String urbanTier;
    private String occupation;
    private String incomeLevel;
    private long monthlyIncomeVnd;
    private String maritalStatus;
    private OffsetDateTime updatedAt;

    private List<LineProfileResponse> lines;
}
