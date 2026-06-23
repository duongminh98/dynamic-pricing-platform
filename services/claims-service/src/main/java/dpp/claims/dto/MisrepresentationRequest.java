package dpp.claims.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class MisrepresentationRequest {
    @NotNull
    private String sanction;
    @NotNull
    private List<String> reasons;
}
