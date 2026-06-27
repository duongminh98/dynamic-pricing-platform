package dpp.customer.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class LineProfileRequest {
    @NotNull
    private Map<String, Object> lineAttributes;
}
