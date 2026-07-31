package bamoe.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/** {@code GET /api/tasks/actions} — what the caller may do with the task right now. */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskActionsResponse {
    private boolean claim;
    private boolean release;
    private boolean complete;
}
