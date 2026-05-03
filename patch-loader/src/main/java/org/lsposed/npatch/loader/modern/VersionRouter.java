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
        return determinePipeline(minApiVersion, true, true);
    }

    public ModulePipeline determinePipeline(
            int minApiVersion,
            boolean hasModernEntrypoint,
            boolean hasLegacyEntrypoint) {
        if (minApiVersion >= MODERN_MIN_API_VERSION) {
            return hasModernEntrypoint ? ModulePipeline.MODERN : ModulePipeline.UNSUPPORTED;
        }
        if (minApiVersion <= LEGACY_MAX_API_VERSION) {
            return hasLegacyEntrypoint ? ModulePipeline.LEGACY : ModulePipeline.UNSUPPORTED;
        }
        return ModulePipeline.UNSUPPORTED;
    }
}
