package org.acme.wrapper.exception;

/**
 * Thrown when {@code assignmentStrategy} is not one of the supported values. Maps to 400.
 */
public class InvalidAssignmentStrategyException extends AssignmentValidationException {

    public InvalidAssignmentStrategyException(String strategy, String allowed) {
        super("INVALID_ASSIGNMENT_STRATEGY",
                "Invalid assignmentStrategy '" + strategy + "'. Allowed values: " + allowed);
    }
}
