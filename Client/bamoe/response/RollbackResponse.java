package bamoe.response;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/** {@code POST /api/workflows/rollback} result. */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class RollbackResponse {

    /** The human task the workflow was rolled back to. */
    private String rolledBackTo;

    /** Live path: the same instance. Ended path: the NEW instance carrying on the work. */
    private String instanceId;

    /** Ended path only: the original (ended) instance id. */
    private String previousInstanceId;

    private List<String> activeNodes;
}
