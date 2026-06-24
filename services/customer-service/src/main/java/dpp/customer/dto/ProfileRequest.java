package dpp.customer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.util.Map;

@Data
public class ProfileRequest {
    private int age;

    @NotBlank
    private String gender;

    @NotBlank
    private String province;

    @NotBlank
    private String region;

    @NotBlank
    private String urbanTier;

    @NotBlank
    private String occupation;

    @NotBlank
    private String incomeLevel;

    private long monthlyIncomeVnd;

    @NotBlank
    private String maritalStatus;

    @NotBlank
    private String line;

    @NotNull
    private Map<String, Object> lineAttributes;
}
