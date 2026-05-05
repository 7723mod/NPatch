package top.nkbe.npatch.share;

public class PatchConfig {

    public final boolean useManager;
    public final boolean debuggable;
    public final boolean overrideVersionCode;
    public final boolean injectProvider;
    public final boolean outputLog;
    public final int sigBypassLevel;
    public final String originalSignature;
    public final String appComponentFactory;
    public final LSPConfig lspConfig;
    public final String managerPackageName;
    public final String newPackage;
    public final boolean useMicroG;

    public PatchConfig(
            boolean useManager,
            boolean debuggable,
            boolean overrideVersionCode,
            int sigBypassLevel,
            String originalSignature,
            String appComponentFactory,
            boolean injectProvider,
            boolean outputLog,
            String newPackage,
            boolean useMicroG
    ) {
        this.useManager = useManager;
        this.debuggable = debuggable;
        this.overrideVersionCode = overrideVersionCode;
        this.sigBypassLevel = sigBypassLevel;
        this.originalSignature = originalSignature;
        this.appComponentFactory = appComponentFactory;
        this.injectProvider = injectProvider;
        this.managerPackageName = Constants.MANAGER_PACKAGE_NAME;
        this.newPackage = newPackage;
        this.outputLog = outputLog;
        this.useMicroG = useMicroG;

        this.lspConfig = LSPConfig.instance;
        this.lspConfig.sigBypassLevel = sigBypassLevel;
    }
}