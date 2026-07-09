package org.acme.wrapper.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.acme.security.jwt.UserContextHolder;
import org.acme.security.jwt.dto.UserDetailsJwt;
import org.acme.security.jwt.dto.UserGroupDTO;
import org.acme.wrapper.exception.AssignmentValidationException;
import org.acme.wrapper.exception.UnauthorizedException;
import org.acme.wrapper.exception.WorkflowEngineException;
import org.acme.wrapper.service.TaskService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Calls the engine's {@code /usertasks/instance} endpoint using the identity taken
 * from the JWT (via {@link UserContextHolder}) and filters the result.
 */
@Service
public class TaskServiceImpl implements TaskService {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> TASK_LIST =
            new ParameterizedTypeReference<>() {
            };

    private static final ParameterizedTypeReference<Map<String, Object>> GRAPHQL_RESPONSE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient engine;

    public TaskServiceImpl(
            @Value("${wrapper.engine.base-url:http://${server.address:localhost}:${server.port:8080}${server.servlet.context-path:}}")
            String engineBaseUrl) {
        this.engine = RestClient.builder().baseUrl(engineBaseUrl).build();
    }

    @Override
    public List<Map<String, Object>> myTasks(String taskName, String processId) {
        UserDetailsJwt user = UserContextHolder.getContext();
        if (user == null || user.getUsername() == null || user.getUsername().isBlank()) {
            throw new UnauthorizedException(
                    "A valid JWT is required to list tasks (Authorization: Bearer <token>).");
        }

        final String username = user.getUsername();
        final List<String> groups = new ArrayList<>();
        if (user.getUserGroups() != null) {
            for (UserGroupDTO group : user.getUserGroups()) {
                if (group.getGroupName() != null) {
                    groups.add(group.getGroupName());
                }
            }
        }

        List<Map<String, Object>> tasks;
        try {
            var spec = engine.get().uri(uri -> {
                uri.path("/usertasks/instance").queryParam("user", username);
                groups.forEach(g -> uri.queryParam("group", g));
                return uri.build();
            });

            // Forward the JWT so identity stays consistent if security is ever enabled.
            if (user.getJwtToken() != null && !user.getJwtToken().isBlank()) {
                spec.header(HttpHeaders.AUTHORIZATION, user.getJwtToken());
            }

            tasks = spec.retrieve().body(TASK_LIST);

        } catch (ResourceAccessException unreachable) {
            throw new WorkflowEngineException(null, HttpStatus.SERVICE_UNAVAILABLE,
                    "ENGINE_UNREACHABLE",
                    "Workflow engine is not reachable. Check that the BAMOE service is running.");
        }

        if (tasks == null) {
            return List.of();
        }

        return tasks.stream()
                .filter(task -> taskName == null || taskName.equals(task.get("taskName")))
                .filter(task -> processId == null || processIdMatches(task, processId))
                .toList();
    }

    private boolean processIdMatches(Map<String, Object> task, String processId) {
        Object processInfo = task.get("processInfo");
        if (processInfo instanceof Map<?, ?> info) {
            return processId.equals(info.get("processId"));
        }
        return false;
    }

    private static final String INVOLVED_QUERY = """
            query ($where: UserTaskInstanceArgument) {
              UserTaskInstances(where: $where, orderBy: { lastUpdate: DESC }) {
                id name state actualOwner processId processInstanceId
                started completed lastUpdate potentialUsers potentialGroups externalReferenceId
              }
            }
            """;

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> involvedTasks(String workflowName, String taskName, String state, String filter,
                                                   boolean liveOnly) {
        UserDetailsJwt user = UserContextHolder.getContext();
        if (user == null || user.getUsername() == null || user.getUsername().isBlank()) {
            throw new UnauthorizedException(
                    "A valid JWT is required to list tasks (Authorization: Bearer <token>).");
        }
        final String username = user.getUsername();
        final List<String> groups = new ArrayList<>();
        if (user.getUserGroups() != null) {
            for (UserGroupDTO group : user.getUserGroups()) {
                if (group.getGroupName() != null) {
                    groups.add(group.getGroupName());
                }
            }
        }

        // Involvement = I own(ed) it, or I am/was a candidate (by name or by group).
        List<Map<String, Object>> involvement = new ArrayList<>();
        involvement.add(Map.of("actualOwner", Map.of("equal", username)));
        involvement.add(Map.of("potentialUsers", Map.of("containsAny", List.of(username))));
        if (!groups.isEmpty()) {
            involvement.add(Map.of("potentialGroups", Map.of("containsAny", groups)));
        }
        Map<String, Object> where = new LinkedHashMap<>();
        where.put("or", involvement);
        applyInvolvementFilter(where, filter, username);
        if (workflowName != null && !workflowName.isBlank()) {
            where.put("processId", Map.of("equal", workflowName.trim()));
        }
        if (taskName != null && !taskName.isBlank()) {
            where.put("name", Map.of("equal", taskName.trim()));
        }
        if (state != null && !state.isBlank()) {
            where.put("state", Map.of("equal", state.trim()));
        }

        // Live instances only: constrain the tasks to still-running process instances.
        if (liveOnly) {
            List<String> liveInstances = fetchActiveInstanceIds(user, workflowName);
            if (liveInstances.isEmpty()) {
                return List.of();
            }
            where.put("processInstanceId", Map.of("in", liveInstances));
        }

        Map<String, Object> response = dataIndexQuery(user, INVOLVED_QUERY, Map.of("where", where));

        List<Map<String, Object>> tasks = List.of();
        if (response != null && response.get("data") instanceof Map<?, ?> data
                && data.get("UserTaskInstances") instanceof List<?> list) {
            tasks = (List<Map<String, Object>>) list;
        }

        // Consistent wrapper naming: expose the process id as workflowName on every entry.
        List<Map<String, Object>> out = new ArrayList<>(tasks.size());
        for (Map<String, Object> task : tasks) {
            Map<String, Object> entry = new LinkedHashMap<>(task);
            entry.put("workflowName", task.get("processId"));
            out.add(entry);
        }
        return out;
    }

