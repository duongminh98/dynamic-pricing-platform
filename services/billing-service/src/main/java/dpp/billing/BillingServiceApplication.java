package dpp.billing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"dpp.billing", "dpp.common.outbox"})
@EntityScan(basePackages = {"dpp.billing", "dpp.common.outbox"})
@EnableJpaRepositories(basePackages = {"dpp.billing", "dpp.common.outbox"})
public class BillingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BillingServiceApplication.class, args);
    }
}
