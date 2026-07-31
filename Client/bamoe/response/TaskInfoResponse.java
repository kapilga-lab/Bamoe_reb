package bamoe.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/** {@code GET /api/tasks/{taskId}/info} — workflow coordinates of a task id. */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskInfoResponse {
    private String taskId;
    private String taskName;
    private String instanceId;
    private String workflowName;
    private String state;
    private String actualOwner;
}