    private static final String ACTIVE_INSTANCES_QUERY = """
            query ($where: ProcessInstanceArgument) {
              ProcessInstances(where: $where) { id }
            }
            """;

    /** Ids of process instances still in state ACTIVE (optionally for one process). */
    @SuppressWarnings("unchecked")
    private List<String> fetchActiveInstanceIds(UserDetailsJwt user, String workflowName) {
        Map<String, Object> where = new LinkedHashMap<>();
        where.put("state", Map.of("equal", "ACTIVE"));
        if (workflowName != null && !workflowName.isBlank()) {
            where.put("processId", Map.of("equal", workflowName.trim()));
        }
        Map<String, Object> response = dataIndexQuery(user, ACTIVE_INSTANCES_QUERY, Map.of("where", where));
        List<String> ids = new ArrayList<>();
        if (response != null && response.get("data") instanceof Map<?, ?> data
                && data.get("ProcessInstances") instanceof List<?> list) {
            for (Object o : (List<Object>) list) {
                if (o instanceof Map<?, ?> pi && pi.get("id") != null) {
                    ids.add(pi.get("id").toString());
                }
            }
        }
        return ids;
    }

    /** POST a query+variables to the data-index {@code /graphql}; GraphQL errors → 502. */
    private Map<String, Object> dataIndexQuery(UserDetailsJwt user, String query, Map<String, Object> variables) {
        Map<String, Object> response;
        try {
            var spec = engine.post().uri("/graphql").contentType(MediaType.APPLICATION_JSON);
            if (user.getJwtToken() != null && !user.getJwtToken().isBlank()) {
                spec.header(HttpHeaders.AUTHORIZATION, user.getJwtToken());
            }
            response = spec.body(Map.of("query", query, "variables", variables))
                    .retrieve().body(GRAPHQL_RESPONSE);
        } catch (ResourceAccessException unreachable) {
            throw new WorkflowEngineException(null, HttpStatus.SERVICE_UNAVAILABLE,
                    "ENGINE_UNREACHABLE",
                    "Workflow engine is not reachable. Check that the BAMOE service is running.");
        }
        if (response != null && response.get("errors") != null) {
            throw new WorkflowEngineException(null, HttpStatus.BAD_GATEWAY,
                    "ENGINE_ERROR", "Data-index query failed: " + response.get("errors"));
        }
        return response;
    }

    @Override
    public Map<String, Boolean> taskActions(String instanceId, String taskId) {
        UserDetailsJwt user = UserContextHolder.getContext();
        if (user == null || user.getUsername() == null || user.getUsername().isBlank()) {
            throw new UnauthorizedException(
                    "A valid JWT is required (Authorization: Bearer <token>).");
        }
        String username = user.getUsername();

        // The user-scoped task list only contains tasks this user may act on (Ready as a
        // candidate, or Reserved by them) — anything else yields all-false.
        Map<String, Object> task = null;
        for (Map<String, Object> candidate : myTasks(null, null)) {
            boolean idMatch = taskId.equals(String.valueOf(candidate.get("id")))
                    || taskId.equals(String.valueOf(candidate.get("externalReferenceId")));
            if (idMatch && instanceId.equals(processInstanceIdOf(candidate))) {
                task = candidate;
                break;
            }
        }
        if (task == null) {
            return actions(false, false, false);
        }

        String status = statusNameOf(task);
        String actualOwner = task.get("actualOwner") == null ? null : task.get("actualOwner").toString();
        if ("Reserved".equalsIgnoreCase(status)) {
            boolean mine = username.equals(actualOwner);
            return actions(false, mine, mine);
        }
        // Ready (or unclaimed states): candidate may claim or complete directly.
        return actions(true, false, true);
    }

