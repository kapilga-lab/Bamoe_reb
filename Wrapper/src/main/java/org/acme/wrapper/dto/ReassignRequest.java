package org.acme.wrapper.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * Body for {@code POST /api/workflows/reassign}: replace {@code currentUser} with {@code newUser}
 * on active tasks. The scope fields are optional AND-filters; at least one of workflowName,
 * instanceId or taskId is required, and taskName needs workflowName or instanceId.
 */
@Getter
@Setter
public class ReassignRequest {

    /** Mandatory. The user to take the tasks away from. */
    private String currentUser;

    /** Mandatory. The user to give the tasks to. */
    private String newUser;

    private String workflowName;

    private String taskName;

    private String instanceId;

    /** Usertask id or work-item id. */
    private String taskId;
}
