package dpp.billing.config;

import dpp.common.security.GatewaySecurity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable);
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/prometheus", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                // Internal-only: peer-service calls (order-service createInvoice, void-by-endorsement, etc.).
                // Not exposed via Kong â€” only reachable within the Docker network.
                .requestMatchers("/internal/**").permitAll()
                // VNPAY callbacks are server-to-server and authenticated by signature, not JWT.
                .requestMatchers("/billing/vnpay/return", "/billing/vnpay/ipn").permitAll()
                // All other endpoints require JWT authentication.
                .anyRequest().authenticated()
        );
        GatewaySecurity.configure(http);
        return http.build();
    }
}

