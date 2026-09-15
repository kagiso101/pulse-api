package pulse_api.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import pulse_api.security.JsonAuthHandlers;
import pulse_api.security.JwtAuthenticationFilter;
import pulse_api.security.RateLimitFilter;
import pulse_api.security.SchedulerAuthFilter;

/**
 * The authorization table for the whole API (contract §0.1 / §4): everything under /api/** needs
 * the Pulse JWT except Google sign-in, the public client view and the GitHub webhook;
 * /internal/** is Cloud Scheduler only.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final SchedulerAuthFilter schedulerAuthFilter;
    private final RateLimitFilter rateLimitFilter;
    private final JsonAuthHandlers jsonAuthHandlers;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
                        .referrerPolicy(rp -> rp.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(jsonAuthHandlers)
                        .accessDeniedHandler(jsonAuthHandlers))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**",
                                "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/google").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/public/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/webhooks/github").permitAll()
                        .requestMatchers("/internal/**").hasRole(SchedulerAuthFilter.ROLE_SCHEDULER)
                        .requestMatchers("/api/**").hasRole("OWNER")
                        .anyRequest().denyAll())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(schedulerAuthFilter, JwtAuthenticationFilter.class)
                .addFilterBefore(rateLimitFilter, SchedulerAuthFilter.class);
        return http.build();
    }

    /** Auth is JWT-only; defining this stops Spring Boot from logging a generated password. */
    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("No form-login users — authentication is JWT-only");
        };
    }
}
