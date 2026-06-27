package dpp.customer.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class BaseProfileRequest {
    private Integer age;

    @NotBlank
    private String gender;

    @NotBlank
    private String province;

    @NotBlank
    private String occupation;

    private Long monthlyIncomeVnd;

    @NotBlank
    private String maritalStatus;
}
