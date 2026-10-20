package io.orgkeep.dashboard.core;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * Turns the failures of the calls to the core into answers of the dashboard API, always as {"message": "..."}:
 * the front reads that single shape. Same mechanism as ExceptionMappers in the core (a Spring @ControllerAdvice).
 */
@Slf4j
public class CoreErrorMappers {

    /** The core answered with an error: same status, so a 409 "already exists" stays a 409 for the front. */
    @ServerExceptionMapper
    public RestResponse<ErrorResponse> mapCoreError(CoreApiException e) {
        // an int, not Response.Status: the enum has no constant for some codes the core may send
        return RestResponse.ResponseBuilder.<ErrorResponse>create(e.getStatus())
                .entity(new ErrorResponse(e.getMessage()))
                .build();
    }

    /** The core did not answer at all (down, wrong URL): 502 Bad Gateway, the dashboard itself is fine. */
    @ServerExceptionMapper
    public RestResponse<ErrorResponse> mapCoreUnreachable(ProcessingException e) {
        log.warn("Core API unreachable: {}", e.toString());
        return RestResponse.status(Response.Status.BAD_GATEWAY, new ErrorResponse("The core API is unreachable"));
    }

    public record ErrorResponse(String message) { }
}
