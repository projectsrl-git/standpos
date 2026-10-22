package it.projectsrl.standpos.auth;

import it.projectsrl.standpos.common.ApiException;
import it.projectsrl.standpos.tenant.TenantContext;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class AuthController {

    record CsrfResponse(String headerName, String token) {}

    record LoginRequest(@NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 200) String password) {}

    record PinRequest(@NotNull UUID userId, @NotBlank @Pattern(regexp = "\\d{4,6}") String pin) {}

    private final LoginService logins;
    private final PinLoginService pins;
    private final SessionSupport sessions;
    private final MeService me;

    AuthController(LoginService logins, PinLoginService pins, SessionSupport sessions, MeService me) {
        this.logins = logins;
        this.pins = pins;
        this.sessions = sessions;
        this.me = me;
    }

    /** Lets the SPA obtain the CSRF token (and cookie) before its first state-changing request. */
    @GetMapping("/auth/csrf")
    @PermitAll
    CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }

    @PostMapping("/auth/login")
    @PermitAll
    MeService.Me login(@Valid @RequestBody LoginRequest request, HttpServletRequest http, HttpServletResponse response) {
        StandposPrincipal principal = logins.login(request.email(), request.password(), http.getRemoteAddr());
        sessions.open(principal, http, response);
        return describe(principal, http);
    }

    @PostMapping("/auth/pin")
    @PreAuthorize("@authz.isRegisterDevice()")
    MeService.Me pinLogin(@Valid @RequestBody PinRequest request, HttpServletRequest http, HttpServletResponse response) {
        CurrentDevice device = CurrentDevice.of(http).orElseThrow();
        StandposPrincipal principal = pins.login(device, request.userId(), request.pin());
        sessions.open(principal, http, response);
        return describe(principal, http);
    }

    @PostMapping("/auth/logout")
    @PermitAll
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(HttpServletRequest http) {
        sessions.close(http);
    }

    @GetMapping("/auth/me")
    @PreAuthorize("isAuthenticated()")
    MeService.Me me(HttpServletRequest http) {
        StandposPrincipal principal = Authz.principal()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", "Not logged in"));
        return me.describe(principal, CurrentDevice.of(http).orElse(null));
    }

    /** Users who may log in with a PIN on this register. */
    @GetMapping("/register/cashiers")
    @PreAuthorize("@authz.isRegisterDevice()")
    List<PinLoginService.Cashier> cashiers(HttpServletRequest http) {
        return pins.cashiers(CurrentDevice.of(http).orElseThrow());
    }

    private MeService.Me describe(StandposPrincipal principal, HttpServletRequest http) {
        return TenantContext.callAs(principal.tenantId(),
                () -> me.describe(principal, CurrentDevice.of(http).orElse(null)));
    }
}
