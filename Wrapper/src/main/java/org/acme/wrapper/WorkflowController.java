package org.acme.wrapper;

import org.acme.wrapper.dto.ExecuteTaskRequest;
import org.acme.wrapper.service.ExecOutcome;
import org.acme.wrapper.service.TaskExecutionService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin wrapper API on top of the BAMOE business service. A single endpoint both
 * <b>starts</b> a workflow (when task coordinates are absent) and <b>completes</b> a
 * task (when present); the assignee is resolved by the assignmentStrategy, required
 * only when the next node is a human task.
 */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private final TaskExecutionService taskExecutionService;

    public WorkflowController(TaskExecutionService taskExecutionService) {
        this.taskExecutionService = taskExecutionService;
    }

    /**
     * Start (no {@code instanceId}/{@code taskId}) or complete (both present) a task. The task
     * node name is derived server-side from {@code taskId}.
     *
     * @return 201 started, 200 completed, or 200 with candidate users for an unresolved CHOICE.
     */
    @PostMapping(value = "/executeTask", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> executeTask(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody ExecuteTaskRequest request) {
        ExecOutcome outcome = taskExecutionService.executeTask(authorization, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }
}
