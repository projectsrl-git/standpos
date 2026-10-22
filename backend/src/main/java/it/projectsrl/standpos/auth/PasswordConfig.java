package it.projectsrl.standpos.auth;

import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
class PasswordConfig {

    /** Argon2id for passwords and PINs (Bouncy Castle implementation). */
    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    RateLimiter loginRateLimiter(SecurityProperties properties, Clock clock) {
        return new RateLimiter(properties.loginAttemptsPerMinute(), Duration.ofMinutes(1), clock);
    }

    @Bean
    RateLimiter enrolRateLimiter(SecurityProperties properties, Clock clock) {
        return new RateLimiter(properties.enrolAttemptsPerMinute(), Duration.ofMinutes(1), clock);
    }
}
