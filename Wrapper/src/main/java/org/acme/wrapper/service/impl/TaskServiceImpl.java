package org.acme.wrapper.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.acme.security.jwt.UserContextHolder;
import org.acme.security.jwt.dto.UserDetailsJwt;
import org.acme.security.jwt.dto.UserGroupDTO;
import org.acme.wrapper.exception.UnauthorizedException;
import org.acme.wrapper.exception.WorkflowEngineException;
import org.acme.wrapper.service.TaskService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
}
