package bamoe.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/** A stored task comment (add/update result and page content). */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class CommentResponse {
    private Long id;
    private String instanceId;
    private String taskId;
    private String taskName;
    private String comment;
    private String commentBy;
    private String commentAt;
}
