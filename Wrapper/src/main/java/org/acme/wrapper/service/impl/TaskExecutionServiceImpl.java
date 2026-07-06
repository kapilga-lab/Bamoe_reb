package org.acme.wrapper.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.acme.security.jwt.UserContextHolder;
import org.acme.security.jwt.dto.UserDetailsJwt;
import org.acme.security.jwt.dto.UserGroupDTO;
import org.acme.wrapper.assignment.AssignmentResolver;
import org.acme.wrapper.assignment.AssignmentResult;
import org.acme.wrapper.dto.ExecuteTaskRequest;
import org.acme.wrapper.exception.AssignmentValidationException;
import org.acme.wrapper.exception.UnauthorizedException;
import org.acme.wrapper.exception.WorkflowEngineException;
import org.acme.wrapper.exception.WorkflowNotFoundException;
import org.acme.wrapper.graph.NextNode;
import org.acme.wrapper.graph.NextNodeType;
import org.acme.wrapper.graph.ProcessGraphService;
import org.acme.wrapper.service.ExecOutcome;
import org.acme.wrapper.service.TaskExecutionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Service
public class TaskExecutionServiceImpl implements TaskExecutionService {

    private static final String DEFAULT_PHASE = "complete";
    private static final String TASK_PATH = "/{workflowName}/{instanceId}/{taskName}/{taskId}";

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {
            };

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_OF_MAP =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient engine;
    private final AssignmentResolver assignmentResolver;
    private final ProcessGraphService processGraphService;

    public TaskExecutionServiceImpl(
            @Value("${wrapper.engine.base-url:http://${server.address:localhost}:${server.port:8080}${server.servlet.context-path:}}")
            String engineBaseUrl,
            AssignmentResolver assignmentResolver,
            ProcessGraphService processGraphService) {
        this.engine = RestClient.builder().baseUrl(engineBaseUrl).build();
        this.assignmentResolver = assignmentResolver;
        this.processGraphService = processGraphService;
    }

    @Override
    public ExecOutcome executeTask(String authorization, ExecuteTaskRequest request) {
        UserDetailsJwt jwtUser = requireJwtUser();
        String workflowName = require(request.getWorkflowName(), "MISSING_WORKFLOW_NAME", "workflowName is mandatory");
        String user = jwtUser.getUsername();
        List<String> groups = groupNames(jwtUser);

        int coords = (notBlank(request.getInstanceId()) ? 1 : 0)
                + (notBlank(request.getTaskId()) ? 1 : 0);

        if (coords == 0) {
            return startWorkflow(workflowName, request, authorization);
        }
        if (coords != 2) {
            throw new AssignmentValidationException("INVALID_TASK_COORDINATES",
                    "Provide both instanceId and taskId to complete a task, or neither to start.");
        }
        return completeTaskFlow(workflowName, request, authorization, user, groups);
    }

    // ---------------------------------------------------------------- START mode

    private ExecOutcome startWorkflow(String workflowName, ExecuteTaskRequest request, String auth) {
        NextNode next = processGraphService.firstNode(workflowName, request.getVariables());

        // The first task's own Actors/Groups #{...} vars are seeded as start process
        // variables, so all are settable.
        Set<String> settableActors = Set.of();
        Set<String> settableGroups = Set.of();
        Set<String> inputs = Set.of();
        if (next.type() == NextNodeType.HUMAN && next.nodeName() != null) {
            settableActors = processGraphService.actorsVars(workflowName, next.nodeName());
            settableGroups = processGraphService.groupsVars(workflowName, next.nodeName());
            inputs = fetchTaskInputSchema(workflowName, next.nodeName(), auth);
        }
        requirePresent(inputs, union(settableActors, settableGroups), request);

        Map<String, Object> variables = new LinkedHashMap<>(request.getVariables());
        ExecOutcome choice = resolveAssignment(request, next, auth, settableActors, settableGroups, variables);
        if (choice != null) {
            return choice;
        }

        Object created = startInstance(workflowName, auth, variables);
        return new ExecOutcome(HttpStatus.CREATED, created);
    }

