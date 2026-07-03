package dpp.common.security;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

public final class GatewaySecurity {

    private GatewaySecurity() {
    }

    public static void configure(HttpSecurity http) throws Exception {
        http.addFilterBefore(new TrustedGatewayAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class);
        http.exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(new GatewayAuthenticationEntryPoint())
                .accessDeniedHandler(new GatewayAccessDeniedHandler())
        );
    }
}
