package dpp.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CostIndexVersionRequest {
    @NotBlank
    private String changeReason;
    @NotEmpty
    private List<CostIndexRowRequest> rows;
}
