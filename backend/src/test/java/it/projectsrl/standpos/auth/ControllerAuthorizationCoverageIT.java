package it.projectsrl.standpos.auth;

import static org.assertj.core.api.Assertions.assertThat;

import it.projectsrl.standpos.support.IntegrationTest;
import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Acceptance criterion 5: every controller method of the application carries an explicit authorisation rule, and the
 * methods open to anonymous callers are exactly the public API listed in {@link SecurityConfig}.
 */
class ControllerAuthorizationCoverageIT extends IntegrationTest {

    private static final List<Class<? extends Annotation>> RULES = List.of(
            PreAuthorize.class, PostAuthorize.class, Secured.class, RolesAllowed.class, PermitAll.class, DenyAll.class);

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    @Test
    void everyControllerMethodHasAnAuthorisationRule() {
        Map<RequestMappingInfo, HandlerMethod> handlers = mapping.getHandlerMethods();
        List<HandlerMethod> ours = handlers.values().stream()
                .filter(h -> h.getBeanType().getPackageName().startsWith("it.projectsrl.standpos"))
                .toList();
        assertThat(ours).hasSizeGreaterThan(20);
        assertThat(ours).allSatisfy(h -> assertThat(RULES.stream().anyMatch(rule -> hasRule(h, rule)))
                .as("%s.%s has no authorisation rule", h.getBeanType().getSimpleName(), h.getMethod().getName())
                .isTrue());
    }

    @Test
    void permitAllIsLimitedToThePublicApi() {
        Set<String> permitted = new TreeSet<>();
        mapping.getHandlerMethods().forEach((info, handler) -> {
            if (hasRule(handler, PermitAll.class)) {
                permitted.addAll(info.getPatternValues());
            }
        });
        assertThat(permitted).containsExactlyInAnyOrder(SecurityConfig.PUBLIC_API);
    }

    private static boolean hasRule(HandlerMethod handler, Class<? extends Annotation> rule) {
        return AnnotatedElementUtils.hasAnnotation(handler.getMethod(), rule)
                || AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), rule);
    }
}
