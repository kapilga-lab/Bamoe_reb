package org.acme.wrapper;

import java.util.Map;

import org.acme.wrapper.dto.CommentRequest;
import org.acme.wrapper.service.CommentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Custom comments against a task of a live instance. Writing follows the completion
 * rule (Ready → potential owners; Reserved → only the owner); update/delete only by
 * the comment's author. Reading is open to any authenticated user.
 */
@RestController
@RequestMapping("/api/comments")
public class CommentController {

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> addComment(@RequestBody CommentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(commentService.addComment(request));
    }

    @PutMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> updateComment(@PathVariable("id") long id, @RequestBody CommentRequest request) {
        return commentService.updateComment(id, request);
    }

    @DeleteMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> deleteComment(@PathVariable("id") long id) {
        return commentService.deleteComment(id);
    }

    /**
     * Paged comments of an instance (newest first), optionally filtered by task name
     * and/or author.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getComments(
            @RequestParam("instanceId") String instanceId,
            @RequestParam(value = "taskName", required = false) String taskName,
            @RequestParam(value = "commentBy", required = false) String commentBy,
            @RequestParam(value = "page", required = false, defaultValue = "0") int page,
            @RequestParam(value = "size", required = false, defaultValue = "20") int size) {
        return commentService.getComments(instanceId, taskName, commentBy, page, size);
    }
}
