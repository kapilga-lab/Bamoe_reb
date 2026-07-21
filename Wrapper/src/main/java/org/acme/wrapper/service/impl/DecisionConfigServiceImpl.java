package org.acme.wrapper.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.acme.security.jwt.UserContextHolder;
import org.acme.security.jwt.dto.UserDetailsJwt;
import org.acme.wrapper.decisions.DecisionConfigStore;
import org.acme.wrapper.dto.DecisionConfigRequest;
import org.acme.wrapper.exception.AssignmentValidationException;
import org.acme.wrapper.exception.UnauthorizedException;
import org.acme.wrapper.exception.WorkflowEngineException;
import org.acme.wrapper.graph.ProcessGraphService;
import org.acme.wrapper.service.DecisionConfigService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class DecisionConfigServiceImpl implements DecisionConfigService {

    private final DecisionConfigStore store;
    private final ProcessGraphService processGraphService;

    public DecisionConfigServiceImpl(DecisionConfigStore store, ProcessGraphService processGraphService) {
        this.store = store;
        this.processGraphService = processGraphService;
    }

    @Override
    public Map<String, Object> saveConfig(DecisionConfigRequest request) {
        String user = requireUser();
        String workflowName = require(request.getWorkflowName(), "MISSING_WORKFLOW_NAME", "workflowName is mandatory");
        String taskName = require(request.getTaskName(), "MISSING_TASK_NAME", "taskName is mandatory");
        String variableName = require(request.getVariableName(), "MISSING_VARIABLE_NAME", "variableName is mandatory");

        // Reserved marker "START": the decision applies to starting the workflow, not to a
        // task — no human-task existence check.
        if ("START".equalsIgnoreCase(taskName)) {
            taskName = "START";
        } else if (!processGraphService.humanTasks(workflowName).contains(taskName)) {
            throw new AssignmentValidationException("INVALID_TASK_NAME",
                    "'" + taskName + "' is not a human task of '" + workflowName + "'. Expected one of "
                            + processGraphService.humanTasks(workflowName) + " or START for the start stage");
        }

        List<String> values = new ArrayList<>();
        if (request.getAllowedValues() != null) {
            for (String value : request.getAllowedValues()) {
                if (value != null && !value.isBlank()) {
                    String trimmed = value.trim();
                    if (values.stream().noneMatch(v -> v.equalsIgnoreCase(trimmed))) {
                        values.add(trimmed);
                    }
                }
            }
        }
        if (values.isEmpty()) {
            throw new AssignmentValidationException("MISSING_ALLOWED_VALUES",
                    "allowedValues must contain at least one non-blank value");
        }
        if (values.stream().anyMatch(v -> v.contains(","))) {
            throw new AssignmentValidationException("INVALID_ALLOWED_VALUE",
                    "allowedValues must not contain commas");
        }

        store.upsert(workflowName, taskName, variableName, values, user);
        return getConfig(workflowName, taskName);
    }

    @Override
    public Map<String, Object> getConfig(String workflowName, String taskName) {
        requireUser();
        return store.find(require(workflowName, "MISSING_WORKFLOW_NAME", "workflowName is mandatory"),
                        require(taskName, "MISSING_TASK_NAME", "taskName is mandatory"))
                .orElseThrow(() -> new WorkflowEngineException(workflowName, HttpStatus.NOT_FOUND,
                        "DECISION_NOT_CONFIGURED",
                        "No decision configured for task '" + taskName + "' of '" + workflowName + "'"));
    }

    @Override
    public List<Map<String, Object>> getConfigs(String workflowName) {
        requireUser();
        return store.findByWorkflow(require(workflowName, "MISSING_WORKFLOW_NAME", "workflowName is mandatory"));
    }

    @Override
    public Map<String, Object> deleteConfig(String workflowName, String taskName) {
        requireUser();
        boolean deleted = store.delete(
                require(workflowName, "MISSING_WORKFLOW_NAME", "workflowName is mandatory"),
                require(taskName, "MISSING_TASK_NAME", "taskName is mandatory"));
        if (!deleted) {
            throw new WorkflowEngineException(workflowName, HttpStatus.NOT_FOUND,
                    "DECISION_NOT_CONFIGURED",
                    "No decision configured for task '" + taskName + "' of '" + workflowName + "'");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted", true);
        out.put("workflowName", workflowName.trim());
        out.put("taskName", taskName.trim());
        return out;
    }

    private String requireUser() {
        UserDetailsJwt user = UserContextHolder.getContext();
        if (user == null || user.getUsername() == null || user.getUsername().isBlank()) {
            throw new UnauthorizedException("A valid JWT is required (Authorization: Bearer <token>).");
        }
        return user.getUsername();
    }

    private static String require(String value, String code, String message) {
        if (value == null || value.isBlank()) {
            throw new AssignmentValidationException(code, message);
        }
        return value.trim();
    }
}
