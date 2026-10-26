package it.projectsrl.standpos.device;

import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Print agent enrolment (docs/adr/0010-print-agent-enrolment.md): accepts only PRINT_AGENT codes, sets no cookie and
 * returns the device token once, in the body. It is exempt from CSRF: CSRF protects cookie-authenticated browser
 * requests, and this endpoint neither reads nor sets cookies, while a cross-site page cannot read its response.
 */
@RestController
class AgentEnrolController {

    record EnrolRequest(@NotBlank @Size(max = 32) String code, @NotBlank @Size(max = 80) String name) {}

    record AgentDevice(UUID id, UUID venueId, DeviceKind kind, String name) {}

    record EnrolResponse(AgentDevice device, String token) {}

    private final DeviceService devices;

    AgentEnrolController(DeviceService devices) {
        this.devices = devices;
    }

    @PostMapping("/api/v1/agents/enrol")
    @PermitAll
    @ResponseStatus(HttpStatus.CREATED)
    EnrolResponse enrol(@Valid @RequestBody EnrolRequest request, HttpServletRequest http) {
        DeviceService.Enrolled enrolled = devices.enrol(request.code(), request.name().trim(), http.getRemoteAddr(),
                DeviceService.AGENT_KINDS);
        Device d = enrolled.device();
        return new EnrolResponse(new AgentDevice(d.getId(), d.getVenueId(), d.getKind(), d.getName()), enrolled.token());
    }
}
