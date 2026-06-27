package dpp.customer.config;

import dpp.common.security.KeycloakRoleConverter;
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
                .requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
                .requestMatchers("/internal/**").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .requestMatchers("/customers/register", "/customers/login").permitAll()
                .requestMatchers("/customers/me", "/customers/me/**").hasAnyRole("Customer", "Administrator")
                .requestMatchers("/admin/**").hasRole("Administrator")
                .anyRequest().authenticated()
        );
        http.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(KeycloakRoleConverter.create())));
        return http.build();
    }
}
