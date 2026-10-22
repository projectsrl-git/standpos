package it.projectsrl.standpos.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("standpos.security")
public record SecurityProperties(
        @DefaultValue("5") int maxLoginFailures,
        @DefaultValue("15m") Duration loginLockDuration,
        @DefaultValue("10") int loginAttemptsPerMinute,
        @DefaultValue("5") int maxPinFailures,
        @DefaultValue("15m") Duration pinLockDuration,
        @DefaultValue("20") int enrolAttemptsPerMinute,
        @DefaultValue("true") boolean secureCookies,
        @DefaultValue("400d") Duration deviceCookieMaxAge) {}
