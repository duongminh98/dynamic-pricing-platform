package dpp.product.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@ComponentScan(basePackages = "dpp.common.outbox")
@EntityScan(basePackages = {"dpp.product", "dpp.common.outbox"})
@EnableJpaRepositories(basePackages = {"dpp.product", "dpp.common.outbox"})
public class ProductOutboxConfig {
}
