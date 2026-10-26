package it.projectsrl.standpos.device;

import it.projectsrl.standpos.auth.AuthLookup;
import it.projectsrl.standpos.auth.CurrentDevice;
import it.projectsrl.standpos.auth.RateLimiter;
import it.projectsrl.standpos.common.ApiException;
import it.projectsrl.standpos.tenant.TenantContext;
import it.projectsrl.standpos.venue.VenueRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Enrolment codes, enrolment, listing, renaming and revocation of devices. */
@Service
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);
    static final Duration CODE_VALIDITY = Duration.ofMinutes(10);
    private static final Duration SEEN_GRANULARITY = Duration.ofMinutes(1);

    public record Enrolled(Device device, String token) {}

    /** Kinds enrolled by {@code POST /api/v1/devices/enrol}: browsers, which receive the token as a cookie. */
    public static final Set<DeviceKind> BROWSER_KINDS = EnumSet.of(DeviceKind.REGISTER, DeviceKind.KITCHEN);

    /** Kinds enrolled by {@code POST /api/v1/agents/enrol}, which returns the token in the body. */
    public static final Set<DeviceKind> AGENT_KINDS = EnumSet.of(DeviceKind.PRINT_AGENT);

    private final DeviceRepository devices;
    private final DeviceEnrolmentCodeRepository codes;
    private final VenueRepository venues;
    private final AuthLookup lookup;
    private final RateLimiter enrolRateLimiter;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public DeviceService(DeviceRepository devices, DeviceEnrolmentCodeRepository codes, VenueRepository venues,
            AuthLookup lookup, @Qualifier("enrolRateLimiter") RateLimiter enrolRateLimiter, TransactionTemplate tx,
            Clock clock, ApplicationEventPublisher events) {
        this.devices = devices;
        this.codes = codes;
        this.venues = venues;
        this.lookup = lookup;
        this.enrolRateLimiter = enrolRateLimiter;
        this.tx = tx;
        this.clock = clock;
        this.events = events;
    }

    public DeviceEnrolmentCode createEnrolmentCode(UUID venueId, DeviceKind kind, UUID createdBy) {
        for (int attempt = 0; ; attempt++) {
            try {
                return tx.execute(status -> {
                    venues.findById(venueId).orElseThrow(() -> ApiException.notFound("Venue"));
                    return codes.saveAndFlush(new DeviceEnrolmentCode(venueId, kind, DeviceTokens.newEnrolmentCode(),
                            clock.instant().plus(CODE_VALIDITY), createdBy));
                });
            } catch (DataIntegrityViolationException e) {
                if (attempt >= 2) {
                    throw e; // three code collisions in a row: something else is wrong
                }
            }
        }
    }

    /** Exchanges a one-time code for a device token. The code is burnt even if the device insert then fails. */
    public Enrolled enrol(String rawCode, String name, String clientIp, Set<DeviceKind> accepted) {
        if (!enrolRateLimiter.tryAcquire("enrol:" + clientIp)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "device.rate_limited", "Too many enrolment attempts");
        }
        String code = DeviceTokens.normaliseCode(rawCode);
        AuthLookup.ClaimedCode claimed = lookup.claimEnrolmentCode(code, accepted)
                .orElseThrow(() -> ApiException.badRequest("device.invalid_code", "Unknown, used or expired code"));
        if (!claimed.claimed()) {
            // A valid code of another kind: not burnt, so it still works on the right endpoint.
            throw claimed.kind() == DeviceKind.PRINT_AGENT
                    ? ApiException.badRequest("device.agent_code", "This code enrols a print agent, not a browser")
                    : ApiException.badRequest("device.not_agent_code", "This code does not enrol a print agent");
        }
        String token = DeviceTokens.newToken();
        Device device = TenantContext.callAs(claimed.tenantId(), () -> tx.execute(status -> {
            Device created = devices.save(new Device(
                    claimed.venueId(), claimed.kind(), name, DeviceTokens.hash(token), null));
            codes.findById(claimed.codeId()).ifPresent(c -> c.linkDevice(created.getId()));
            return created;
        }));
        log.info("Device {} enrolled as {} for venue {}", device.getId(), device.getKind(), device.getVenueId());
        return new Enrolled(device, token);
    }

    @Transactional(readOnly = true)
    public List<Device> list(UUID venueId) {
        return devices.findAllByVenueIdOrderByCreatedAtDesc(venueId);
    }

    @Transactional
    public Device rename(UUID venueId, UUID deviceId, String name) {
        Device device = find(venueId, deviceId);
        device.rename(name);
        return device;
    }

    @Transactional
    public Device revoke(UUID venueId, UUID deviceId) {
        Device device = find(venueId, deviceId);
        device.revoke(clock.instant());
        events.publishEvent(new DeviceEvents.Revoked(deviceId));
        log.info("Device {} revoked", deviceId);
        return device;
    }

    /** Updates last_seen_at at most once a minute per device. */
    public void seen(CurrentDevice device) {
        Instant now = clock.instant();
        if (device.lastSeenAt() != null && device.lastSeenAt().plus(SEEN_GRANULARITY).isAfter(now)) {
            return;
        }
        TenantContext.runAs(device.tenantId(), () -> tx.executeWithoutResult(
                status -> devices.findById(device.deviceId()).ifPresent(d -> d.seen(now))));
    }

    private Device find(UUID venueId, UUID deviceId) {
        return devices.findByIdAndVenueId(deviceId, venueId).orElseThrow(() -> ApiException.notFound("Device"));
    }
}
