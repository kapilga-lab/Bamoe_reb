package bamoe.request;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Getter;
import lombok.Setter;

/**
 * Body for {@code POST /api/comments} (add) and {@code PUT /api/comments/{id}} (update —
 * only {@code comment} is used there).
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CommentRequest {

    /** Mandatory on add. */
    private String instanceId;

    /** Mandatory on add. */
    private String taskId;

    /** The comment text. */
    private String comment;
}
