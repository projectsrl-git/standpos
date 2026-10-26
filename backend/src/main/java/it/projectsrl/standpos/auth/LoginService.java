package it.projectsrl.standpos.auth;

import it.projectsrl.standpos.common.ApiException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Email and password login for owners and managers, with per-IP rate limiting and per-account lockout. */
@Service
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    private final AuthLookup lookup;
    private final PasswordEncoder encoder;
    private final RateLimiter rateLimiter;
    private final SecurityProperties properties;
    private final Clock clock;
    private final String dummyHash;

    public LoginService(AuthLookup lookup, PasswordEncoder encoder,
            @Qualifier("loginRateLimiter") RateLimiter rateLimiter, SecurityProperties properties, Clock clock) {
        this.lookup = lookup;
        this.encoder = encoder;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.clock = clock;
        this.dummyHash = encoder.encode("standpos-timing-equaliser");
    }

    public StandposPrincipal login(String email, String password, String clientIp) {
        if (!rateLimiter.tryAcquire("login:" + clientIp)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "auth.rate_limited", "Too many login attempts");
        }
        Optional<AuthLookup.LoginRow> found = lookup.findLogin(email);
        if (found.isEmpty() || found.get().passwordHash() == null) {
            encoder.matches(password, dummyHash);
            throw invalidCredentials();
        }
        AuthLookup.LoginRow row = found.get();
        if (row.lockedUntil() != null && row.lockedUntil().isAfter(clock.instant())) {
            throw locked();
        }
        if (!encoder.matches(password, row.passwordHash())) {
            Optional<java.time.Instant> lockedNow = lookup.recordLoginFailure(
                    row.userId(), properties.maxLoginFailures(), properties.loginLockDuration());
            if (lockedNow.isPresent()) {
                log.warn("Account {} locked after repeated login failures", row.userId());
                throw locked();
            }
            throw invalidCredentials();
        }
        if (!"ACTIVE".equals(row.status())) {
            throw invalidCredentials();
        }
        List<AuthLookup.MembershipRow> memberships = lookup.userMemberships(row.userId());
        if (memberships.isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "auth.no_membership", "The user belongs to no active tenant");
        }
        lookup.recordLoginSuccess(row.userId());
        // A user in several tenants lands in the oldest one; switching tenant is not in Phase 0.
        AuthLookup.MembershipRow first = memberships.getFirst();
        return StandposPrincipal.user(first.tenantId(), row.userId(), null, row.platformAdmin());
    }

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "auth.invalid_credentials", "Invalid email or password");
    }

    private static ApiException locked() {
        return new ApiException(HttpStatus.LOCKED, "auth.locked", "Too many failed attempts, try again later");
    }
}
