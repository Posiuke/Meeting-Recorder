package bbbbot.config;

import bbbbot.auth.ApiKeyAuthFilter;
import bbbbot.auth.JwtAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Stateless-Security mit JWT. Die eigentliche Anmeldung (lokales Passwort oder
 * LDAP/AD) laeuft ueber {@code AuthService} und ist zur Laufzeit konfigurierbar -
 * es gibt daher bewusst KEINEN statischen AuthenticationManager mehr.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter,
                                           ApiKeyAuthFilter apiKeyAuthFilter) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> {})
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/login", "/actuator/health").permitAll()
                // Freigabe-Links: Die Berechtigung steckt im Token in der Adresse,
                // geprueft wird sie im PublicShareController. Nur Lesen, nur die
                // eine freigegebene Aufnahme.
                .requestMatchers("/api/public/**").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll())
            // Ohne (gueltigen) Token -> 401, damit das Frontend die Session
            // beendet und zum Login leitet. Ohne diesen EntryPoint liefert
            // Spring Security 403 - das Frontend haelt den Nutzer dann fuer
            // eingeloggt und zeigt nur "verbotene" Seiten an. 403 bleibt fuer
            // echte Berechtigungsfehler (eingeloggt, aber z.B. kein Admin).
            .exceptionHandling(ex -> ex.authenticationEntryPoint(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            // API-Schluessel zuerst: Er wird am Prefix erkannt, sodass ein
            // Login-Token (JWT) unberuehrt bleibt.
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(apiKeyAuthFilter, JwtAuthFilter.class);
        return http.build();
    }

    /** Anwendungsweiter Encoder fuer lokale Passwoerter (bcrypt via {bcrypt}-Prefix). */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
