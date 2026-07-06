package org.acme.wrapper.exception;

/**
 * Thrown when an operation requires an authenticated JWT user but none is present. Maps to 401.
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
