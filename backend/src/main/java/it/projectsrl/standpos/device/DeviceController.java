package it.projectsrl.standpos.device;

import it.projectsrl.standpos.auth.Authz;
import it.projectsrl.standpos.auth.CurrentDevice;
import it.projectsrl.standpos.auth.SecurityProperties;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class DeviceController {

    record CodeRequest(@NotNull DeviceKind kind) {}

    record CodeResponse(String code, DeviceKind kind, Instant expiresAt) {}

    record EnrolRequest(@NotBlank @Size(max = 32) String code, @NotBlank @Size(max = 80) String name) {}

    /** {@code token} is returned only to print agents; browsers receive it as an HttpOnly cookie. */
    record EnrolResponse(DeviceResponse device, String token) {}

    record RenameRequest(@NotBlank @Size(max = 80) String name) {}

    record DeviceResponse(UUID id, UUID venueId, DeviceKind kind, String name, Instant lastSeenAt, Instant revokedAt,
            Instant createdAt) {

        static DeviceResponse of(Device d) {
            return new DeviceResponse(d.getId(), d.getVenueId(), d.getKind(), d.getName(), d.getLastSeenAt(),
                    d.getRevokedAt(), d.getCreatedAt());
        }
    }

    record CurrentDeviceResponse(boolean enrolled, UUID id, UUID venueId, DeviceKind kind, String name) {}

    private final DeviceService devices;
    private final SecurityProperties properties;

    DeviceController(DeviceService devices, SecurityProperties properties) {
        this.devices = devices;
        this.properties = properties;
    }

    @PostMapping("/venues/{venueId}/enrolment-codes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.canManageVenue(#venueId)")
    CodeResponse createCode(@PathVariable UUID venueId, @Valid @RequestBody CodeRequest request) {
        DeviceEnrolmentCode code =
                devices.createEnrolmentCode(venueId, request.kind(), Authz.requirePrincipal().userId());
        return new CodeResponse(code.getCode(), code.getKind(), code.getExpiresAt());
    }

    @GetMapping("/venues/{venueId}/devices")
    @PreAuthorize("@authz.canManageVenue(#venueId)")
    List<DeviceResponse> list(@PathVariable UUID venueId) {
        return devices.list(venueId).stream().map(DeviceResponse::of).toList();
    }

    @PutMapping("/venues/{venueId}/devices/{deviceId}")
    @PreAuthorize("@authz.canManageVenue(#venueId)")
    DeviceResponse rename(@PathVariable UUID venueId, @PathVariable UUID deviceId,
            @Valid @RequestBody RenameRequest request) {
        return DeviceResponse.of(devices.rename(venueId, deviceId, request.name().trim()));
    }

    @PostMapping("/venues/{venueId}/devices/{deviceId}/revoke")
    @PreAuthorize("@authz.canManageVenue(#venueId)")
    DeviceResponse revoke(@PathVariable UUID venueId, @PathVariable UUID deviceId) {
        return DeviceResponse.of(devices.revoke(venueId, deviceId));
    }

    @PostMapping("/devices/enrol")
    @PermitAll
    ResponseEntity<EnrolResponse> enrol(@Valid @RequestBody EnrolRequest request, HttpServletRequest http) {
        DeviceService.Enrolled enrolled = devices.enrol(request.code(), request.name().trim(), http.getRemoteAddr());
        DeviceResponse device = DeviceResponse.of(enrolled.device());
        if (enrolled.device().getKind() == DeviceKind.PRINT_AGENT) {
            return ResponseEntity.status(HttpStatus.CREATED).body(new EnrolResponse(device, enrolled.token()));
        }
        ResponseCookie cookie = ResponseCookie.from(DeviceTokens.COOKIE, enrolled.token())
                .httpOnly(true)
                .secure(properties.secureCookies())
                .sameSite("Lax")
                .path("/")
                .maxAge(properties.deviceCookieMaxAge())
                .build();
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new EnrolResponse(device, null));
    }

    @GetMapping("/devices/current")
    @PermitAll
    CurrentDeviceResponse current(HttpServletRequest http) {
        return CurrentDevice.of(http)
                .map(d -> new CurrentDeviceResponse(true, d.deviceId(), d.venueId(), d.kind(), d.name()))
                .orElse(new CurrentDeviceResponse(false, null, null, null, null));
    }
}
