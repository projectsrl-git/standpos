package it.projectsrl.standpos.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/** Opens and closes sessions (Spring Session JDBC behind the servlet HttpSession). */
@Component
public class SessionSupport {

    private final SecurityContextHolderStrategy strategy = SecurityContextHolder.getContextHolderStrategy();
    private final SecurityContextRepository repository = new HttpSessionSecurityContextRepository();

    public SecurityContextRepository repository() {
        return repository;
    }

    public void open(StandposPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
        if (request.getSession(false) != null) {
            request.changeSessionId(); // session fixation
        }
        String role = principal.kind() == StandposPrincipal.Kind.CASHIER ? "ROLE_CASHIER" : "ROLE_USER";
        var authorities = principal.platformAdmin()
                ? AuthorityUtils.createAuthorityList(role, "ROLE_PLATFORM_ADMIN")
                : AuthorityUtils.createAuthorityList(role);
        SecurityContext context = strategy.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities));
        strategy.setContext(context);
        repository.saveContext(context, request, response);
    }

    public void close(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        strategy.clearContext();
    }
}
