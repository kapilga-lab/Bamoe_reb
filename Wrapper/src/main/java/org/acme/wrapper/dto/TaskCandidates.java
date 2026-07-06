package org.acme.wrapper.dto;

import java.util.List;

import org.acme.wrapper.client.dto.UsersByGroupDto;

/**
 * One entry of an array {@code /executeTask} response: the CHOICE candidate users for a
 * single (parallel) task, labeled with the task's name.
 *
 * <p>{@code taskId} is populated for complete-mode entries (derived from the running task)
 * and {@code null} for start-mode entries (no instance yet). {@code fieldName} is the
 * derived assignee variable the task fills (e.g. {@code reviewerBActors}).
 */
public record TaskCandidates(String taskId, String taskName, String fieldName, List<UsersByGroupDto> users) {
}
