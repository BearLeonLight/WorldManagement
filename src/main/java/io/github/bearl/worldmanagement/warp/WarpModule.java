package io.github.bearl.worldmanagement.warp;

import io.github.bearl.worldmanagement.module.ModuleId;
import io.github.bearl.worldmanagement.module.WorldManagementModule;

/** Registers the Warp feature with the module manager. */
public final class WarpModule implements WorldManagementModule {

    @Override
    public ModuleId id() {
        return ModuleId.WARP;
    }
}