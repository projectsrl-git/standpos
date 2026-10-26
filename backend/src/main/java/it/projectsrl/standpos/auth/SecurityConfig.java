package it.projectsrl.standpos.auth;

import it.projectsrl.standpos.device.DeviceService;
import it.projectsrl.standpos.tenant.TenantContextFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP security. Browsers use a session cookie plus a CSRF token (cookie-to-header, readable by the SPA); the print
 * agent uses a bearer device token and is exempt from CSRF. Every controller method also carries its own
 * {@code @PreAuthorize} or {@code @PermitAll} rule (checked by {@code ControllerAuthorizationCoverageTest}).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity(jsr250Enabled = true)
class SecurityConfig {

    /** Print agent enrolment: no cookie read or set, so no CSRF (docs/adr/0010-print-agent-enrolment.md). */
    static final String AGENT_ENROL = "/api/v1/agents/enrol";

    static final String[] PUBLIC_API = {
        "/api/v1/auth/csrf", "/api/v1/auth/login", "/api/v1/auth/logout", "/api/v1/devices/enrol",
        "/api/v1/devices/current", AGENT_ENROL
    };

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuthLookup lookup, DeviceService devices,
            SessionSupport sessions, SecurityProperties properties, JsonMapper json) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookiePath("/");
        csrfRepository.setCookieCustomizer(c -> c.secure(properties.secureCookies()).sameSite("Lax"));

        http.securityContext(c -> c.securityContextRepository(sessions.repository()))
                .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                        .ignoringRequestMatchers(SecurityConfig::isCookieless))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .headers(h -> h.referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN)))
                .addFilterBefore(new DeviceAuthenticationFilter(lookup, devices::seen), AnonymousAuthenticationFilter.class)
                .addFilterAfter(new TenantContextFilter(), AnonymousAuthenticationFilter.class)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, ex) -> writeProblem(json, response,
                                HttpStatus.UNAUTHORIZED, "unauthenticated", "Authentication required"))
                        .accessDeniedHandler((request, response, ex) -> {
                            if (ex instanceof CsrfException) {
                                writeProblem(json, response, HttpStatus.FORBIDDEN, "csrf.invalid",
                                        "Missing or invalid CSRF token");
                            } else {
                                writeProblem(json, response, HttpStatus.FORBIDDEN, "access_denied", "Access denied");
                            }
                        }))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**").authenticated()
                        .requestMatchers(PUBLIC_API).permitAll()
                        .requestMatchers("/api/**").authenticated()
                        // WebSocket handshake: session (browsers) or bearer device token (agent); see ADR 0009.
                        .requestMatchers("/ws").authenticated()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }

    private static boolean hasBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return header != null && header.regionMatches(true, 0, "Bearer ", 0, 7);
    }

    /** Requests of the print agent: bearer-authenticated, or its enrolment. Exempt from CSRF, given no CSRF cookie. */
    static boolean isCookieless(HttpServletRequest request) {
        return hasBearerToken(request) || AGENT_ENROL.equals(request.getRequestURI());
    }

    static void writeProblem(JsonMapper json, HttpServletResponse response, HttpStatus status, String code,
            String detail) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "https://standpos.it/problems/" + code.replace('.', '/'));
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("code", code);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), body);
    }

    /**
     * CSRF for a single-page app: the token is read from the {@code XSRF-TOKEN} cookie and sent back in the
     * {@code X-XSRF-TOKEN} header (plain); server-rendered values keep BREACH protection (XOR). The token is loaded
     * on every browser request so the cookie is always present; print agent requests never get the cookie.
     */
    static final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

        private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
        private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
            xor.handle(request, response, csrfToken);
            if (!isCookieless(request)) {
                csrfToken.get();
            }
        }

        @Override
        public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
            String header = request.getHeader(csrfToken.getHeaderName());
            return (StringUtils.hasText(header) ? plain : xor).resolveCsrfTokenValue(request, csrfToken);
        }
    }
}
