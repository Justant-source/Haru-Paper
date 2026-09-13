package com.harupaper.server.common.exception;

import java.util.List;

public class ValidationException extends RuntimeException {
    private final List<FieldError> fieldErrors;

    public ValidationException(String message, List<FieldError> fieldErrors) {
        super(message);
        this.fieldErrors = fieldErrors;
    }

    public List<FieldError> getFieldErrors() {
        return fieldErrors;
    }

    public record FieldError(String path, String message) {
    }
}
