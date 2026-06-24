package org.acme.wrapper.service;

import java.util.Map;

/**
 * Business operations for driving BAMOE workflows through the wrapper API.
 */
public interface WorkflowService {

    /**
     * Starts a new instance of the given workflow (process) id.
     *
     * @param workflowId deployed process id (e.g. {@code approval}) — dynamic.
     * @param variables  initial process variables; may be {@code null}.
     * @return the created process instance representation returned by the engine.
     */
    Object startWorkflow(String workflowId, Map<String, Object> variables);
}
