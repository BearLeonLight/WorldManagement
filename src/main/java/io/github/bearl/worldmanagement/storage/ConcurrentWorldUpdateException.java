package io.github.bearl.worldmanagement.storage;

public final class ConcurrentWorldUpdateException extends RuntimeException {

    public ConcurrentWorldUpdateException(final String worldName) {
        super("World metadata was updated concurrently: " + worldName);
    }
}
