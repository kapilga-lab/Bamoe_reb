package org.acme.wrapper.assignment;

import java.util.List;
import java.util.Map;

import org.acme.wrapper.client.dto.UsersByGroupDto;

/**
 * Outcome of assignment resolution: either the process is ready to start with the
 * resolved variables, or (CHOICE without a selection) the candidate users are returned.
 */
public sealed interface AssignmentResult {

    /** Variables (with the assignee field populated) — proceed to start the workflow. */
    record Resolved(Map<String, Object> variables) implements AssignmentResult {
    }

    /** Candidate users for CHOICE — do NOT start; let the caller pick. */
    record Choices(List<UsersByGroupDto> users) implements AssignmentResult {
    }
}
