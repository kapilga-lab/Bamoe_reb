package bamoe.response;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * {@code POST /api/workflows/reassign} result. Reassigned tasks are re-created, so each carries
 * its NEW taskId; per-item problems are listed in {@code skipped} / {@code failed}.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReassignResponse {

    private String currentUser;
    private String newUser;
    private List<ReassignedTask> reassignedTasks;
    private List<VariableChange> updatedVariables;
    private List<AssignmentChange> updatedAssignments;
    private List<TaskIssue> skipped;
    private List<TaskIssue> failed;

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReassignedTask {
        private String workflowName;
        private String instanceId;
        private String taskName;
        private String oldTaskId;
        private String newTaskId;
        private String actualOwner;
    }

    /** Actor process variables rewritten in an instance (drive future activations). */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VariableChange {
        private String workflowName;
        private String instanceId;
        private Map<String, Object> variables;
    }

    /** Remembered assignee (LAST_ASSIGN_TO_* memory) rewritten for an instance's task. */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AssignmentChange {
        private String workflowName;
        private String instanceId;
        private String taskName;
        private String field;
        private String previous;
        private String current;
    }

    /** A task that was skipped ({@code reason}) or failed ({@code error}). */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TaskIssue {
        private String workflowName;
        private String instanceId;
        private String taskName;
        private String taskId;
        private String reason;
        private String error;
        private String message;
    }
}
