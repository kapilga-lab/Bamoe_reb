package bamoe.request;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Getter;
import lombok.Setter;

/**
 * Body for {@code POST /api/workflows/rollback}. Rolls a live instance back to a human
 * task (steered in place) or re-creates an ended instance from its final variables.
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RollbackRequest {

    /** Mandatory. */
    private String workflowName;

    /** Mandatory. */
    private String instanceId;

    /** Optional rollback target; default = the instance's last completed human task. */
    private String taskName;
}
