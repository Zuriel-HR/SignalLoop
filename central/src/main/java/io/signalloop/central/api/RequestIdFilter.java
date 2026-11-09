package io.signalloop.central.api;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

import java.util.UUID;

@Provider
public class RequestIdFilter implements ContainerRequestFilter, ContainerResponseFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String PROPERTY = "signalloop.requestId";

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String incoming = requestContext.getHeaderString(HEADER);
        String requestId = incoming == null || incoming.isBlank() || incoming.length() > 80
                ? UUID.randomUUID().toString()
                : incoming.trim();
        requestContext.setProperty(PROPERTY, requestId);
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        Object requestId = requestContext.getProperty(PROPERTY);
        if (requestId != null) {
            responseContext.getHeaders().putSingle(HEADER, requestId.toString());
        }
    }
}
