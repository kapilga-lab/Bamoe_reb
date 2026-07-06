package org.acme.wrapper.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import lombok.Getter;
import lombok.Setter;

/**
 * Body for the start endpoint. The four control fields are bound explicitly; every
 * other JSON property (e.g. {@code makerActors}, {@code request}) is captured into
 * {@link #variables} and forwarded to the engine as process variables.
 */
@Getter
@Setter
public class StartWorkflowRequest {

    /** Mandatory. The deployed process id to start (e.g. {@code approval}). */
    private String workflowName;

    /** Mandatory. Names the process variable that holds the assignee value. */
    private String assigneToFieldName;

    /** Mandatory. One of {@code AssignmentStrategy}. */
    private String assignmentStrategy;

    /** Group filter passed to the userDetails API. */
    private List<String> groupName;

    /** Role filter passed to the userDetails API. */
    private List<String> roleNames;

    /** Users to assign — mapped to the task's Actors variable and resolved by the strategy. */
    private List<String> assignToActors;

    /** Groups to assign — placed directly into the task's Groups variable (no strategy). */
    private List<String> assignToGroups;

    /** All remaining body properties = process variables. */
    private final Map<String, Object> variables = new LinkedHashMap<>();

    @JsonAnySetter
    public void putVariable(String key, Object value) {
        variables.put(key, value);
    }
}
