package org.acme.wrapper;

import java.util.Map;

import org.acme.wrapper.dto.DecisionConfigRequest;
import org.acme.wrapper.service.DecisionConfigService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Decision configuration per (workflowName, taskName): which output variable carries the
 * decision and which values it may take. When configured, completing the task enforces
 * the variable and value; when not configured, completion is not validated.
 */
@RestController
@RequestMapping("/api/decisions")
public class DecisionConfigController {

    private final DecisionConfigService decisionConfigService;

    public DecisionConfigController(DecisionConfigService decisionConfigService) {
        this.decisionConfigService = decisionConfigService;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> saveConfig(@RequestBody DecisionConfigRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(decisionConfigService.saveConfig(request));
    }

    /** With {@code taskName}: one config (404 when absent). Without: all configs of the workflow. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> getConfig(
            @RequestParam("workflowName") String workflowName,
            @RequestParam(value = "taskName", required = false) String taskName) {
        if (taskName == null || taskName.isBlank()) {
            return ResponseEntity.ok(decisionConfigService.getConfigs(workflowName));
        }
        return ResponseEntity.ok(decisionConfigService.getConfig(workflowName, taskName));
    }

    @DeleteMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> deleteConfig(
            @RequestParam("workflowName") String workflowName,
            @RequestParam("taskName") String taskName) {
        return decisionConfigService.deleteConfig(workflowName, taskName);
    }
}
