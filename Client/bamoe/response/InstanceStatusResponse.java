package bamoe.response;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/** {@code GET /api/workflows/{instanceId}/status} — full status of an instance. */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class InstanceStatusResponse {

    private String instanceId;
    private String workflowName;

    /** The BPMN display name of the process. */
    private String workflowLabel;

    /** ACTIVE | COMPLETED | ABORTED. */
    private String status;

    private boolean completed;

    /** End event the instance finished at (e.g. Approved / Rejected); null while live. */
    private String endedBy;

    /** All reached end events (parallel flows can reach several). */
    private List<String> endNodes;

    private String businessKey;
    private String start;
    private String end;

    private List<StatusTaskResponse> activeTasks;
    private List<StatusTaskResponse> completedTasks;
}
