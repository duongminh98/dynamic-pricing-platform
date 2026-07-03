package dpp.product.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CostIndexRowResponse {
    private Integer year;
    private Integer month;
    private String monthStart;
    private Double medicalInflationIndex;
    private Double vehicleRepairInflationIndex;
    private Double constructionInflationIndex;
    private Double travelMedicalCostIndex;
    private Double generalExpenseIndex;
}
