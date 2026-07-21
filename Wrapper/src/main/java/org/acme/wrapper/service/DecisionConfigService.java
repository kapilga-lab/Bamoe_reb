package org.acme.wrapper.service;

import java.util.List;
import java.util.Map;

import org.acme.wrapper.dto.DecisionConfigRequest;

/**
 * Configures, per (workflowName, taskName), which output variable carries the task's
 * decision and which values are allowed. A configured task enforces the variable +
 * value at completion; an unconfigured task is not validated.
 */
public interface DecisionConfigService {

    /** Create/replace the config. @return the stored config. */
    Map<String, Object> saveConfig(DecisionConfigRequest request);

    /** @return the config for one task (404 when not configured). */
    Map<String, Object> getConfig(String workflowName, String taskName);

    /** @return all configs of a workflow (possibly empty). */
    List<Map<String, Object>> getConfigs(String workflowName);

    /** Remove the config (404 when not configured). */
    Map<String, Object> deleteConfig(String workflowName, String taskName);
}
