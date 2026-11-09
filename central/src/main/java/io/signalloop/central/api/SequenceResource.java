package io.signalloop.central.api;

import io.signalloop.central.application.Commands.OutcomeCommand;
import io.signalloop.central.application.SequenceService;
import io.signalloop.central.domain.FieldViolation;
import io.signalloop.central.domain.Records.ExpectedOutcome;
import io.signalloop.central.domain.Records.SignalLog;
import io.signalloop.central.domain.RejectedCommandException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.UUID;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SequenceResource {

    private final SequenceService sequences;

    public SequenceResource(SequenceService sequences) {
        this.sequences = sequences;
    }

    @GET
    @Path("/api/v1/atms/{atmId}/logs")
    public List<SignalLog> sequence(@PathParam("atmId") UUID atmId) {
        return sequences.sequence(atmId);
    }

    @POST
    @Path("/api/v1/tickets/{ticketId}/expected-outcome")
    public Response outcome(@PathParam("ticketId") UUID ticketId, OutcomeCommand body) {
        if (body == null) {
            throw new RejectedCommandException(List.of(new FieldViolation("body", "required")));
        }
        ExpectedOutcome outcome = sequences.addOutcome(ticketId, body);
        return Response.status(Response.Status.CREATED).entity(outcome).build();
    }
}