    private Object startInstance(String workflowName, String auth, Map<String, Object> variables) {
        try {
            var spec = engine.post().uri("/{workflowName}", workflowName).contentType(MediaType.APPLICATION_JSON);
            if (auth != null && !auth.isBlank()) {
                spec.header(HttpHeaders.AUTHORIZATION, auth);
            }
            return spec.body(variables).retrieve().body(Object.class);
        } catch (HttpClientErrorException.NotFound nf) {
            throw new WorkflowNotFoundException(workflowName);
        } catch (HttpClientErrorException ce) {
            throw new WorkflowEngineException(workflowName, ce.getStatusCode(),
                    "ENGINE_REJECTED_REQUEST", ce.getResponseBodyAsString());
        } catch (HttpServerErrorException se) {
            throw new WorkflowEngineException(workflowName, HttpStatus.BAD_GATEWAY,
                    "ENGINE_ERROR", "The workflow engine returned an error: " + se.getResponseBodyAsString());
        } catch (ResourceAccessException unreachable) {
            throw new WorkflowEngineException(workflowName, HttpStatus.SERVICE_UNAVAILABLE,
                    "ENGINE_UNREACHABLE", "Workflow engine is not reachable.");
        }
    }

    // ------------------------------------------------------------- COMPLETE mode

    private ExecOutcome completeTaskFlow(String workflowName, ExecuteTaskRequest request, String auth,
                                         String user, List<String> groups) {
        String instanceId = request.getInstanceId().trim();
        String taskId = request.getTaskId().trim();
        String taskName = fetchTaskNameById(workflowName, instanceId, taskId, user, groups, auth);

        Map<String, Object> resultsTemplate = fetchTaskResults(workflowName, instanceId, taskName, taskId, user, groups, auth);

        Map<String, Object> mergedVars = new LinkedHashMap<>(fetchProcessVariables(workflowName, instanceId, auth));
        mergedVars.putAll(request.getVariables());
        NextNode next = processGraphService.nextAfterTask(workflowName, taskName, mergedVars);

        // The next human task's Actors/Groups vars that this task can actually set
        // (must be an output of the task being completed).
        Set<String> settableActors = Set.of();
        Set<String> settableGroups = Set.of();
        if (next.type() == NextNodeType.HUMAN && next.nodeName() != null) {
            settableActors = intersect(processGraphService.actorsVars(workflowName, next.nodeName()), resultsTemplate.keySet());
            settableGroups = intersect(processGraphService.groupsVars(workflowName, next.nodeName()), resultsTemplate.keySet());
        }
        requirePresent(resultsTemplate.keySet(), union(settableActors, settableGroups), request);

        Map<String, Object> accumulated = new LinkedHashMap<>(request.getVariables());
        ExecOutcome choice = resolveAssignment(request, next, auth, settableActors, settableGroups, accumulated);
        if (choice != null) {
            return choice;
        }
        Map<String, Object> output = pick(resultsTemplate.keySet(), accumulated);

        String phase = (request.getPhase() == null || request.getPhase().isBlank())
                ? DEFAULT_PHASE : request.getPhase().trim();
        Object body = completeTask(workflowName, instanceId, taskName, taskId, phase, user, groups, auth, output);
        return new ExecOutcome(HttpStatus.OK, body);
    }

    // --------------------------------------------------------------- assignment

    /**
     * Apply assignment into {@code out}: Groups vars are set directly from
     * {@code assignToGroups}; Actors vars are resolved by {@code assignmentStrategy}
     * (seeded from {@code assignToActors} for USER/GROUP/CHOICE). Returns a non-null
     * {@link ExecOutcome} (200 candidate list) if a CHOICE strategy has no selection.
     */
    private ExecOutcome resolveAssignment(ExecuteTaskRequest request, NextNode next, String auth,
                                          Set<String> settableActors, Set<String> settableGroups,
                                          Map<String, Object> out) {
        // Groups: placed directly (no strategy), only if the caller supplied them.
        if (!settableGroups.isEmpty() && notEmpty(request.getAssignToGroups())) {
            String groupsValue = String.join(",", request.getAssignToGroups());
            for (String g : settableGroups) {
                out.put(g, groupsValue);
            }
        }

        // Actors: resolved by the strategy.
        if (!settableActors.isEmpty()) {
            requireStrategy(request, next);
            if (notEmpty(request.getAssignToActors())) {
                String actorsValue = String.join(",", request.getAssignToActors());
                for (String a : settableActors) {
                    request.getVariables().put(a, actorsValue); // so USER/GROUP/CHOICE use it
                }
            }
            for (String a : settableActors) {
                request.setAssigneToFieldName(a);
                AssignmentResult result = assignmentResolver.resolve(request, auth);
                if (result instanceof AssignmentResult.Choices choices) {
                    return new ExecOutcome(HttpStatus.OK, choices.users());
                }
                out.put(a, ((AssignmentResult.Resolved) result).variables().get(a));
            }
        }
        return null;
    }

