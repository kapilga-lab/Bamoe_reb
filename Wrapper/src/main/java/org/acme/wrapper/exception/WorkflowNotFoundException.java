package org.acme.wrapper.exception;

/**
 * Thrown when the requested workflow id is not deployed in the engine.
 */
public class WorkflowNotFoundException extends RuntimeException {

    private final String workflowId;

    public WorkflowNotFoundException(String workflowId) {
        super("Invalid workflow '" + workflowId + "': workflow not present");
        this.workflowId = workflowId;
    }

    public String getWorkflowId() {
        return workflowId;
    }
}
