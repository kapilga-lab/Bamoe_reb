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
    public AssignmentResult resolve(StartWorkflowRequest request, String authorization) {
        String workflowName = require(request.getWorkflowName(), "MISSING_WORKFLOW_NAME", "workflowName is mandatory");
        String field = require(request.getAssigneToFieldName(), "MISSING_ASSIGNE_FIELD", "assigneToFieldName is mandatory");
        require(request.getAssignmentStrategy(), "MISSING_ASSIGNMENT_STRATEGY", "assignmentStrategy is mandatory");
        AssignmentStrategy strategy = AssignmentStrategy.parse(request.getAssignmentStrategy());

        Map<String, Object> variables = new LinkedHashMap<>(request.getVariables());

        // LAST_ASSIGN_TO_*: reuse the stored assignee if we have one.
        if (strategy.isLastAssign()) {
            Optional<String> stored = lastAssignmentStore.findAssignee(workflowName, field);
            if (stored.isPresent() && !stored.get().isBlank()) {
                variables.put(field, stored.get());
                lastAssignmentStore.upsert(workflowName, field, stored.get(), currentUsername()); // refresh
                return new Resolved(variables);
            }
        }

        AssignmentResult result = resolveBase(strategy.base(), field, variables, request, authorization);

        // Remember the resolved value for next time (LAST_* strategies only).
        if (strategy.isLastAssign() && result instanceof Resolved resolved) {
            Object value = resolved.variables().get(field);
            if (value != null) {
                lastAssignmentStore.upsert(workflowName, field, value.toString(), currentUsername());
            }
        }
        return result;
    }

    @Override
    public List<UsersByGroupDto> listCandidates(StartWorkflowRequest request, String authorization) {
        return fetchCandidates(request, authorization);
    }

    private AssignmentResult resolveBase(AssignmentStrategy base, String field,
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
        if (users.isEmpty()) {
            throw new AssignmentValidationException("NO_USER_FOUND",
                    "No user found from userDetails for the given groupName/roleNames filter");
        }
        return users;
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
