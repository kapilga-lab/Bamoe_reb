package bamoe.response;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/** A stored decision configuration for a task or the START stage. */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class DecisionConfigResponse {
    private String workflowName;

    /** A human task name, or {@code START} for the start stage. */
    private String taskName;

    private String variableName;
    private List<String> allowedValues;
}
