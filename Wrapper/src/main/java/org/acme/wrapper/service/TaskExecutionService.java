package org.acme.wrapper.service;

import org.acme.wrapper.dto.ExecuteTaskRequest;

/**
 * Unified workflow action: <b>start</b> a workflow when task coordinates
 * ({@code taskName}/{@code instanceId}/{@code taskId}) are absent, or <b>complete</b>
 * an existing task when they are present. In both cases the assignee is resolved by the
 * assignmentStrategy and assignment is required only when the next node is a human task.
 */
public interface TaskExecutionService {

    /**
     * @param authorization the incoming {@code Authorization: Bearer <jwt>} header (may be null).
     * @param request       control fields + task coordinates (optional) + variables.
     * @return the outcome (201 started / 200 completed / 200 CHOICE candidates).
     */
    ExecOutcome executeTask(String authorization, ExecuteTaskRequest request);
}
