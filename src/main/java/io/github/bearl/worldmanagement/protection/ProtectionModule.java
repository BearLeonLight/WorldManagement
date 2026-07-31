package io.github.bearl.worldmanagement.protection;

import io.github.bearl.worldmanagement.module.ModuleId;
import io.github.bearl.worldmanagement.module.WorldManagementModule;

/** Registers cache-only world protection with the module manager. */
public final class ProtectionModule implements WorldManagementModule {

    @Override
    public ModuleId id() {
        return ModuleId.PROTECTION;
    }
}