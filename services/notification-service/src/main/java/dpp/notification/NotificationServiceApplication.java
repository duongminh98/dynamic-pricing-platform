package dpp.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"dpp.notification", "dpp.common.outbox"})
@EntityScan(basePackages = {"dpp.notification", "dpp.common.outbox"})
@EnableJpaRepositories(basePackages = {"dpp.notification", "dpp.common.outbox"})
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
