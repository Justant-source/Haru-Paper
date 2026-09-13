package com.harupaper.server.common.exception;

import java.util.List;

public class ConflictException extends RuntimeException {
    private final List<String> conflictingIds;

    public ConflictException(String message, List<String> conflictingIds) {
        super(message);
        this.conflictingIds = conflictingIds;
    }

    public List<String> getConflictingIds() {
        return conflictingIds;
    }
}
