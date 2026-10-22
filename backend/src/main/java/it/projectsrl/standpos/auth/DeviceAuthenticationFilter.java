package it.projectsrl.standpos.auth;

import it.projectsrl.standpos.device.DeviceTokens;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the device token on every request, from the {@code SP_DEVICE} cookie (browsers) or a bearer token
 * (print agent), so a revoked device stops working at once.
 *
 * <ul>
 *   <li>The resolved device is exposed as {@link CurrentDevice} for the rest of the request.
 *   <li>A CASHIER session is valid only together with the token of the device it was opened on; otherwise the
 *       session is invalidated.
 *   <li>With no session principal, a valid device authenticates the request as a DEVICE principal (not stored).
 * </ul>
 */
public class DeviceAuthenticationFilter extends OncePerRequestFilter {

    private final AuthLookup lookup;
    private final Consumer<CurrentDevice> onSeen;
    private final SecurityContextHolderStrategy strategy = SecurityContextHolder.getContextHolderStrategy();

    public DeviceAuthenticationFilter(AuthLookup lookup, Consumer<CurrentDevice> onSeen) {
        this.lookup = lookup;
        this.onSeen = onSeen;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<CurrentDevice> device = token(request).map(DeviceTokens::hash).flatMap(lookup::findDevice);
        device.ifPresent(d -> {
            request.setAttribute(CurrentDevice.ATTRIBUTE, d);
            onSeen.accept(d);
        });

        Authentication authentication = strategy.getContext().getAuthentication();
        boolean anonymous = authentication == null || authentication instanceof AnonymousAuthenticationToken;
        if (!anonymous && authentication.getPrincipal() instanceof StandposPrincipal principal
                && principal.kind() == StandposPrincipal.Kind.CASHIER) {
            boolean sameDevice = device.map(d -> d.deviceId().equals(principal.deviceId())).orElse(false);
            if (!sameDevice) {
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                strategy.clearContext();
                anonymous = true;
            }
        }
        if (anonymous && device.isPresent()) {
            SecurityContext context = strategy.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    StandposPrincipal.device(device.get()), null, AuthorityUtils.createAuthorityList("ROLE_DEVICE")));
            strategy.setContext(context);
        }
        chain.doFilter(request, response);
    }

    private static Optional<String> token(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.of(header.substring(7).trim()).filter(t -> !t.isEmpty());
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (DeviceTokens.COOKIE.equals(cookie.getName()) && !cookie.getValue().isBlank()) {
                    return Optional.of(cookie.getValue());
                }
            }
        }
        return Optional.empty();
    }
}
