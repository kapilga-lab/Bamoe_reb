package org.acme.wrapper.dto;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

/** Body for configuring a task's decision variable and its allowed values. */
@Getter
@Setter
public class DecisionConfigRequest {

    /** Mandatory. The workflow (process id), e.g. {@code approval}. */
    private String workflowName;

    /** Mandatory. The human task name, e.g. {@code Checker}. */
    private String taskName;

    /** Mandatory. The output variable carrying the decision, e.g. {@code checkerDecision}. */
    private String variableName;

    /** Mandatory, non-empty. The allowed values, e.g. {@code ["APPROVE","REJECT","SENDBACK"]}. */
    private List<String> allowedValues;
}
