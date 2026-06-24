package org.acme.wrapper;

import java.util.Map;

import org.acme.wrapper.service.WorkflowService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin wrapper API on top of the BAMOE business service.
 *
 * <p>Exposes a generic "start" endpoint where the workflow (process) id is dynamic.
 * All engine interaction and error handling live in {@link WorkflowService} and the
 * exception handler — this controller only adapts HTTP to the service call.</p>
 */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private final WorkflowService workflowService;

    public WorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    /**
     * Starts a workflow instance.
     *
     * @param workflowId the deployed process id (e.g. {@code approval}) — dynamic.
     * @param variables  process variables to seed the instance (may be empty/absent).
     */
    @PostMapping(value = "/{workflowId}/start", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> start(@PathVariable("workflowId") String workflowId,
                                        @RequestBody(required = false) Map<String, Object> variables) {
        Object created = workflowService.startWorkflow(workflowId, variables);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
