package org.acme.wrapper.dto;

import lombok.Getter;
import lombok.Setter;

/** Body for adding/updating a task comment. */
@Getter
@Setter
public class CommentRequest {

    /** Mandatory on add. The process instance id. */
    private String instanceId;

    /** Mandatory on add. The task (work item or usertask) id the comment belongs to. */
    private String taskId;

    /** The comment text. */
    private String comment;
}
