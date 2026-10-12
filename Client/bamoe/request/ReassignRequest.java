package bamoe.request;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Getter;
import lombok.Setter;

/**
 * Body for {@code POST /api/workflows/reassign}: move active tasks from {@code currentUser} to
 * {@code newUser}. Scope (AND-filters, at least one of workflowName / instanceId / taskId):
 * <ul>
 *   <li>workflowName — every running instance of the workflow</li>
 *   <li>workflowName + taskName — that task in every instance</li>
 *   <li>instanceId — every task of the instance</li>
 *   <li>taskId — only that task</li>
 * </ul>
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReassignRequest {

    /** Mandatory. */
    private String currentUser;

    /** Mandatory. */
    private String newUser;

    private String workflowName;

    private String taskName;

    private String instanceId;

    private String taskId;
}
