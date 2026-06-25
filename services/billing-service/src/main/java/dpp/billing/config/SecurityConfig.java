package dpp.billing.config;

import dpp.common.security.KeycloakRoleConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.http.HttpMethod;
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
                // Internal-only: Order_Service calls this after order approval (commit-then-REST).
                // Left open for the internal flow; createInvoice is idempotent on order_id (task 20.11)
                // so it is safe to replay. Should be locked down to an internal credential / network
                // policy at the gateway; not customer-reachable in practice.
                .requestMatchers(HttpMethod.POST, "/billing/invoices").permitAll()
                // VNPAY callbacks are server-to-server and authenticated by signature, not JWT.
                .requestMatchers("/billing/vnpay/return", "/billing/vnpay/ipn").permitAll()
                // POST /billing/invoices/{id}/pay now requires authentication; ownership is enforced
                // in BillingService.payInvoiceAsCustomer (task 20.13, R33.5).
                .anyRequest().authenticated()
        );
        http.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(KeycloakRoleConverter.create())));
        return http.build();
    }
}
