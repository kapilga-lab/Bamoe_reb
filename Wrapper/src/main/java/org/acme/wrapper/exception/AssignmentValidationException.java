package org.acme.wrapper.exception;

/**
 * Thrown for client-side assignment problems (missing mandatory field, missing
 * group/role filter, CHOICE value not in the allowed candidate list, …). Maps to 400.
 */
public class AssignmentValidationException extends RuntimeException {

    private final String code;

    public AssignmentValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
