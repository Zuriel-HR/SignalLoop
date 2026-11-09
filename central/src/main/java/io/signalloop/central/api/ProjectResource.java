package io.signalloop.central.api;

import io.signalloop.central.application.Commands.ProblemSpecCommand;
import io.signalloop.central.application.Commands.ProjectCommand;
import io.signalloop.central.application.ProjectService;
import io.signalloop.central.domain.Records.ProblemSpec;
import io.signalloop.central.domain.Records.Project;
import io.signalloop.central.domain.RejectedCommandException;
import io.signalloop.central.domain.FieldViolation;
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

@Path("/api/v1/projects")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ProjectResource {

    private final ProjectService projects;

    public ProjectResource(ProjectService projects) {
        this.projects = projects;
    }

    @POST
    public Response create(ProjectCommand body) {
        requireBody(body);
        Project project = projects.create(body);
        return Response.status(Response.Status.CREATED).entity(project).build();
    }

    @GET
    @Path("/{id}")
    public Project get(@PathParam("id") UUID id) {
        return projects.get(id);
    }

    @POST
    @Path("/{id}/problem-specs")
    public Response addProblem(@PathParam("id") UUID id, ProblemSpecCommand body) {
        requireBody(body);
        ProblemSpec spec = projects.addProblem(id, body);
        return Response.status(Response.Status.CREATED).entity(spec).build();
    }

    private static void requireBody(Object body) {
        if (body == null) {
            throw new RejectedCommandException(List.of(new FieldViolation("body", "required")));
        }
    }
}
