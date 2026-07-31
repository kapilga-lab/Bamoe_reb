package bamoe.response;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/** One entry of {@code GET /api/tasks/involved} — current or historical involvement. */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class InvolvedTaskResponse {

    private String id;

    /** Task node name, e.g. {@code Review}. */
    private String name;

    /** Ready | Reserved | Completed | Aborted. */
    private String state;

    private String actualOwner;
    private String processId;
    private String processInstanceId;
    private String started;
    private String completed;
    private String lastUpdate;
    private List<String> potentialUsers;
    private List<String> potentialGroups;
    private String externalReferenceId;

    /** Same as {@code processId}; wrapper-consistent naming. */
    private String workflowName;
}
