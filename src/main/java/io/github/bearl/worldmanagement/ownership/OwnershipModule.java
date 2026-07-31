package io.github.bearl.worldmanagement.ownership;

import io.github.bearl.worldmanagement.module.ModuleId;
import io.github.bearl.worldmanagement.module.WorldManagementModule;

/** Registers ownership, rank, and access-control features with the module manager. */
public final class OwnershipModule implements WorldManagementModule {

    @Override
    public ModuleId id() {
        return ModuleId.OWNERSHIP;
    }
}