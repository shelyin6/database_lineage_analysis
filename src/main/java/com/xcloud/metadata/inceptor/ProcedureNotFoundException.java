package com.xcloud.metadata.inceptor;

/** Raised when a requested procedure does not exist in the metadata table. */
public class ProcedureNotFoundException extends RuntimeException {

    public ProcedureNotFoundException(String message) {
        super(message);
    }
}