    private Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new LinkedHashSet<>(a);
        out.addAll(b);
        return out;
    }

    private static boolean notEmpty(List<String> list) {
        return list != null && !list.isEmpty();
    }

    private void requireStrategy(ExecuteTaskRequest request, NextNode next) {
        if (request.getAssignmentStrategy() == null || request.getAssignmentStrategy().isBlank()) {
            String who = (next.nodeName() != null) ? "'" + next.nodeName() + "'" : "the next task";
            throw new AssignmentValidationException("ASSIGNMENT_REQUIRED",
                    "Task " + who + " has a dynamic assignee; assignmentStrategy is required.");
        }
    }

    /** Every key (excluding {@code exempt}) must be present in the request. */
    private void requirePresent(Set<String> keys, Set<String> exempt, ExecuteTaskRequest request) {
        List<String> missing = new ArrayList<>();
        for (String key : keys) {
            if ((exempt == null || !exempt.contains(key)) && !request.getVariables().containsKey(key)) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            throw new AssignmentValidationException("MISSING_TASK_VARIABLES",
                    "Missing required task variables: " + missing);
        }
    }

    private Map<String, Object> pick(Set<String> keys, Map<String, Object> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : keys) {
            out.put(key, source.get(key));
        }
        return out;
    }

    private Set<String> intersect(Set<String> a, Set<String> b) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : a) {
            if (b.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }

    // --------------------------------------------------------------- engine I/O

    /**
     * Resolve the task node name from its work-item id. Lists the instance's active tasks
     * (GET {@code /{workflowName}/{instanceId}/tasks?user=&group=}), finds the entry whose
     * {@code id} equals {@code taskId} and returns its {@code name}. This lets callers pass
     * only {@code instanceId}/{@code taskId} without knowing the node name.
     */
    private String fetchTaskNameById(String workflowName, String instanceId, String taskId,
                                     String user, List<String> groups, String auth) {
        List<Map<String, Object>> tasks;
        try {
            var spec = engine.get().uri(uri -> {
                uri.path("/{workflowName}/{instanceId}/tasks").queryParam("user", user);
                groups.forEach(g -> uri.queryParam("group", g));
                return uri.build(workflowName, instanceId);
            });
            if (auth != null && !auth.isBlank()) {
                spec.header(HttpHeaders.AUTHORIZATION, auth);
            }
            tasks = spec.retrieve().body(LIST_OF_MAP);
        } catch (HttpClientErrorException.NotFound nf) {
            throw new WorkflowEngineException(workflowName, HttpStatus.NOT_FOUND,
                    "TASK_NOT_FOUND", "No active tasks found for the given workflowName/instanceId");
        } catch (ResourceAccessException unreachable) {
            throw new WorkflowEngineException(workflowName, HttpStatus.SERVICE_UNAVAILABLE,
                    "ENGINE_UNREACHABLE", "Workflow engine is not reachable.");
        }

        if (tasks != null) {
            for (Map<String, Object> task : tasks) {
                if (taskId.equals(String.valueOf(task.get("id")))) {
                    Object name = task.get("name");
                    if (name != null) {
                        return name.toString();
                    }
                }
            }
        }
        throw new WorkflowEngineException(workflowName, HttpStatus.NOT_FOUND,
                "TASK_NOT_FOUND", "Task not found for taskId '" + taskId
                        + "' among the active tasks of instance '" + instanceId + "'");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchTaskResults(String workflowName, String instanceId, String taskName,
                                                 String taskId, String user, List<String> groups, String auth) {
        try {
            var spec = engine.get().uri(uri -> {
                uri.path(TASK_PATH).queryParam("user", user);
                groups.forEach(g -> uri.queryParam("group", g));
                return uri.build(workflowName, instanceId, taskName, taskId);
            });
            if (auth != null && !auth.isBlank()) {
                spec.header(HttpHeaders.AUTHORIZATION, auth);
            }
            Map<String, Object> task = spec.retrieve().body(MAP);

            Object results = (task == null) ? null : task.get("results");
            if (results instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            return Map.of();

        } catch (HttpClientErrorException.NotFound nf) {
            throw new WorkflowEngineException(workflowName, HttpStatus.NOT_FOUND,
                    "TASK_NOT_FOUND", "Task not found for the given workflowName/instanceId/taskName/taskId");
        } catch (ResourceAccessException unreachable) {
            throw new WorkflowEngineException(workflowName, HttpStatus.SERVICE_UNAVAILABLE,
                    "ENGINE_UNREACHABLE", "Workflow engine is not reachable.");
        }
    }

    /** Current process variables (flat map) used to evaluate gateway conditions. */
    private Map<String, Object> fetchProcessVariables(String workflowName, String instanceId, String auth) {
        try {
            var spec = engine.get().uri("/{workflowName}/{instanceId}", workflowName, instanceId);
            if (auth != null && !auth.isBlank()) {
                spec.header(HttpHeaders.AUTHORIZATION, auth);
            }
            Map<String, Object> vars = spec.retrieve().body(MAP);
            return (vars == null) ? Map.of() : vars;
        } catch (HttpClientErrorException nf) {
            return Map.of();
        } catch (ResourceAccessException unreachable) {
            throw new WorkflowEngineException(workflowName, HttpStatus.SERVICE_UNAVAILABLE,
                    "ENGINE_UNREACHABLE", "Workflow engine is not reachable.");
        }
    }

    /** The declared input variable names of a task (from its JSON schema). */
    @SuppressWarnings("unchecked")
    private Set<String> fetchTaskInputSchema(String workflowName, String taskName, String auth) {
        try {
            var spec = engine.get().uri("/{workflowName}/{taskName}/schema", workflowName, taskName);
            if (auth != null && !auth.isBlank()) {
                spec.header(HttpHeaders.AUTHORIZATION, auth);
            }
            Map<String, Object> schema = spec.retrieve().body(MAP);
            Set<String> inputs = new LinkedHashSet<>();
            if (schema != null && schema.get("properties") instanceof Map<?, ?> props) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) props).entrySet()) {
                    if (e.getValue() instanceof Map<?, ?> meta && Boolean.TRUE.equals(meta.get("input"))) {
                        inputs.add(e.getKey());
                    }
                }
            }
            return inputs;
        } catch (HttpClientErrorException nf) {
            return Set.of();
        } catch (ResourceAccessException unreachable) {
            throw new WorkflowEngineException(workflowName, HttpStatus.SERVICE_UNAVAILABLE,
                    "ENGINE_UNREACHABLE", "Workflow engine is not reachable.");
        }
    }

    private Object completeTask(String workflowName, String instanceId, String taskName, String taskId,
                                String phase, String user, List<String> groups, String auth, Map<String, Object> body) {
        try {
            var spec = engine.post()
                    .uri(uri -> {
                        uri.path(TASK_PATH).queryParam("phase", phase).queryParam("user", user);
                        groups.forEach(g -> uri.queryParam("group", g));
                        return uri.build(workflowName, instanceId, taskName, taskId);
                    })
                    .contentType(MediaType.APPLICATION_JSON);
            if (auth != null && !auth.isBlank()) {
                spec.header(HttpHeaders.AUTHORIZATION, auth);
            }
            return spec.body(body).retrieve().body(Object.class);

        } catch (HttpClientErrorException.NotFound nf) {
            throw new WorkflowEngineException(workflowName, HttpStatus.NOT_FOUND,
                    "TASK_NOT_FOUND", "Task not found for the given workflowName/instanceId/taskName/taskId");
        } catch (HttpClientErrorException ce) {
            throw new WorkflowEngineException(workflowName, ce.getStatusCode(),
                    "ENGINE_REJECTED_REQUEST", ce.getResponseBodyAsString());
        } catch (HttpServerErrorException se) {
            throw new WorkflowEngineException(workflowName, HttpStatus.BAD_GATEWAY,
                    "ENGINE_ERROR", "The workflow engine returned an error: " + se.getResponseBodyAsString());
        } catch (ResourceAccessException unreachable) {
            throw new WorkflowEngineException(workflowName, HttpStatus.SERVICE_UNAVAILABLE,
                    "ENGINE_UNREACHABLE", "Workflow engine is not reachable.");
        }
    }

    // ------------------------------------------------------------------ helpers

    private UserDetailsJwt requireJwtUser() {
        UserDetailsJwt user = UserContextHolder.getContext();
        if (user == null || user.getUsername() == null || user.getUsername().isBlank()) {
            throw new UnauthorizedException(
                    "A valid JWT is required (Authorization: Bearer <token>).");
        }
        return user;
    }

    private static List<String> groupNames(UserDetailsJwt user) {
        List<String> groups = new ArrayList<>();
        if (user.getUserGroups() != null) {
            for (UserGroupDTO group : user.getUserGroups()) {
                if (group.getGroupName() != null) {
                    groups.add(group.getGroupName());
                }
            }
        }
        return groups;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String require(String value, String code, String message) {
        if (value == null || value.isBlank()) {
            throw new AssignmentValidationException(code, message);
        }
        return value.trim();
    }
}
