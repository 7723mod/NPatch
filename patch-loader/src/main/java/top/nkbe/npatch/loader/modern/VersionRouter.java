package top.nkbe.npatch.loader.modern;

public final class VersionRouter {
    public static final int FRAMEWORK_API_VERSION = 101;
    public static final int MODERN_TARGET_API_VERSION = 101;
    public static final int LEGACY_MAX_API_VERSION = 94;

    public ModulePipeline determinePipeline(ModuleMetadata metadata) {
        if (metadata == null) {
            return ModulePipeline.UNSUPPORTED;
        }
        return metadata.getPipeline();
    }

    public ModulePipeline determinePipeline(int minApiVersion) {
        return determinePipeline(minApiVersion, minApiVersion, true, true);
    }

    public ModulePipeline determinePipeline(
            int minApiVersion,
            boolean hasModernEntrypoint,
            boolean hasLegacyEntrypoint) {
        return determinePipeline(
                minApiVersion,
                minApiVersion,
                hasModernEntrypoint,
                hasLegacyEntrypoint);
    }

    public ModulePipeline determinePipeline(
            int minApiVersion,
            int targetApiVersion,
            boolean hasModernEntrypoint,
            boolean hasLegacyEntrypoint) {
        if (minApiVersion <= FRAMEWORK_API_VERSION && targetApiVersion >= MODERN_TARGET_API_VERSION) {
            return hasModernEntrypoint ? ModulePipeline.MODERN : ModulePipeline.UNSUPPORTED;
        }
        if (minApiVersion <= LEGACY_MAX_API_VERSION) {
            return hasLegacyEntrypoint ? ModulePipeline.LEGACY : ModulePipeline.UNSUPPORTED;
        }
        return ModulePipeline.UNSUPPORTED;
    }
}
