package bamoe.request;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Getter;
import lombok.Setter;

/**
 * Body for {@code POST /api/workflows/executeTask}.
 *
 * <ul>
 *   <li><b>Start</b>: omit {@code instanceId}/{@code taskId}. For workflows starting with
 *       several parallel human tasks, send a LIST of these items (one per {@code taskName}).</li>
 *   <li><b>Complete</b>: set {@code instanceId} + {@code taskId} plus the task's output
 *       variables (via {@link #putVariable}).</li>
 *   <li><b>Lifecycle</b>: set {@code phase} to {@code claim}/{@code release} to (un)claim
 *       without completing.</li>
 * </ul>
 *
 * Process variables added with {@link #putVariable} are serialized flat at the top level,
 * exactly as the wrapper expects.
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExecuteTaskRequest {

    private String workflowName;

    /** Start-array items only: which parallel first task this item assigns. */
    private String taskName;

    private String instanceId;
    private String taskId;

    /** Optional lifecycle phase: {@code claim}, {@code release}; default complete. */
    private String phase;

    /** USER, GROUP, SELF, RANDOM_SELECT, ROUND_ROBIN, TO_ALL_USER, CHOICE, LAST_ASSIGN_TO_OR_*. */
    private String assignmentStrategy;

    private List<String> assignToActors;
    private List<String> assignToGroups;

    /** Candidate filter for RANDOM_SELECT / ROUND_ROBIN / TO_ALL_USER / CHOICE. */
    private List<String> groupName;
    private List<String> roleNames;

    @JsonIgnore
    private final Map<String, Object> variables = new LinkedHashMap<>();

    /** Add a process/task variable (serialized flat at the top level). */
    public ExecuteTaskRequest putVariable(String name, Object value) {
        variables.put(name, value);
        return this;
    }

    @JsonAnyGetter
    public Map<String, Object> anyVariables() {
        return variables;
    }

    @JsonAnySetter
    public void setVariable(String name, Object value) {
        variables.put(name, value);
    }
}
