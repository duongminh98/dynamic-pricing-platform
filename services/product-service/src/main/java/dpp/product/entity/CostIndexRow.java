package dpp.product.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "cost_index_row")
public class CostIndexRow {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "row_id")
    private UUID rowId;
    @Column(name = "version_id", nullable = false)
    private UUID versionId;
    @Column(name = "year", nullable = false)
    private Integer year;
    @Column(name = "month", nullable = false)
    private Integer month;
    @Column(name = "month_start", nullable = false)
    private LocalDate monthStart;
    @Column(name = "medical_inflation_index", nullable = false)
    private Double medicalInflationIndex;
    @Column(name = "vehicle_repair_inflation_index", nullable = false)
    private Double vehicleRepairInflationIndex;
    @Column(name = "construction_inflation_index", nullable = false)
    private Double constructionInflationIndex;
    @Column(name = "travel_medical_cost_index", nullable = false)
    private Double travelMedicalCostIndex;
    @Column(name = "general_expense_index", nullable = false)
    private Double generalExpenseIndex;
}
