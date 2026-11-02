package it.projectsrl.standpos.tenant;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Self-service sign-up (docs/adr/0015-self-service-sign-up.md). Off unless enabled: there is no email verification
 * yet, so production keeps it off; the dev and demo profiles turn it on.
 */
@ConfigurationProperties("standpos.signup")
public record SignupProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("5") int attemptsPerHour) {}
