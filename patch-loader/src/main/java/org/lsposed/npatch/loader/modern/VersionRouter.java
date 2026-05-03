package org.lsposed.npatch.loader.modern;

public final class VersionRouter {
    public static final int MODERN_MIN_API_VERSION = 101;
    public static final int LEGACY_MAX_API_VERSION = 94;

    public ModulePipeline determinePipeline(ModuleMetadata metadata) {
        if (metadata == null) {
            return ModulePipeline.UNSUPPORTED;
        }
        return determinePipeline(metadata.getMinApiVersion());
    }

    public ModulePipeline determinePipeline(int minApiVersion) {
        if (minApiVersion >= MODERN_MIN_API_VERSION) {
            return ModulePipeline.MODERN;
        }
        if (minApiVersion <= LEGACY_MAX_API_VERSION) {
            return ModulePipeline.LEGACY;
        }
        return ModulePipeline.UNSUPPORTED;
    }
}
