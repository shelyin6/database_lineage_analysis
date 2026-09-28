package com.xcloud.metadata.inceptor;

/**
 * Raised when a catalogue operation exceeds
 * {@code metadata.inceptor.request-timeout-seconds}.
 *
 * <p>Reported as HTTP 504 so the caller can tell "the database is too slow" apart from
 * "the database is unreachable".
 */
public class InceptorTimeoutException extends RuntimeException {

    public InceptorTimeoutException(String message) {
        super(message);
    }
}
