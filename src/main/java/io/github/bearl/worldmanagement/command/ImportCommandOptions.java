package io.github.bearl.worldmanagement.command;

record ImportCommandOptions(boolean detached, boolean regenerateIdentity) {

    static ImportCommandOptions defaults() {
        return new ImportCommandOptions(false, false);
    }
}
