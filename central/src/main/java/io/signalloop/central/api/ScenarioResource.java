package io.signalloop.central.api;

import io.signalloop.central.application.Commands.AtmCommand;
import io.signalloop.central.application.Commands.LogCommand;
import io.signalloop.central.application.Commands.ScenarioCommand;
import io.signalloop.central.application.Commands.TicketCommand;
import io.signalloop.central.application.SequenceService;
import io.signalloop.central.domain.FieldViolation;
import io.signalloop.central.domain.Records.Atm;
import io.signalloop.central.domain.Records.Scenario;
import io.signalloop.central.domain.Records.SignalLog;
import io.signalloop.central.domain.Records.Ticket;
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

@Path("/api/v1/scenarios")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ScenarioResource {

    private final SequenceService sequences;

    public ScenarioResource(SequenceService sequences) {
        this.sequences = sequences;
    }

    @POST
    public Response create(ScenarioCommand body) {
        requireBody(body);
        Scenario scenario = sequences.createScenario(body);
        return Response.status(Response.Status.CREATED).entity(scenario).build();
    }

    @GET
    @Path("/{id}")
    public Scenario get(@PathParam("id") UUID id) {
        return sequences.getScenario(id);
    }

    @POST
    @Path("/{id}/atms")
    public Response addAtm(@PathParam("id") UUID id, AtmCommand body) {
        requireBody(body);
        Atm atm = sequences.addAtm(id, body);
        return Response.status(Response.Status.CREATED).entity(atm).build();
    }

    @POST
    @Path("/{id}/tickets")
    public Response addTicket(@PathParam("id") UUID id, TicketCommand body) {
        requireBody(body);
        Ticket ticket = sequences.addTicket(id, body);
        return Response.status(Response.Status.CREATED).entity(ticket).build();
    }

    @POST
    @Path("/{id}/logs")
    public Response appendLog(@PathParam("id") UUID id, LogCommand body) {
        requireBody(body);
        SignalLog log = sequences.appendLog(id, body);
        return Response.status(Response.Status.CREATED).entity(log).build();
    }

    private static void requireBody(Object body) {
        if (body == null) {
            throw new RejectedCommandException(List.of(new FieldViolation("body", "required")));
        }
    }
}
