package io.github.bearl.worldmanagement.world.lifecycle;

import io.github.bearl.worldmanagement.module.ModuleId;
import io.github.bearl.worldmanagement.module.WorldManagementModule;

/** Registers world lifecycle commands and services with the module manager. */
public final class LifecycleFeatureModule implements WorldManagementModule {

    @Override
    public ModuleId id() {
        return ModuleId.LIFECYCLE;
    }
}