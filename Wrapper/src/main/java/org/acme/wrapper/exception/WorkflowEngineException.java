package org.acme.wrapper.exception;

import org.springframework.http.HttpStatusCode;

/**
 * Thrown when the engine is reachable but the request could not be completed
 * (engine error, rejected request, or the engine being unreachable).
 */
public class WorkflowEngineException extends RuntimeException {

    private final String workflowId;
    private final HttpStatusCode status;
    private final String code;

    public WorkflowEngineException(String workflowId, HttpStatusCode status, String code, String message) {
        super(message);
        this.workflowId = workflowId;
        this.status = status;
        this.code = code;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public HttpStatusCode getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
