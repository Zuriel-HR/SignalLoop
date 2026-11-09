package io.signalloop.central.api;

import io.signalloop.central.domain.ConflictException;
import io.signalloop.central.domain.NotFoundException;
import io.signalloop.central.domain.RejectedCommandException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

@Provider
public class GlobalExceptionMapper implements ExceptionMapper<Exception> {

    private static final Logger LOG = Logger.getLogger(GlobalExceptionMapper.class);

    @Override
    public Response toResponse(Exception exception) {
        return switch (exception) {
            case RejectedCommandException e -> Response.status(Response.Status.BAD_REQUEST)
                    .entity(ErrorResponse.invalid(e.violations()))
                    .build();
            case ConflictException e -> Response.status(Response.Status.CONFLICT)
                    .entity(ErrorResponse.of(e.code(), e.getMessage()))
                    .build();
            case NotFoundException e -> Response.status(Response.Status.NOT_FOUND)
                    .entity(ErrorResponse.of(e.code(), e.getMessage()))
                    .build();
            case WebApplicationException e -> clientResponse(e);
            default -> {
                LOG.error("Unhandled exception", exception);
                yield Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity(ErrorResponse.of("INTERNAL_ERROR", "Unexpected server error"))
                        .build();
            }
        };
    }

    private Response clientResponse(WebApplicationException exception) {
        Response response = exception.getResponse();
        if (response.getEntity() != null) {
            return response;
        }
        int status = response.getStatus();
        String code = status == 400 ? "BAD_REQUEST" : "HTTP_" + status;
        String message = status >= 500 ? "Unexpected server error" : "Request rejected";
        return Response.status(status).entity(ErrorResponse.of(code, message)).build();
    }
}