    private static final String TASK_INFO_QUERY = """
            query ($where: UserTaskInstanceArgument) {
              UserTaskInstances(where: $where) {
                id name state actualOwner processId processInstanceId externalReferenceId
              }
            }
            """;

    @Override
    public Map<String, Object> taskInfo(String taskId) {
        UserDetailsJwt user = UserContextHolder.getContext();
        if (user == null || user.getUsername() == null || user.getUsername().isBlank()) {
            throw new UnauthorizedException(
                    "A valid JWT is required (Authorization: Bearer <token>).");
        }
        if (taskId == null || taskId.isBlank()) {
            throw new AssignmentValidationException("MISSING_TASK_ID", "taskId is mandatory");
        }
        String id = taskId.trim();

        // Callers may hold either the usertask id or the work-item id (externalReferenceId).
        Map<String, Object> where = Map.of("or", List.of(
                Map.of("id", Map.of("equal", id)),
                Map.of("externalReferenceId", Map.of("equal", id))));
        Map<String, Object> response = dataIndexQuery(user, TASK_INFO_QUERY, Map.of("where", where));

        Map<String, Object> task = null;
        if (response != null && response.get("data") instanceof Map<?, ?> data
                && data.get("UserTaskInstances") instanceof List<?> list && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> first) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) first;
            task = cast;
        }
        if (task == null) {
            throw new WorkflowEngineException(null, HttpStatus.NOT_FOUND,
                    "TASK_NOT_FOUND", "No task found for taskId '" + id + "'.");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", id);
        out.put("taskName", task.get("name"));
        out.put("instanceId", task.get("processInstanceId"));
        out.put("workflowName", task.get("processId"));
        out.put("state", task.get("state"));
        out.put("actualOwner", task.get("actualOwner"));
        return out;
    }

    private static Map<String, Boolean> actions(boolean claim, boolean release, boolean complete) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        out.put("claim", claim);
        out.put("release", release);
        out.put("complete", complete);
        return out;
    }

    private static String processInstanceIdOf(Map<String, Object> task) {
        Object info = task.get("processInfo");
        if (info instanceof Map<?, ?> map && map.get("processInstanceId") != null) {
            return map.get("processInstanceId").toString();
        }
        return null;
    }

    private static String statusNameOf(Map<String, Object> task) {
        Object status = task.get("status");
        if (status instanceof Map<?, ?> map && map.get("name") != null) {
            return map.get("name").toString();
        }
        return (status == null) ? null : status.toString();
    }

    /**
     * Narrows the involvement query to a mode:
     * <ul>
     *   <li>{@code COMPLETED_BY_ME} — I am the actual owner and the task is Completed.</li>
     *   <li>{@code NOT_WITH_ME} — earlier with me but no longer actionable by me:
     *       Completed/Aborted, or currently Reserved by someone else.</li>
     *   <li>{@code WITH_ME} — currently actionable by me: Ready (I'm a candidate)
     *       or Reserved by me.</li>
     * </ul>
     */
    private void applyInvolvementFilter(Map<String, Object> where, String filter, String username) {
        if (filter == null || filter.isBlank()) {
            return;
        }
        switch (filter.trim().toUpperCase()) {
            case "COMPLETED_BY_ME" -> {
                where.put("actualOwner", Map.of("equal", username));
                where.put("state", Map.of("equal", "Completed"));
            }
            case "NOT_WITH_ME" -> where.put("and", List.of(Map.of("or", List.of(
                    Map.of("state", Map.of("in", List.of("Completed", "Aborted"))),
                    Map.of("and", List.of(
                            Map.of("state", Map.of("equal", "Reserved")),
                            Map.of("not", Map.of("actualOwner", Map.of("equal", username)))))))));
            case "WITH_ME" -> where.put("and", List.of(Map.of("or", List.of(
                    Map.of("state", Map.of("equal", "Ready")),
                    Map.of("and", List.of(
                            Map.of("state", Map.of("equal", "Reserved")),
                            Map.of("actualOwner", Map.of("equal", username))))))));
            default -> throw new AssignmentValidationException("INVALID_FILTER",
                    "Unknown filter '" + filter + "'. Use COMPLETED_BY_ME, NOT_WITH_ME or WITH_ME.");
        }
    }
}
