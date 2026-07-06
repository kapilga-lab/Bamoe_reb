package org.acme.wrapper.service;

import org.springframework.http.HttpStatus;

/**
 * Result of the unified executeTask endpoint: the HTTP status to return
 * (201 started / 200 completed / 200 CHOICE candidates) and the body.
 */
public record ExecOutcome(HttpStatus status, Object body) {
}
