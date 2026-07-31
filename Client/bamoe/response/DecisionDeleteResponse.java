package bamoe.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/** {@code DELETE /api/decisions} result. */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class DecisionDeleteResponse {
    private boolean deleted;
    private String workflowName;
    private String taskName;
}
