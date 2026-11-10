package io.signalloop.central.api;

import io.signalloop.central.application.LearningCycleService;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;
import java.util.UUID;

@Path("/api/v1/scenarios")
@Produces(MediaType.APPLICATION_JSON)
public class LearningCycleResource {
    private final LearningCycleService learning;

    public LearningCycleResource(LearningCycleService learning) {
        this.learning = learning;
    }

    @POST
    @Path("/{id}/learning-cycle")
    public Map<String, Object> run(@PathParam("id") UUID scenarioId, @QueryParam("source") String source) {
        return learning.run(scenarioId, source);
    }
}
