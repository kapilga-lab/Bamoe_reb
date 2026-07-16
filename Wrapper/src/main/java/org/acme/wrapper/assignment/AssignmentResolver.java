package org.acme.wrapper.assignment;

import java.util.List;

import org.acme.wrapper.client.dto.UsersByGroupDto;
import org.acme.wrapper.dto.StartWorkflowRequest;

/**
 * Resolves the task assignee for a start request according to its assignmentStrategy.
 */
public interface AssignmentResolver {

    /**
     * @param request       the start request (control fields incl. {@code workflowName} + variables).
     * @param authorization incoming Authorization header to forward to userDetails (may be null).
     * @param taskName      the human task being assigned.
     * @param instanceId    the process instance being acted on, or null at start. LAST_ASSIGN
     *                      reuse is per workflowName + taskName + instanceId (so it never
     *                      matches at start); the ROUND_ROBIN cursor is per
     *                      workflowName + taskName across all instances.
     * @return resolved variables to start with, or the candidate list for CHOICE.
     */
    AssignmentResult resolve(StartWorkflowRequest request, String authorization, String taskName, String instanceId);

    /**
     * Fetch the candidate users for a task straight from userDetails using the request's
     * {@code groupName}/{@code roleNames} filter — the CHOICE candidate list, without any
     * strategy resolution. Enforces the same filter/empty checks as CHOICE.
     *
     * @return the candidate users (never empty; throws if none match).
     */
    List<UsersByGroupDto> listCandidates(StartWorkflowRequest request, String authorization);
}
