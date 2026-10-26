package it.projectsrl.standpos.common;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * An error returned to the client as an RFC 9457 problem detail. {@code code} is a stable key the frontend
 * translates (for example {@code auth.invalid_credentials}); the message is English text for developers.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public ApiException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** Extra members of the problem detail, for example the products that ran out. */
    public Map<String, Object> properties() {
        return properties;
    }

    public ApiException with(String name, Object value) {
        properties.put(name, value);
        return this;
    }

    public static ApiException notFound(String entity) {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", entity + " not found");
    }

    public static ApiException conflict(String code, String detail) {
        return new ApiException(HttpStatus.CONFLICT, code, detail);
    }

    public static ApiException badRequest(String code, String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, detail);
    }
}
