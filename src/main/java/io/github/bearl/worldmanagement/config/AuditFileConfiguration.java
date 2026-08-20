package io.github.bearl.worldmanagement.config;

/** Size and archive retention contract for the YAML provider's JSONL audit log. */
public record AuditFileConfiguration(int maximumFileSizeMib, int retainedFiles) {

    public static final int DEFAULT_MAXIMUM_FILE_SIZE_MIB = 10;
    public static final int DEFAULT_RETAINED_FILES = 10;

    public AuditFileConfiguration {
        if (maximumFileSizeMib < 1 || maximumFileSizeMib > 100) {
            throw new IllegalArgumentException("audit.file.max-file-size-mib must be between 1 and 100.");
        }
        if (retainedFiles < 1 || retainedFiles > 100) {
            throw new IllegalArgumentException("audit.file.retained-files must be between 1 and 100.");
        }
    }

    public long maximumFileSizeBytes() {
        return maximumFileSizeMib * 1024L * 1024L;
    }
}
