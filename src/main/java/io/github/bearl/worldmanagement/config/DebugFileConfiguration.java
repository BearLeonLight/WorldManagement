package io.github.bearl.worldmanagement.config;

public record DebugFileConfiguration(int maximumFileSizeMib, int retainedFiles) {

    public static final int DEFAULT_MAXIMUM_FILE_SIZE_MIB = 10;
    public static final int DEFAULT_RETAINED_FILES = 5;

    public DebugFileConfiguration {
        if (maximumFileSizeMib < 1 || maximumFileSizeMib > 100) {
            throw new IllegalArgumentException("debug.file.max-file-size-mib must be between 1 and 100.");
        }
        if (retainedFiles < 1 || retainedFiles > 20) {
            throw new IllegalArgumentException("debug.file.retained-files must be between 1 and 20.");
        }
    }

    public long maximumFileSizeBytes() {
        return maximumFileSizeMib * 1024L * 1024L;
    }
}