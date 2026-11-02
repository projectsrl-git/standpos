package it.projectsrl.standpos.tenant;

import it.projectsrl.standpos.auth.SessionSupport;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public sign-up page backend. Exists only when {@code standpos.signup.enabled} is true; otherwise both methods are
 * 404. CSRF applies as for login.
 */
@RestController
@RequestMapping("/api/v1/auth/signup")
@ConditionalOnProperty(prefix = "standpos.signup", name = "enabled", havingValue = "true")
class SignupController {

    record SignupRequest(
            @NotBlank @Size(max = 120) String organisationName,
            @NotBlank @Size(max = 80) String ownerName,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 10, max = 200) String password,
            @AssertTrue boolean nonProfit,
            @AssertTrue boolean nonFiscalAcknowledged) {}

    record AvailabilityResponse(boolean enabled, String declarationsVersion) {}

    /** The same for a new and for an already registered email; only a new owner also gets a session. */
    record SignupResponse(String status) {}

    private static final SignupResponse ACCEPTED = new SignupResponse("accepted");

    private final SignupService signups;
    private final SessionSupport sessions;

    SignupController(SignupService signups, SessionSupport sessions) {
        this.signups = signups;
        this.sessions = sessions;
    }

    /** Lets the login page show the sign-up link only where sign-up is enabled. */
    @GetMapping
    @PermitAll
    AvailabilityResponse availability() {
        return new AvailabilityResponse(true, SignupService.DECLARATIONS_VERSION);
    }

    @PostMapping
    @PermitAll
    @ResponseStatus(HttpStatus.ACCEPTED)
    SignupResponse signUp(@Valid @RequestBody SignupRequest r, HttpServletRequest http, HttpServletResponse response) {
        sessions.close(http);
        signups.signUp(new SignupService.Signup(r.organisationName(), r.ownerName(), r.email(), r.password()),
                        http.getRemoteAddr())
                .ifPresent(owner -> sessions.open(owner, http, response));
        return ACCEPTED;
    }
}
