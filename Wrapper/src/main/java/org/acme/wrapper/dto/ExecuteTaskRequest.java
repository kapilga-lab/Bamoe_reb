package org.acme.wrapper.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * Body for the executeTask endpoint. Inherits the control fields and the
 * {@code @JsonAnySetter} variables map from {@link StartWorkflowRequest}, and adds the
 * task coordinates. Inheriting StartWorkflowRequest lets the same
 * {@code AssignmentResolver} resolve the assignee field.
 *
 * <p>The task node name is not supplied by the caller — it is derived server-side from
 * {@code taskId} by listing the instance's active tasks.
 */
@Getter
@Setter
public class ExecuteTaskRequest extends StartWorkflowRequest {

    /**
     * Start-array mode only: names the parallel first-task this item targets (e.g.
     * {@code Reviewer A}). Ignored in complete mode, where the name is derived from
     * {@code taskId}, and unused by single-object requests.
     */
    private String taskName;

    /** Mandatory (complete mode). The process instance id. */
    private String instanceId;

    /** Mandatory (complete mode). The user task (work item) id. */
    private String taskId;

    /** Optional. Lifecycle phase; defaults to {@code complete}. */
    private String phase;
}
