package io.github.bearl.worldmanagement.storage;

import io.github.bearl.worldmanagement.module.ModuleId;
import io.github.bearl.worldmanagement.module.WorldManagementModule;

/** Registers storage migration features with the module manager. */
public final class StorageModule implements WorldManagementModule {

    @Override
    public ModuleId id() {
        return ModuleId.STORAGE;
    }
}