package org.acme.wrapper;

import java.util.List;

import org.acme.wrapper.dto.ExecuteTaskRequest;
import org.acme.wrapper.service.ExecOutcome;
import org.acme.wrapper.service.TaskExecutionService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Thin wrapper API on top of the BAMOE business service. A single endpoint both
 * <b>starts</b> a workflow (when task coordinates are absent) and <b>completes</b> a
 * task (when present); the assignee is resolved by the assignmentStrategy, required
 * only when the next node is a human task.
 */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private static final TypeReference<List<ExecuteTaskRequest>> ITEM_LIST = new TypeReference<>() {
    };

    private final TaskExecutionService taskExecutionService;
    private final ObjectMapper objectMapper;

    public WorkflowController(TaskExecutionService taskExecutionService, ObjectMapper objectMapper) {
        this.taskExecutionService = taskExecutionService;
        this.objectMapper = objectMapper;
    }

    /**
     * Single object → start (no {@code instanceId}/{@code taskId}) or complete (both present) a
     * task; the node name is derived from {@code taskId}. Array → parallel human tasks: assign
     * each first-task at start, or list a CHOICE candidate set per active task at complete.
     *
     * @return 201 started, 200 completed, or 200 with candidate users (single) / a per-task
     *         candidate array (array) for an unresolved CHOICE.
     */
    @PostMapping(value = "/executeTask", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> executeTask(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody JsonNode body) {
        ExecOutcome outcome;
        if (body.isArray()) {
            List<ExecuteTaskRequest> items = objectMapper.convertValue(body, ITEM_LIST);
            outcome = taskExecutionService.executeTaskBatch(authorization, items);
        } else {
            ExecuteTaskRequest request = objectMapper.convertValue(body, ExecuteTaskRequest.class);
            outcome = taskExecutionService.executeTask(authorization, request);
        }
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * Roll a workflow back to a human task: the last completed one (default) or an explicit
     * {@code taskName}. Works on live instances (steered in place) and ended instances
     * (re-created from their final variables under a new instanceId).
     */
    @PostMapping(value = "/rollback", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> rollback(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody ExecuteTaskRequest request) {
        ExecOutcome outcome = taskExecutionService.rollback(authorization, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * Full status of an instance by id alone: ACTIVE/COMPLETED/ABORTED, timestamps, the
     * active human tasks (state, actual owner, candidates) and the completed task history.
     */
    @GetMapping(value = "/{instanceId}/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> instanceStatus(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable("instanceId") String instanceId) {
        ExecOutcome outcome = taskExecutionService.instanceStatus(authorization, instanceId);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }
}
