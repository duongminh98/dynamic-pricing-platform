package dpp.product.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "product")
public class Product {

    @Id
    @Column(name = "product_id", length = 32)
    private String productId;

    @Column(name = "category", length = 20, nullable = false)
    private String category;

    @Column(name = "product_name", length = 200, nullable = false)
    private String productName;

    @Column(name = "coverage_amount_vnd", nullable = false)
    private Long coverageAmountVnd;

    @Column(name = "deductible_vnd", nullable = false)
    private Long deductibleVnd;

    @Column(name = "base_premium_vnd", nullable = false)
    private Long basePremiumVnd;

    @Column(name = "admin_fee_vnd", nullable = false)
    private Long adminFeeVnd;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = true;
}


