package bamoe.response;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/** {@code GET /api/comments} — one page of an instance's comments, newest first. */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class CommentPageResponse {
    private List<CommentResponse> content;
    private int page;
    private int size;
    private long totalElements;
    private long totalPages;
}
