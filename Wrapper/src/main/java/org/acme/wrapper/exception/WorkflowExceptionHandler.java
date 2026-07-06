package org.acme.wrapper.exception;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps wrapper domain exceptions to clean JSON HTTP responses, keeping the
 * controller free of error-handling concerns.
 */
@RestControllerAdvice
public class WorkflowExceptionHandler {

    @ExceptionHandler(WorkflowNotFoundException.class)
    public ResponseEntity<Object> handleNotFound(WorkflowNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(body("WORKFLOW_NOT_FOUND", ex.getWorkflowId(), ex.getMessage()));
    }

    @ExceptionHandler(WorkflowEngineException.class)
    public ResponseEntity<Object> handleEngine(WorkflowEngineException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(body(ex.getCode(), ex.getWorkflowId(), ex.getMessage()));
    }

    @ExceptionHandler(AssignmentValidationException.class)
    public ResponseEntity<Object> handleAssignment(AssignmentValidationException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", ex.getCode());
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Object> handleUnauthorized(UnauthorizedException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "UNAUTHORIZED");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    private Map<String, Object> body(String code, String workflowId, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("workflow", workflowId);
        body.put("message", message);
        return body;
    }
}
