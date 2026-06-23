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
@Table(name = "eligibility_rule")
public class EligibilityRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "rule_id")
    private UUID ruleId;

    @Column(name = "rate_version_id", nullable = false)
    private UUID rateVersionId;

    @Column(name = "line", length = 20, nullable = false)
    private String line;

    @Column(name = "rule_type", length = 20, nullable = false)
    private String ruleType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private String params = "{}";

    @Column(name = "action", length = 10, nullable = false)
    private String action;
}



