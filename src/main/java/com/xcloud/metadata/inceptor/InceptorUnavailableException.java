package com.xcloud.metadata.inceptor;

/** Raised when the database source is disabled, misconfigured or unreachable. */
public class InceptorUnavailableException extends RuntimeException {

    public InceptorUnavailableException(String message) {
        super(message);
    }

    public InceptorUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
