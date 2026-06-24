package org.acme.wrapper.service.impl;

import java.util.Map;

import org.acme.wrapper.exception.WorkflowEngineException;
import org.acme.wrapper.exception.WorkflowNotFoundException;
import org.acme.wrapper.service.WorkflowService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Default {@link WorkflowService} implementation. Forwards requests to the BAMOE
 * engine's generated REST endpoints over HTTP and translates transport-level
 * failures into wrapper domain exceptions.
 */
@Service
public class WorkflowServiceImpl implements WorkflowService {

    private final RestClient engine;

    public WorkflowServiceImpl(
            // Defaults to this same service; override with wrapper.engine.base-url if the
            // engine runs elsewhere.
            @Value("${wrapper.engine.base-url:http://${server.address:localhost}:${server.port:8080}${server.servlet.context-path:}}")
            String engineBaseUrl) {
        this.engine = RestClient.builder().baseUrl(engineBaseUrl).build();
    }

    @Override
    public Object startWorkflow(String workflowId, Map<String, Object> variables) {
        Map<String, Object> payload = (variables == null) ? Map.of() : variables;

        try {
            return engine.post()
                    .uri("/{workflowId}", workflowId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(Object.class);

        } catch (HttpClientErrorException.NotFound nf) {
            // No start endpoint for this id -> the workflow does not exist.
            throw new WorkflowNotFoundException(workflowId);

        } catch (HttpClientErrorException ce) {
            // Other client-side problems (e.g. malformed variables) — forward the status.
            throw new WorkflowEngineException(workflowId, ce.getStatusCode(),
                    "ENGINE_REJECTED_REQUEST", ce.getResponseBodyAsString());

        } catch (HttpServerErrorException se) {
            throw new WorkflowEngineException(workflowId, HttpStatus.BAD_GATEWAY,
                    "ENGINE_ERROR", "The workflow engine returned an error: " + se.getResponseBodyAsString());

        } catch (ResourceAccessException unreachable) {
            throw new WorkflowEngineException(workflowId, HttpStatus.SERVICE_UNAVAILABLE,
                    "ENGINE_UNREACHABLE",
                    "Workflow engine is not reachable. Check that the BAMOE service is running.");
        }
    }
}
