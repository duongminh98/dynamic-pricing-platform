package dpp.order.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ExtendDueDateRequest {
    @Min(1)
    @Max(90)
    private int extraDays;
}
