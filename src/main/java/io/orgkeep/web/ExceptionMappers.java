package io.orgkeep.web;

import io.orgkeep.api.exceptions.AlreadyExistsException;
import io.orgkeep.api.exceptions.InvalidRequestException;
import io.orgkeep.api.exceptions.OperationNotAllowedException;
import io.orgkeep.api.exceptions.ResourceNotFoundException;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * Turns the business exceptions of the api package into HTTP statuses.
 * Declared outside of any resource class, these @ServerExceptionMapper methods apply to all resources
 * (the Quarkus REST counterpart of a Spring @ControllerAdvice).
 */
public class ExceptionMappers {

    @ServerExceptionMapper
    public RestResponse<ErrorResponse> mapNotFound(ResourceNotFoundException e) {
        return RestResponse.status(Response.Status.NOT_FOUND, new ErrorResponse(e.getMessage()));
    }

    @ServerExceptionMapper
    public RestResponse<ErrorResponse> mapAlreadyExists(AlreadyExistsException e) {
        return RestResponse.status(Response.Status.CONFLICT, new ErrorResponse(e.getMessage()));
    }

    @ServerExceptionMapper
    public RestResponse<ErrorResponse> mapNotAllowed(OperationNotAllowedException e) {
        return RestResponse.status(Response.Status.CONFLICT, new ErrorResponse(e.getMessage()));
    }

    @ServerExceptionMapper
    public RestResponse<ErrorResponse> mapInvalidRequest(InvalidRequestException e) {
        return RestResponse.status(Response.Status.BAD_REQUEST, new ErrorResponse(e.getMessage()));
    }

    public record ErrorResponse(String message) { }
}
