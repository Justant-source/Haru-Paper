package com.harupaper.server.common.exception;

public class UnsupportedMediaTypeAppException extends RuntimeException {
    public UnsupportedMediaTypeAppException(String message) {
        super(message);
    }

    public UnsupportedMediaTypeAppException(String message, Throwable cause) {
        super(message, cause);
    }
}
