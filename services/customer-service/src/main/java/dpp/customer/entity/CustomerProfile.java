package dpp.customer.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "customer_profile")
@Getter
@Setter
public class CustomerProfile {

    @Id
    private UUID customerId;

    @OneToOne(optional = false)
    @JoinColumn(name = "account_id", nullable = false, unique = true)
    private Account account;

    @Column(nullable = false)
    private int age;

    @Column(nullable = false, length = 10)
    private String gender;

    @Column(nullable = false, length = 50)
    private String province;

    @Column(nullable = false, length = 50)
    private String region;

    @Column(name = "urban_tier", nullable = false, length = 10)
    private String urbanTier;

    @Column(nullable = false, length = 100)
    private String occupation;

    @Column(name = "income_level", nullable = false, length = 20)
    private String incomeLevel;

    @Column(name = "monthly_income_vnd", nullable = false)
    private long monthlyIncomeVnd;

    @Column(name = "marital_status", nullable = false, length = 20)
    private String maritalStatus;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
