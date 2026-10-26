package it.projectsrl.standpos.venue;

import it.projectsrl.standpos.auth.Authz;
import it.projectsrl.standpos.auth.Membership;
import it.projectsrl.standpos.auth.MembershipRepository;
import it.projectsrl.standpos.auth.Role;
import it.projectsrl.standpos.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/venues")
class VenueController {

    record VenueRequest(
            @NotBlank @Size(max = 80) String name,
            @NotNull Venue.Kind kind,
            @Size(max = 64) String timeZone,
            LocalTime dayChangeTime,
            @Size(max = 500) String receiptHeader,
            @Size(max = 500) String receiptFooter,
            Boolean active,
            Boolean allowNegativeStock) {}

    record VenueResponse(UUID id, String name, Venue.Kind kind, String timeZone, LocalTime dayChangeTime,
            String receiptHeader, String receiptFooter, Venue.TicketMode ticketMode, boolean active,
            boolean allowNegativeStock) {

        static VenueResponse of(Venue v) {
            return new VenueResponse(v.getId(), v.getName(), v.getKind(), v.getTimeZone(), v.getDayChangeTime(),
                    v.getReceiptHeader(), v.getReceiptFooter(), v.getTicketMode(), v.isActive(),
                    v.isAllowNegativeStock());
        }
    }

    private final VenueRepository venues;
    private final MembershipRepository memberships;

    VenueController(VenueRepository venues, MembershipRepository memberships) {
        this.venues = venues;
        this.memberships = memberships;
    }

    /** Owners see every venue; other users the venues they hold a role on. */
    @GetMapping
    @PreAuthorize("@authz.hasAnyMembership()")
    @Transactional(readOnly = true)
    public List<VenueResponse> list() {
        List<Membership> mine = memberships.findAllByUserId(Authz.requirePrincipal().userId());
        boolean owner = mine.stream().anyMatch(m -> m.getRole() == Role.OWNER);
        Set<UUID> visible = mine.stream().map(Membership::getVenueId).collect(Collectors.toSet());
        return venues.findAllByOrderByName().stream()
                .filter(v -> owner || visible.contains(v.getId()))
                .map(VenueResponse::of)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.isOwner()")
    @Transactional
    public VenueResponse create(@Valid @RequestBody VenueRequest request) {
        Venue venue = new Venue(request.name().trim(), request.kind());
        apply(venue, request);
        return VenueResponse.of(venues.save(venue));
    }

    @PutMapping("/{venueId}")
    @PreAuthorize("@authz.isOwner()")
    @Transactional
    public VenueResponse update(@PathVariable UUID venueId, @Valid @RequestBody VenueRequest request) {
        Venue venue = venues.findById(venueId).orElseThrow(() -> ApiException.notFound("Venue"));
        apply(venue, request);
        return VenueResponse.of(venue);
    }

    private static void apply(Venue venue, VenueRequest r) {
        String zone = r.timeZone() == null || r.timeZone().isBlank() ? venue.getTimeZone() : r.timeZone();
        try {
            ZoneId.of(zone);
        } catch (DateTimeException e) {
            throw ApiException.badRequest("venue.invalid_time_zone", "Unknown time zone " + zone);
        }
        venue.update(r.name().trim(), r.kind(), zone,
                r.dayChangeTime() == null ? venue.getDayChangeTime() : r.dayChangeTime(),
                r.receiptHeader(), r.receiptFooter(), r.active() == null || r.active(),
                r.allowNegativeStock() == null ? venue.isAllowNegativeStock() : r.allowNegativeStock());
    }
}
