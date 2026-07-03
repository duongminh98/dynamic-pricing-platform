package dpp.customer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"dpp.customer", "dpp.common.outbox"})
@EntityScan(basePackages = {"dpp.customer", "dpp.common.outbox"})
@EnableJpaRepositories(basePackages = {"dpp.customer", "dpp.common.outbox"})
public class CustomerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CustomerServiceApplication.class, args);
    }
}
