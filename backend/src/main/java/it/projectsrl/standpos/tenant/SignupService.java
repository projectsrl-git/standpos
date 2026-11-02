package it.projectsrl.standpos.tenant;

import it.projectsrl.standpos.audit.AuditService;
import it.projectsrl.standpos.auth.AppUser;
import it.projectsrl.standpos.auth.AppUserRepository;
import it.projectsrl.standpos.auth.AuthLookup;
import it.projectsrl.standpos.auth.Membership;
import it.projectsrl.standpos.auth.MembershipRepository;
import it.projectsrl.standpos.auth.RateLimiter;
import it.projectsrl.standpos.auth.Role;
import it.projectsrl.standpos.auth.StandposPrincipal;
import it.projectsrl.standpos.common.ApiException;
import it.projectsrl.standpos.common.Ids;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Self-service sign-up: a tenant on the free plan, its owner and the OWNER membership, written in one transaction
 * under the new tenant's context. An email that is already registered creates and changes nothing, and the caller
 * gets the same answer as for a new one (docs/adr/0015-self-service-sign-up.md).
 */
@Service
public class SignupService {

    private static final Logger log = LoggerFactory.getLogger(SignupService.class);

    /** Version of the declarations shown on the sign-up page; change it whenever their wording changes. */
    public static final String DECLARATIONS_VERSION = "2026-11-02";

    public record Signup(String organisationName, String ownerName, String email, String password) {}

    private final TenantRepository tenants;
    private final AppUserRepository users;
    private final MembershipRepository memberships;
    private final AuthLookup lookup;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final RateLimiter rateLimiter;

    public SignupService(TenantRepository tenants, AppUserRepository users, MembershipRepository memberships,
            AuthLookup lookup, PasswordEncoder encoder, AuditService audit, TransactionTemplate tx,
            SignupProperties properties, Clock clock) {
        this.tenants = tenants;
        this.users = users;
        this.memberships = memberships;
        this.lookup = lookup;
        this.encoder = encoder;
        this.audit = audit;
        this.tx = tx;
        this.rateLimiter = new RateLimiter(properties.attemptsPerHour(), Duration.ofHours(1), clock);
    }

    /** Returns the new owner, or empty when the email is already registered. */
    public Optional<StandposPrincipal> signUp(Signup request, String clientIp) {
        if (!rateLimiter.tryAcquire("signup:" + clientIp)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "signup.rate_limited", "Too many sign-ups");
        }
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        // Hashed before the lookup, so a registered and a new email cost the same Argon2 work.
        String passwordHash = encoder.encode(request.password());
        if (lookup.findLogin(email).isPresent()) {
            log.info("Sign-up refused: the email is already registered");
            return Optional.empty();
        }
        UUID tenantId = Ids.newId();
        try {
            return Optional.of(TenantContext.callAs(tenantId, () -> tx.execute(status -> {
                String name = request.organisationName().trim();
                tenants.save(new Tenant(tenantId, name, Plan.FREE_ID));
                AppUser owner = new AppUser(email, request.ownerName().trim());
                owner.setPasswordHash(passwordHash);
                users.saveAndFlush(owner);
                memberships.save(new Membership(owner.getId(), null, Role.OWNER));
                audit.recordAs(owner.getId(), "tenant.signed_up", "tenant", tenantId, null, signedUp(name));
                log.info("Sign-up created tenant {} and owner {}", tenantId, owner.getId());
                return StandposPrincipal.user(tenantId, owner.getId(), null, false);
            })));
        } catch (DataIntegrityViolationException e) {
            // A concurrent sign-up took the same email first (unique index on app_user.email).
            log.info("Sign-up refused: the email was registered concurrently");
            return Optional.empty();
        }
    }

    private static Map<String, Object> signedUp(String name) {
        Map<String, Object> declarations = new LinkedHashMap<>();
        declarations.put("nonProfit", true);
        declarations.put("nonFiscalReceiptsOnly", true);
        declarations.put("fiscalObligationsOwnResponsibility", true);
        declarations.put("version", DECLARATIONS_VERSION);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        data.put("plan", "FREE");
        data.put("declarations", declarations);
        return data;
    }
}
