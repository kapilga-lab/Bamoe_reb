package org.acme.wrapper.assignment.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import org.acme.security.jwt.UserContextHolder;
import org.acme.security.jwt.dto.UserDetailsJwt;
import org.acme.wrapper.assignment.AssignmentResolver;
import org.acme.wrapper.assignment.AssignmentResult;
import org.acme.wrapper.assignment.AssignmentResult.Choices;
import org.acme.wrapper.assignment.AssignmentResult.Resolved;
import org.acme.wrapper.assignment.AssignmentStrategy;
import org.acme.wrapper.assignment.LastAssignmentStore;
import org.acme.wrapper.client.UserDetailsClient;
import org.acme.wrapper.client.dto.UsersByGroupDto;
import org.acme.wrapper.dto.StartWorkflowRequest;
import org.acme.wrapper.exception.AssignmentValidationException;
import org.springframework.stereotype.Service;

@Service
public class AssignmentResolverImpl implements AssignmentResolver {

    private final UserDetailsClient userDetailsClient;
    private final LastAssignmentStore lastAssignmentStore;

    public AssignmentResolverImpl(UserDetailsClient userDetailsClient,
                                  LastAssignmentStore lastAssignmentStore) {
        this.userDetailsClient = userDetailsClient;
        this.lastAssignmentStore = lastAssignmentStore;
    }

    @Override
    public AssignmentResult resolve(StartWorkflowRequest request, String authorization, String taskName,
                                    String instanceId) {
        String workflowName = require(request.getWorkflowName(), "MISSING_WORKFLOW_NAME", "workflowName is mandatory");
        String task = require(taskName, "MISSING_TASK_NAME", "taskName is mandatory for assignment resolution");
        String field = require(request.getAssigneToFieldName(), "MISSING_ASSIGNE_FIELD", "assigneToFieldName is mandatory");
        require(request.getAssignmentStrategy(), "MISSING_ASSIGNMENT_STRATEGY", "assignmentStrategy is mandatory");
        AssignmentStrategy strategy = AssignmentStrategy.parse(request.getAssignmentStrategy());

        Map<String, Object> variables = new LinkedHashMap<>(request.getVariables());

        // LAST_ASSIGN_TO_*: reuse whoever had this task in THIS instance. At start there
        // is no instance yet, so this never matches and the base strategy applies.
        if (strategy.isLastAssign() && instanceId != null && !instanceId.isBlank()) {
            Optional<String> stored = lastAssignmentStore.findAssignee(workflowName, task, field, instanceId.trim());
            if (stored.isPresent() && !stored.get().isBlank()) {
                variables.put(field, stored.get());
                return new Resolved(variables);
            }
        }

        // Per-instance recording of resolved values happens centrally in the service
        // (it also covers start, where the instance id only exists after creation).
        return resolveBase(strategy.base(), task, field, variables, request, authorization);
    }

    @Override
    public List<UsersByGroupDto> listCandidates(StartWorkflowRequest request, String authorization) {
        return fetchCandidates(request, authorization);
    }

