package dpp.customer.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.util.Map;

@Data
public class ProfileRequest {
    @Min(18)
    @Max(100)
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

    @Min(0)
    @Max(999999999999L)
    private long monthlyIncomeVnd;

    @NotBlank
    private String maritalStatus;

    @NotBlank
    private String line;

    @NotNull
    private Map<String, Object> lineAttributes;
}
