package com.patobytes.tasks.config;

import com.patobytes.tasks.user.UserProvisioningService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final UserProvisioningService userProvisioning;

    public SecurityConfig(UserProvisioningService userProvisioning) {
        this.userProvisioning = userProvisioning;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // The SPA reads the XSRF-TOKEN cookie and echoes it, so the repository
        // must be readable by JavaScript. This is the standard cookie-based
        // pattern, not a weakening of CSRF protection.
        CookieCsrfTokenRepository csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();

        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**").permitAll()
                .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico",
                                 "/manifest.webmanifest").permitAll()
                .anyRequest().authenticated())
            .csrf(c -> c
                .csrfTokenRepository(csrf)
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .oauth2Login(login -> login
                .userInfoEndpoint(userInfo -> userInfo.oidcUserService(userProvisioning))
                .defaultSuccessUrl("/", true))
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/")
                .deleteCookies("JSESSIONID"))
            // An expired session on an API call must not answer with a 302 to
            // Microsoft - the browser cannot follow that from fetch(). A bare
            // 401 lets the SPA decide to redirect.
            .exceptionHandling(ex -> ex
                .defaultAuthenticationEntryPointFor(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                    new AntPathRequestMatcher("/api/**")));

        return http.build();
    }
}
