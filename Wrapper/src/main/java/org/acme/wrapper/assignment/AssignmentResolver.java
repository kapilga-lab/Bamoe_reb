package org.acme.wrapper.assignment;

import org.acme.wrapper.dto.StartWorkflowRequest;

/**
 * Resolves the task assignee for a start request according to its assignmentStrategy.
 */
public interface AssignmentResolver {

    /**
     * @param request       the start request (control fields incl. {@code workflowName} + variables).
     * @param authorization incoming Authorization header to forward to userDetails (may be null).
     * @return resolved variables to start with, or the candidate list for CHOICE.
     */
    AssignmentResult resolve(StartWorkflowRequest request, String authorization);
}
