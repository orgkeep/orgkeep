package io.orgkeep.dashboard.core;

import jakarta.ws.rs.core.Response;
import lombok.Getter;

import java.util.List;
import java.util.stream.Collectors;

/**
 * An error answer of the core: its HTTP status and a message the front can show as is.
 * Turned back into an HTTP answer of the dashboard by {@link CoreErrorMappers}.
 */
@Getter
public class CoreApiException extends RuntimeException {
    private final int status;

    public CoreApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    static CoreApiException from(Response response) {
        return new CoreApiException(response.getStatus(), messageOf(response));
    }

    /**
     * The core answers {"message": "..."} for its business errors (404, 409) and a Bean Validation report
     * {"violations": [{"field": "create.request.name", "message": "..."}]} for an invalid request (400).
     * Other answers (401, 403) have no body: the status is all we get.
     */
    private static String messageOf(Response response) {
        CoreError error;
        try {
            error = response.readEntity(CoreError.class);
        } catch (RuntimeException e) {
            error = null;
        }
        if (error != null && error.message() != null) {
            return error.message();
        }
        if (error != null && error.violations() != null && !error.violations().isEmpty()) {
            return error.violations().stream()
                    .map(violation -> fieldName(violation.field()) + ": " + violation.message())
                    .collect(Collectors.joining(", "));
        }
        return "The core answered " + response.getStatus();
    }

    // "create.request.name" (method.parameter.field) -> "name"
    private static String fieldName(String path) {
        return path == null ? "" : path.substring(path.lastIndexOf('.') + 1);
    }

    // Both shapes of error body of the core, unknown properties are ignored by Jackson (Quarkus default)
    record CoreError(String message, List<Violation> violations) { }

    record Violation(String field, String message) { }
}
