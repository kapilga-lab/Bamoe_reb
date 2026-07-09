package org.acme.wrapper.service;

import java.util.Map;

import org.acme.wrapper.dto.CommentRequest;

/**
 * Custom comments against a task of a live instance. Write access follows the same rule
 * as completing the task: Ready → any potential owner; Reserved → only the actual owner.
 * Update/delete additionally require being the comment's author.
 */
public interface CommentService {

    /** @return the created comment row (201). */
    Map<String, Object> addComment(CommentRequest request);

    /** @return the updated comment row. */
    Map<String, Object> updateComment(long commentId, CommentRequest request);

    /** @return {@code {deleted: true, id: …}}. */
    Map<String, Object> deleteComment(long commentId);

    /**
     * Paged comments of an instance, newest first.
     *
     * @param instanceId mandatory.
     * @param taskName   optional filter.
     * @param commentBy  optional filter.
     * @param page       zero-based page index.
     * @param size       page size (capped at 100).
     * @return {@code {content: [...], page, size, totalElements, totalPages}}.
     */
    Map<String, Object> getComments(String instanceId, String taskName, String commentBy, int page, int size);
}
