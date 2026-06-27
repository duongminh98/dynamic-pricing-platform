package dpp.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"dpp.order", "dpp.common.outbox"})
@EntityScan(basePackages = {"dpp.order", "dpp.common.outbox"})
@EnableJpaRepositories(basePackages = {"dpp.order", "dpp.common.outbox"})
@EnableScheduling
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
