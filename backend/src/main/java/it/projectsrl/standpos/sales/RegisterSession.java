package it.projectsrl.standpos.sales;

import it.projectsrl.standpos.auth.Authz;
import it.projectsrl.standpos.auth.CurrentDevice;
import it.projectsrl.standpos.auth.StandposPrincipal;
import it.projectsrl.standpos.common.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** Who is selling where: the cashier logged in with a PIN and the register (device) they are bound to. */
public record RegisterSession(UUID venueId, UUID deviceId, UUID cashierId, CurrentDevice device) {

    /** The current request's cashier session; {@code @authz.isCashier()} has already checked it. */
    public static RegisterSession current() {
        StandposPrincipal principal = Authz.requirePrincipal();
        if (principal.kind() != StandposPrincipal.Kind.CASHIER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "auth.register_required", "A cashier session is required");
        }
        CurrentDevice device = CurrentDevice.current().orElseThrow(() -> new ApiException(
                HttpStatus.FORBIDDEN, "auth.register_required", "Only an enrolled register may do this"));
        return new RegisterSession(principal.venueId(), principal.deviceId(), principal.userId(), device);
    }
}
