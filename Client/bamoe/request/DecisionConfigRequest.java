package bamoe.request;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Getter;
import lombok.Setter;

/**
 * Body for {@code POST /api/decisions} — configures the decision variable and its allowed
 * values for a task, or for the start stage ({@code taskName = "START"}).
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DecisionConfigRequest {

    /** Mandatory. */
    private String workflowName;

    /** Mandatory. A human task name, or {@code START} for the start stage. */
    private String taskName;

    /** Mandatory. The variable carrying the decision, e.g. {@code checkerDecision}. */
    private String variableName;

    /** Mandatory, non-empty. E.g. {@code ["APPROVE","REJECT","SENDBACK"]}. */
    private List<String> allowedValues;
}
