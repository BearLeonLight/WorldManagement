package io.github.bearl.worldmanagement.storage;

/** Signals a valid storage document from a newer schema than this plugin supports. */
public final class UnsupportedStorageSchemaException extends StorageException {

    public UnsupportedStorageSchemaException(final String message) {
        super(message);
    }
}