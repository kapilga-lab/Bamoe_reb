package bamoe.response;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/** One entry of {@code GET /api/tasks/my-task} — an active task actionable by the caller. */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class MyTaskResponse {

    /** Usertask id — use as {@code taskId} in executeTask/comments/actions calls. */
    private String id;

    private String userTaskId;
    private String taskName;
    private TaskStatusInfo status;
    private String actualOwner;
    private List<String> potentialUsers;
    private List<String> potentialGroups;
    private List<String> adminUsers;
    private List<String> adminGroups;
    private List<String> excludedUsers;

    /** Work-item id — also accepted wherever a taskId is expected. */
    private String externalReferenceId;

    private Map<String, Object> inputs;
    private Map<String, Object> outputs;
    private Map<String, Object> metadata;
    private ProcessInfo processInfo;
}
