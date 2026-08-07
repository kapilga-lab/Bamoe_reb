package bamoe.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/** {@code DELETE /api/workflows/{instanceId}} result. */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class AbortResponse {

    private String instanceId;
    private String workflowName;

    /** The instance's status before the call: ACTIVE | COMPLETED | ABORTED. */
    private String previousStatus;

    /** true if this call aborted a running instance; false if it was already ended. */
    private boolean aborted;

    /** Set when {@code aborted} is false (already-ended instance). */
    private String message;
}
