package dpp.customer.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "profile_version")
@Getter
@Setter
public class ProfileVersion {

    @Id
    private UUID versionId;

    @ManyToOne(optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private CustomerProfile customerProfile;

    @Column(nullable = false, length = 20)
    private String line;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "line_attributes", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> lineAttributes;

    @Column(name = "effective_at", nullable = false)
    private OffsetDateTime effectiveAt;
}
