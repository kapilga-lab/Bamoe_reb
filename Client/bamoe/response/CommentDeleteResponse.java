package bamoe.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/** {@code DELETE /api/comments/{id}} result. */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class CommentDeleteResponse {
    private boolean deleted;
    private Long id;
}
