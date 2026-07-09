package org.acme.wrapper.service.impl;

import java.util.LinkedHashMap;
import java.util.Map;

import org.acme.security.jwt.UserContextHolder;
import org.acme.security.jwt.dto.UserDetailsJwt;
import org.acme.wrapper.comments.TaskCommentStore;
import org.acme.wrapper.dto.CommentRequest;
import org.acme.wrapper.exception.AssignmentValidationException;
import org.acme.wrapper.exception.UnauthorizedException;
import org.acme.wrapper.exception.WorkflowEngineException;
import org.acme.wrapper.service.CommentService;
import org.acme.wrapper.service.TaskService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class CommentServiceImpl implements CommentService {

    private static final int MAX_PAGE_SIZE = 100;

    private final TaskCommentStore store;
    private final TaskService taskService;

    public CommentServiceImpl(TaskCommentStore store, TaskService taskService) {
        this.store = store;
        this.taskService = taskService;
    }

    @Override
    public Map<String, Object> addComment(CommentRequest request) {
        String username = requireUser();
        String instanceId = require(request.getInstanceId(), "MISSING_INSTANCE_ID", "instanceId is mandatory");
        String taskId = require(request.getTaskId(), "MISSING_TASK_ID", "taskId is mandatory");
        String comment = require(request.getComment(), "MISSING_COMMENT", "comment is mandatory");

        Map<String, Object> task = requireActAccess(instanceId, taskId, username);
        String taskName = String.valueOf(task.get("taskName"));

        long id = store.insert(instanceId, taskId, taskName, comment, username);
        return store.findById(id).orElseThrow(() -> new WorkflowEngineException(null,
                HttpStatus.INTERNAL_SERVER_ERROR, "COMMENT_SAVE_FAILED", "Comment could not be stored."));
    }

    @Override
    public Map<String, Object> updateComment(long commentId, CommentRequest request) {
        String username = requireUser();
        String comment = require(request.getComment(), "MISSING_COMMENT", "comment is mandatory");

        Map<String, Object> existing = requireOwnComment(commentId, username);
        requireActAccess(existing.get("instanceId").toString(), existing.get("taskId").toString(), username);

        store.update(commentId, comment);
        return store.findById(commentId).orElseThrow();
    }

    @Override
    public Map<String, Object> deleteComment(long commentId) {
        String username = requireUser();
        Map<String, Object> existing = requireOwnComment(commentId, username);
        requireActAccess(existing.get("instanceId").toString(), existing.get("taskId").toString(), username);

        store.delete(commentId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted", true);
        out.put("id", commentId);
        return out;
    }

    @Override
    public Map<String, Object> getComments(String instanceId, String taskName, String commentBy,
                                           int page, int size) {
        requireUser();
        String id = require(instanceId, "MISSING_INSTANCE_ID", "instanceId is mandatory");
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

        long total = store.count(id, taskName, commentBy);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", store.find(id, taskName, commentBy, safePage * safeSize, safeSize));
        out.put("page", safePage);
        out.put("size", safeSize);
        out.put("totalElements", total);
        out.put("totalPages", (total + safeSize - 1) / safeSize);
        return out;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The commenter must be able to act on the task right now — same rule as completing
     * it: the task is active and visible to them (Ready → candidate; Reserved → they are
     * the actual owner). Returns the engine task (for its taskName).
     */
    private Map<String, Object> requireActAccess(String instanceId, String taskId, String username) {
        for (Map<String, Object> task : taskService.myTasks(null, null)) {
            boolean idMatch = taskId.equals(String.valueOf(task.get("id")))
                    || taskId.equals(String.valueOf(task.get("externalReferenceId")));
            if (!idMatch || !instanceId.equals(processInstanceIdOf(task))) {
                continue;
            }
            String status = statusNameOf(task);
            Object owner = task.get("actualOwner");
            if ("Reserved".equalsIgnoreCase(status) && !username.equals(owner)) {
                break;
            }
            return task;
        }
        throw new WorkflowEngineException(null, HttpStatus.FORBIDDEN, "COMMENT_NOT_ALLOWED",
                "The task is not active, or you cannot act on it (Ready: potential owners; "
                        + "Reserved: only the owner).");
    }

    private Map<String, Object> requireOwnComment(long commentId, String username) {
        Map<String, Object> existing = store.findById(commentId)
                .orElseThrow(() -> new WorkflowEngineException(null, HttpStatus.NOT_FOUND,
                        "COMMENT_NOT_FOUND", "No comment found with id " + commentId));
        if (!username.equals(existing.get("commentBy"))) {
            throw new WorkflowEngineException(null, HttpStatus.FORBIDDEN, "NOT_YOUR_COMMENT",
                    "Only the comment's author may change or delete it.");
        }
        return existing;
    }

    private static String processInstanceIdOf(Map<String, Object> task) {
        Object info = task.get("processInfo");
        if (info instanceof Map<?, ?> map && map.get("processInstanceId") != null) {
            return map.get("processInstanceId").toString();
        }
        return null;
    }

    private static String statusNameOf(Map<String, Object> task) {
        Object status = task.get("status");
        if (status instanceof Map<?, ?> map && map.get("name") != null) {
            return map.get("name").toString();
        }
        return (status == null) ? null : status.toString();
    }

    private String requireUser() {
        UserDetailsJwt user = UserContextHolder.getContext();
        if (user == null || user.getUsername() == null || user.getUsername().isBlank()) {
            throw new UnauthorizedException("A valid JWT is required (Authorization: Bearer <token>).");
        }
        return user.getUsername();
    }

    private static String require(String value, String code, String message) {
        if (value == null || value.isBlank()) {
            throw new AssignmentValidationException(code, message);
        }
        return value.trim();
    }
}