    private AssignmentResult resolveBase(AssignmentStrategy base, String taskName, String field,
                                         Map<String, Object> variables, StartWorkflowRequest request, String auth) {
        switch (base) {
            case USER, GROUP -> {
                Object value = variables.get(field);
                if (isBlank(value)) {
                    throw new AssignmentValidationException("MISSING_ASSIGNEE",
                            "'" + field + "' is required for strategy " + base);
                }
                return new Resolved(variables);
            }
            case SELF -> {
                String username = currentUsername();
                if (username == null) {
                    throw new AssignmentValidationException("NO_IDENTITY",
                            "No JWT user available for SELF strategy");
                }
                variables.put(field, username);
                return new Resolved(variables);
            }
            case RANDOM_SELECT -> {
                List<UsersByGroupDto> users = fetchCandidates(request, auth);
                UsersByGroupDto picked = users.get(ThreadLocalRandom.current().nextInt(users.size()));
                variables.put(field, picked.getUserName());
                return new Resolved(variables);
            }
            case ROUND_ROBIN -> {
                List<UsersByGroupDto> users = fetchCandidates(request, auth);
                List<String> sorted = users.stream()
                        .map(UsersByGroupDto::getUserName)
                        .filter(Objects::nonNull)
                        .distinct()
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .toList();
                String previous = lastAssignmentStore
                        .findAssignee(request.getWorkflowName(), taskName, field, LastAssignmentStore.GLOBAL_SCOPE)
                        .orElse(null);
                String next = nextInRotation(sorted, previous);
                variables.put(field, next);
                // Advance the workflow+task-wide rotation cursor.
                lastAssignmentStore.upsert(request.getWorkflowName(), taskName, field,
                        LastAssignmentStore.GLOBAL_SCOPE, next, currentUsername());
                return new Resolved(variables);
            }
            case TO_ALL_USER -> {
                List<UsersByGroupDto> users = fetchCandidates(request, auth);
                String all = users.stream()
                        .map(UsersByGroupDto::getUserName)
                        .filter(Objects::nonNull)
                        .collect(Collectors.joining(","));
                variables.put(field, all);
                return new Resolved(variables);
            }
            case CHOICE -> {
                List<UsersByGroupDto> users = fetchCandidates(request, auth);
                Object chosen = variables.get(field);
                if (isBlank(chosen)) {
                    // No selection yet -> return candidates, do not start.
                    return new Choices(users);
                }
                Set<String> allowed = users.stream()
                        .map(UsersByGroupDto::getUserName)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
                List<String> requested = splitCsv(chosen.toString());
                List<String> invalid = requested.stream().filter(u -> !allowed.contains(u)).toList();
                if (!invalid.isEmpty()) {
                    throw new AssignmentValidationException("INVALID_CHOICE",
                            "These users are not valid candidates for this task: " + invalid);
                }
                variables.put(field, String.join(",", requested));
                return new Resolved(variables);
            }
            default -> throw new IllegalStateException("Unexpected base strategy: " + base);
        }
    }

    private List<UsersByGroupDto> fetchCandidates(StartWorkflowRequest request, String auth) {
        boolean hasGroup = request.getGroupName() != null && !request.getGroupName().isEmpty();
        boolean hasRole = request.getRoleNames() != null && !request.getRoleNames().isEmpty();
        if (!hasGroup && !hasRole) {
            throw new AssignmentValidationException("MISSING_FILTER",
                    "At least one of groupName or roleNames is required for this strategy");
        }
        List<UsersByGroupDto> users = userDetailsClient.fetchUsers(request.getGroupName(), request.getRoleNames(), auth);
        // No task may be assigned from nothing: null/empty responses and entries without a
        // usable userName all count as "no user found".
        List<UsersByGroupDto> valid = (users == null) ? List.of() : users.stream()
                .filter(u -> u != null && u.getUserName() != null && !u.getUserName().isBlank())
                .toList();
        if (valid.isEmpty()) {
            throw new AssignmentValidationException("NO_USER_FOUND",
                    "No user found from userDetails for the given groupName/roleNames filter");
        }
        return valid;
    }

    /**
     * The user after {@code previous} in alphabetical rotation: first user when there is
     * no history; wrap-around at the end; when {@code previous} is no longer a candidate,
     * the first user alphabetically after it (or the first overall).
     */
    private static String nextInRotation(List<String> sorted, String previous) {
        if (previous == null || previous.isBlank()) {
            return sorted.get(0);
        }
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).equalsIgnoreCase(previous.trim())) {
                return sorted.get((i + 1) % sorted.size());
            }
        }
        for (String candidate : sorted) {
            if (String.CASE_INSENSITIVE_ORDER.compare(candidate, previous.trim()) > 0) {
                return candidate;
            }
        }
        return sorted.get(0);
    }

    private String currentUsername() {
        UserDetailsJwt ctx = UserContextHolder.getContext();
        return (ctx != null) ? ctx.getUsername() : null;
    }

    private static String require(String value, String code, String message) {
        if (value == null || value.isBlank()) {
            throw new AssignmentValidationException(code, message);
        }
        return value.trim();
    }

    private static boolean isBlank(Object value) {
        return value == null || value.toString().isBlank();
    }

    private static List<String> splitCsv(String csv) {
        List<String> out = new ArrayList<>();
        for (String part : csv.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }
}
