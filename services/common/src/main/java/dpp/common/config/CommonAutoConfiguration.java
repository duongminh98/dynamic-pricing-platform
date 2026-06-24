package dpp.common.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;
import dpp.common.api.CorrelationIdFilter;
import dpp.common.api.CorrelationIdInterceptor;
import dpp.common.api.GlobalExceptionHandler;

import java.util.List;

/**
 * Auto-configuration for the DPP common library.
 * Imported by Spring Boot auto-configuration via
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports.
 *
 * <p>Provides:</p>
 * <ul>
 *   <li>{@link CorrelationIdFilter} — read/generate X-Correlation-Id, set MDC (R19.5)</li>
 *   <li>{@link CorrelationIdInterceptor} — propagate correlation-id on outgoing calls (R19.6)</li>
 *   <li>{@link GlobalExceptionHandler} — structured error responses (R19.3, R18.4)</li>
 *   <li>{@link RestTemplate} — pre-configured with correlation interceptor</li>
 * </ul>
 *
 * <p>OutboxRelay and OutboxPublisher are @Component-annotated and
 * picked up via component scanning when the consuming service adds
 * {@code scanBasePackages = {"dpp.<svc>", "dpp.common.outbox"}} to its
 * {@code @SpringBootApplication} along with {@code @EntityScan} and
 * {@code @EnableJpaRepositories} for the outbox package.</p>
 */
@AutoConfiguration
@EnableScheduling
public class CommonAutoConfiguration {

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new CorrelationIdFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("correlationIdFilter");
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean
    public CorrelationIdInterceptor correlationIdInterceptor() {
        return new CorrelationIdInterceptor();
    }

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean(name = "dppJacksonSnakeCaseCustomizer")
    public Jackson2ObjectMapperBuilderCustomizer dppJacksonSnakeCaseCustomizer() {
        return builder -> builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }
    @Bean
    @ConditionalOnMissingBean
    public RestTemplate restTemplate(CorrelationIdInterceptor correlationIdInterceptor) {
        RestTemplate rt = new RestTemplate();
        rt.setInterceptors(List.of(correlationIdInterceptor));
        return rt;
    }
}
