package dpp.claims;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"dpp.claims", "dpp.common.outbox"})
@EntityScan(basePackages = {"dpp.claims", "dpp.common.outbox"})
@EnableJpaRepositories(basePackages = {"dpp.claims", "dpp.common.outbox"})
public class ClaimsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClaimsServiceApplication.class, args);
    }
}
