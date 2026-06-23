package dpp.product.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Getter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "coverage_option")
public class CoverageOption {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "coverage_option_id")
    private UUID coverageOptionId;

    @Column(name = "product_id", length = 32, nullable = false)
    private String productId;

    @Column(name = "coverage_amount_vnd", nullable = false)
    private Long coverageAmountVnd;

    @Column(name = "deductible_vnd", nullable = false)
    private Long deductibleVnd;

    @Column(name = "base_premium_vnd", nullable = false)
    private Long basePremiumVnd;

    @Column(name = "admin_fee_vnd", nullable = false)
    private Long adminFeeVnd;
}


