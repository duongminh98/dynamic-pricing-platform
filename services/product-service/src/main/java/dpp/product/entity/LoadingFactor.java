package dpp.product.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Getter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "loading_factor")
public class LoadingFactor {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "loading_factor_id")
    private UUID loadingFactorId;

    @Column(name = "rate_version_id", nullable = false)
    private UUID rateVersionId;

    @Column(name = "line", length = 20, nullable = false)
    private String line;

    @Column(name = "loading_value", nullable = false)
    private Double loadingValue;
}


