package top.nkbe.npatch.patch;

import static top.nkbe.npatch.share.Constants.CONFIG_ASSET_PATH;
import static top.nkbe.npatch.share.Constants.EMBEDDED_MODULES_ASSET_PATH;
import static top.nkbe.npatch.share.Constants.LOADER_DEX_ASSET_PATH;
import static top.nkbe.npatch.share.Constants.ORIGINAL_APK_ASSET_PATH;
import static top.nkbe.npatch.share.Constants.PROXY_APP_COMPONENT_FACTORY;

import com.android.tools.build.apkzlib.sign.SigningExtension;
import com.android.tools.build.apkzlib.sign.SigningOptions;
import com.android.tools.build.apkzlib.zip.AlignmentRules;
import com.android.tools.build.apkzlib.zip.NestedZip;
import com.android.tools.build.apkzlib.zip.StoredEntry;
import com.android.tools.build.apkzlib.zip.ZFile;
import com.android.tools.build.apkzlib.zip.ZFileOptions;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.google.gson.Gson;
import com.wind.meditor.core.ManifestEditor;
import com.wind.meditor.property.AttributeItem;
import com.wind.meditor.property.ModificationProperty;
import com.wind.meditor.utils.NodeValue;

import org.apache.commons.io.FilenameUtils;
import top.nkbe.npatch.share.Constants;
import top.nkbe.npatch.share.LSPConfig;
import top.nkbe.npatch.share.PatchConfig;
import top.nkbe.npatch.patch.util.ApkSignatureHelper;
import top.nkbe.npatch.patch.util.JavaLogger;
import top.nkbe.npatch.patch.util.Logger;
import top.nkbe.npatch.patch.util.ManifestParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NPatch {

    private static final String NPATCH_KEYSTORE_PASSWORD_ENC = "a2hpbm9s";
    private static final String NPATCH_KEY_ALIAS_ENC = "MT8jag==";
    private static final String FPA_KEYSTORE_PASSWORD_ENC = "a2hpbm9sbWI=";
    private static final String FPA_KEY_ALIAS_ENC = "Oyoq";
    private static final int SECRET_XOR_KEY = 0x5a;

    static class PatchError extends Error {
        public PatchError(String message, Throwable cause) {
            super(message, cause);
        }

        PatchError(String message) {
            super(message);
        }
    }

    @Parameter(description = "apks")
    private List<String> apkPaths = new ArrayList<>();

    @Parameter(names = {"-h", "--help"}, help = true, order = 0, description = "Print this message")
    private boolean help = false;

    @Parameter(names = {"-o", "--output"}, description = "Output directory")
    private String outputPath = ".";

    @Parameter(names = {"-f", "--force"}, description = "Force overwrite exists output file")
    private boolean forceOverwrite = false;

    @Parameter(names = {"-p", "--newpackage"}, description = "Patch with new package")
    private String newPackageName = "";

    @Parameter(names = {"-d", "--debuggable"}, description = "Set app to be debuggable")
    private boolean debuggableFlag = false;

    @Parameter(names = {"-l", "--sigbypasslv"}, description = "Signature bypass mode. 0: None, 1: Basic, 2: High, 3: Extreme, 4: Seccomp. Extreme and Seccomp require --manager. default 1")
    private int sigbypassLevel = 1;

    @Parameter(names = {"--provider"}, description = "Inject Provider to manager data files")
    private boolean isInjectProvider = false;

    @Parameter(names = {"--installerSource"}, description = "Original app installer source")
    private String installerSource = "";

    @Parameter(names = {"--useMicroG"}, description = "Redirect GMS calls to community MicroG")
    private boolean useMicroG = false;

    @Parameter(names = {"--outputLog"}, description = "Output Log to Media")
    private boolean outputLog = true;

    @Parameter(names = {"--hidelibs"}, description = "Exempt basic environment checks by sanitizing ART and sensitive system library visibility")
    private boolean hideLibs = false;

    @Parameter(names = {"--cleartext", "--usesCleartextTraffic"}, description = "Force android:usesCleartextTraffic=\"true\" in manifest to allow plain HTTP traffic")
    private boolean usesCleartextTraffic = false;

    @Parameter(names = {"-k", "--keystore"}, arity = 4, description = "Set custom signature keystore. Followed by 4 arguments: keystore path, keystore password, keystore alias, keystore alias password")
    private List<String> keystoreArgs = null;

    @Parameter(names = {"-npa", "--npatch-keystore"}, description = "Use built-in NPatch keystore")
    private boolean useNpatchKeystore = false;

    @Parameter(names = {"-fpa", "--fpa-keystore"}, description = "Use built-in FPA keystore")
    private boolean useFpaKeystore = false;

    @Parameter(names = {"--manager"}, description = "Use manager (Cannot work with embedding modules)")
    private boolean useManager = false;

    @Parameter(names = {"--injectdex"}, description = "[Advanced/Opt-in] Physically inject the loader DEX into the main DEX sequence for isolated process and sub-process loading. Only enable this workaround if your modules explicitly need to hook sub-processes.")
    private boolean injectDex = false;

    @Parameter(names = {"-r", "--allowdown"}, description = "Allow downgrade installation by overriding versionCode to 1 (In most cases, the app can still get the correct versionCode)")
    private boolean overrideVersionCode = false;

    @Parameter(names = {"--versioncode"}, description = "Custom versionCode used when --allowdown is enabled. default 1")
    private int overrideVersionCodeValue = 1;

    @Parameter(names = {"--override-target-sdk"}, description = "Override patched app's target SDK version")
    private boolean overrideTargetSdk = false;

    @Parameter(names = {"--target-sdk"}, description = "Custom target SDK version value")
    private int overrideTargetSdkValue = 28;

    @Parameter(names = {"-v", "--verbose"}, description = "Verbose output")
    private boolean verbose = false;

    @Parameter(names = {"-m", "--embed"}, description = "Embed provided modules to apk")
    private List<String> modules = new ArrayList<>();

    private String packageName;

    private static final String ANDROID_MANIFEST_XML = "AndroidManifest.xml";
    private static final Pattern DEX_NAME_PATTERN = Pattern.compile("^classes(\\d*)\\.dex$");
    private static final String META_INF_PREFIX = "META-INF/";
    private static final String META_INF_MANIFEST = "META-INF/MANIFEST.MF";
    private static final HashSet<String> APK_SIGNATURE_EXTENSIONS = new HashSet<>(Arrays.asList(
            ".SF",
            ".RSA",
            ".DSA",
            ".EC"
    ));
    private static final HashSet<String> ARCHES = new HashSet<>(Arrays.asList(
            "arm64-v8a",
            "x86_64"
    ));

    private static final ZFileOptions Z_FILE_OPTIONS = new ZFileOptions()
            .setAlignmentRule(AlignmentRules.compose(
                    AlignmentRules.constantForSuffix(".so", 4096),
                    AlignmentRules.constantForSuffix(ORIGINAL_APK_ASSET_PATH, 4096),
                    AlignmentRules.constantForSuffix(".arsc", 4)
            ));

    private final JCommander jCommander;
    private final Logger logger;

    public NPatch(Logger logger, String... args) {
        jCommander = JCommander.newBuilder().addObject(this).build();
        try {
            jCommander.parse(args);
        } catch (ParameterException e) {
            logger.e(e.getMessage() + "\n");
            help = true;
        }
        if (apkPaths == null || apkPaths.isEmpty()) {
            logger.e("No apk specified\n");
            help = true;
        }
        if (!modules.isEmpty() && useManager) {
            logger.e("Should not use --embed and --manager at the same time\n");
            help = true;
        }
        if (keystoreArgs != null && (useNpatchKeystore || useFpaKeystore)) {
            logger.e("Cannot use -k with -npa or -fpa\n");
            help = true;
        }
        if (useNpatchKeystore && useFpaKeystore) {
            logger.e("Cannot use -npa and -fpa at the same time\n");
            help = true;
        }
        if (!useManager && sigbypassLevel > Constants.SIGBYPASS_HIGH) {
            logger.e("Extreme and Seccomp signature bypass modes cannot be used in integrated mode\n");
            help = true;
        }
        this.logger = logger;
        logger.verbose = verbose;
    }

    public static void main(String... args) throws IOException {
        NPatch npatch = new NPatch(new JavaLogger(), args);
        if (npatch.help) {
            npatch.jCommander.usage();
            return;
        }
        try {
            npatch.doCommandLine();
        } catch (PatchError e) {
            e.printStackTrace(System.err);
        }
    }

    public void doCommandLine() throws PatchError, IOException {
        for (var apk : apkPaths) {
            File srcApkFile = new File(apk).getAbsoluteFile();

            String apkFileName = srcApkFile.getName();

            var outputDir = new File(outputPath);
            //noinspection ResultOfMethodCallIgnored
            outputDir.mkdirs();

            File outputFile = new File(outputDir, String.format(
                    Locale.US, "%s-%d-npatched.apk",
                    FilenameUtils.getBaseName(apkFileName),
                    LSPConfig.instance.VERSION_CODE)
            ).getAbsoluteFile();

            if (outputFile.exists() && !forceOverwrite)
                throw new PatchError(outputFile.getAbsolutePath() + " exists. Use --force to overwrite");
            logger.i("Processing " + srcApkFile + " -> " + outputFile);

            patch(srcApkFile, outputFile);
        }
    }

    public void patch(File srcApkFile, File outputFile) throws PatchError, IOException {
        if (!srcApkFile.exists())
            throw new PatchError("The source apk file does not exit. Please provide a correct path.");

        //noinspection ResultOfMethodCallIgnored
        outputFile.delete();

        logger.d("apk path: " + srcApkFile);

        logger.i("Parsing original apk...");

        ManifestParser.Pair pair;
        try (var tempSrc = ZFile.openReadOnly(srcApkFile)) {
            var manifestEntry = tempSrc.get(ANDROID_MANIFEST_XML);
            if (manifestEntry == null) {
                throw new PatchError("Provided file is not a valid apk");
            }
            try (var is = manifestEntry.open()) {
                pair = ManifestParser.parseManifestFile(is);
            }
        }
        if (pair == null) {
            throw new PatchError("Failed to parse AndroidManifest.xml");
        }

        final String appComponentFactory = pair.appComponentFactory;
        final int minSdkVersion = pair.minSdkVersion;
        final int effectiveMinSdk = Math.max(minSdkVersion, 28);
        packageName = pair.packageName;

        String newPackage = newPackageName;
        if (newPackage == null || newPackage.isEmpty()) {
            newPackage = pair.packageName;
        }

        logger.d("original appComponentFactory class: " + appComponentFactory);
        logger.d("original split name: " + pair.splitName);
        logger.d("original minSdkVersion: " + minSdkVersion);

        logger.i("permissions size: " + (pair.permissions == null ? 0 : pair.permissions.size()));
        logger.i("use-permissions size: " + (pair.use_permissions == null ? 0 : pair.use_permissions.size()));
        logger.i("authorities size: " + (pair.authorities == null ? 0 : pair.authorities.size()));

        if (pair.hasIsolatedOrMultiProcessComponents()) {
            logger.i("--------------------------------------------------");
            logger.i("[Analysis Hint] Detected " + pair.getIsolatedOrMultiProcessCount() + " isolated/multi-process component(s):");
            for (String comp : pair.getIsolatedOrMultiProcessComponents()) {
                logger.i("  - " + comp);
            }
            if (!injectDex) {
                logger.i("If your modules need to hook these sub-processes (e.g. sandbox/isolated), you can enable --injectdex.");
                logger.i("(Default: disabled. Most UI and business modules only hook the main process)");
            } else {
                logger.i("--injectdex enabled: Loader DEX will be injected into the main DEX sequence for sub-process support.");
            }
            logger.i("--------------------------------------------------");
        }

        // Only the apk carrying the application element gets the loader; a split that declares
        // no appComponentFactory has nothing to hook, so it is repacked with synchronized attributes
        // and left installable alongside the base. A lone apk is never treated this way.
        final boolean hasSplitIdentity = (pair.splitName != null && !pair.splitName.isEmpty())
                || srcApkFile.getName().startsWith("split_");
        final boolean isSplit = apkPaths.size() > 1
                && hasSplitIdentity
                && appComponentFactory == null;
        final boolean embedOriginal = !isSplit && (sigbypassLevel >= Constants.SIGBYPASS_BASIC);

        try (ZFile dstZFile = ZFile.openReadWrite(outputFile, Z_FILE_OPTIONS);
             ZFile srcZFile = embedOriginal
                     ? dstZFile.addNestedZip((ignore) -> Constants.ORIGINAL_APK_ASSET_PATH, srcApkFile, false)
                     : ZFile.openReadOnly(srcApkFile)) {

            // sign apk with V2 + V3
            try {
                var keyStore = KeyStore.getInstance("BKS");
                if (useNpatchKeystore || (!useFpaKeystore && keystoreArgs == null)) {
                    logger.i("Register apk signer with built-in NPatch keystore (V2+V3, minSdk " + effectiveMinSdk + ")...");
                    registerBuiltinSigner(keyStore, dstZFile, "assets/npatch.key", NPATCH_KEYSTORE_PASSWORD_ENC, NPATCH_KEY_ALIAS_ENC, effectiveMinSdk);
                } else if (useFpaKeystore) {
                    logger.i("Register apk signer with built-in FPA keystore (V2+V3, minSdk " + effectiveMinSdk + ")...");
                    registerBuiltinSigner(keyStore, dstZFile, "assets/fpa_app.key", FPA_KEYSTORE_PASSWORD_ENC, FPA_KEY_ALIAS_ENC, effectiveMinSdk);
                } else if (keystoreArgs != null) {
                    logger.i("Register apk signer with custom keystore (V2+V3, minSdk " + effectiveMinSdk + ")...");
                    try (var is = new FileInputStream(keystoreArgs.get(0))) {
                        keyStore.load(is, keystoreArgs.get(1).toCharArray());
                    }
                    var entry = (KeyStore.PrivateKeyEntry) keyStore.getEntry(keystoreArgs.get(2), new KeyStore.PasswordProtection(keystoreArgs.get(3).toCharArray()));
                    new SigningExtension(SigningOptions.builder()
                            .setMinSdkVersion(effectiveMinSdk)
                            .setV1SigningEnabled(false)
                            .setV2SigningEnabled(true)
                            .setV3SigningEnabled(true)
                            .setCertificates((X509Certificate[]) entry.getCertificateChain())
                            .setKey(entry.getPrivateKey())
                            .build()).register(dstZFile);
                }
            } catch (Exception e) {
                throw new PatchError("Failed to register signer", e);
            }

            var manifestEntry = srcZFile.get(ANDROID_MANIFEST_XML);
            if (manifestEntry == null)
                throw new PatchError("Provided file is not a valid apk");

            if (isSplit) {
                String splitDisplayName = (pair.splitName != null && !pair.splitName.isEmpty())
                        ? pair.splitName
                        : srcApkFile.getName();
                logger.i("Packing split apk: " + splitDisplayName + "...");
                boolean needModifyManifest = !newPackage.equals(pair.packageName) || overrideVersionCode || overrideTargetSdk || minSdkVersion > 0;
                if (needModifyManifest) {
                    ModificationProperty splitProperty = new ModificationProperty();
                    if (overrideVersionCode) {
                        splitProperty.addManifestAttribute(new AttributeItem(NodeValue.Manifest.VERSION_CODE, overrideVersionCodeValue));
                    }
                    if (overrideTargetSdk) {
                        splitProperty.addUsesSdkAttribute(new AttributeItem(NodeValue.UsesSDK.TARGET_SDK_VERSION, overrideTargetSdkValue));
                    }
                    if (minSdkVersion > 0) {
                        splitProperty.addUsesSdkAttribute(new AttributeItem(NodeValue.UsesSDK.MIN_SDK_VERSION, minSdkVersion));
                    }
                    if (!newPackage.equals(pair.packageName)) {
                        splitProperty.addManifestAttribute(new AttributeItem(NodeValue.Manifest.PACKAGE, newPackage).setNamespace(null));
                    }
                    try (var xmlIs = manifestEntry.open();
                         var os = new ByteArrayOutputStream()) {
                        new ManifestEditor(xmlIs, os, splitProperty).processManifest();
                        dstZFile.add(ANDROID_MANIFEST_XML, new ByteArrayInputStream(os.toByteArray()));
                    } catch (Throwable e) {
                        logger.e("Failed to modify split manifest: " + e.getMessage() + ", falling back to copy");
                        try (var xmlIs = manifestEntry.open()) {
                            dstZFile.add(ANDROID_MANIFEST_XML, xmlIs);
                        }
                    }
                } else {
                    try (var xmlIs = manifestEntry.open()) {
                        dstZFile.add(ANDROID_MANIFEST_XML, xmlIs);
                    }
                }

                for (StoredEntry entry : srcZFile.entries()) {
                    String name = entry.getCentralDirectoryHeader().getName();
                    if (dstZFile.get(name) != null) continue;
                    if (name.equals(ANDROID_MANIFEST_XML)) continue;
                    if (isApkSignatureEntry(name))
                        continue;
                    try (InputStream is = entry.open()) {
                        if (name.endsWith(".so") || name.equals("resources.arsc")) {
                            dstZFile.add(name, is, false);
                        } else {
                            dstZFile.add(name, is);
                        }
                    } catch (IOException e) {
                        throw new PatchError("Failed to copy entry: " + name, e);
                    }
                }
                dstZFile.realign();
                return;
            }

            logger.i("Patching apk...");
            String originalSignature = null;
            if (sigbypassLevel > Constants.SIGBYPASS_NONE) {
                originalSignature = ApkSignatureHelper.getApkSignInfo(srcApkFile.getAbsolutePath());
                if (originalSignature == null || originalSignature.isEmpty()) {
                    throw new PatchError("get original signature failed");
                }
                logger.d("Original signature\n" + originalSignature);
            }

            // modify manifest
            final var config = new PatchConfig(
                    useManager,
                    debuggableFlag,
                    overrideVersionCode,
                    overrideVersionCodeValue,
                    sigbypassLevel,
                    originalSignature,
                    appComponentFactory,
                    isInjectProvider,
                    outputLog,
                    newPackage,
                    useMicroG,
                    hideLibs && sigbypassLevel > Constants.SIGBYPASS_NONE,
                    usesCleartextTraffic,
                    overrideTargetSdk,
                    overrideTargetSdkValue);
            final var configBytes = new Gson().toJson(config).getBytes(StandardCharsets.UTF_8);
            final var metadata = Base64.getEncoder().encodeToString(configBytes);
            try (var is = new ByteArrayInputStream(modifyManifestFile(manifestEntry.open(), metadata, minSdkVersion, pair.packageName, newPackage, originalSignature))) {
                dstZFile.add(ANDROID_MANIFEST_XML, is);
            } catch (Throwable e) {
                throw new PatchError("Error when modifying manifest", e);
            }

            logger.i("Adding config...");
            // save npatch config to asset..
            try (var is = new ByteArrayInputStream(configBytes)) {
                dstZFile.add(CONFIG_ASSET_PATH, is);
            } catch (Throwable e) {
                throw new PatchError("Error when saving config");
            }

            if (isInjectProvider){
                try (var is = getClass().getClassLoader().getResourceAsStream("assets/mtprovider.dex")) {
                    dstZFile.add("assets/npatch/mtprovider.dex", is);
                } catch (Throwable e) {
                    throw new PatchError("Error when adding dex", e);
                }
            }

            // Manager mode controls LoadedModule discovery, not bootstrap ownership. Keep every patched
            // APK independently bootable so its process never needs to read another package's APK.
            logger.i("Adding loader dex...");
            try (var is = getClass().getClassLoader().getResourceAsStream(LOADER_DEX_ASSET_PATH)) {
                if (is == null) {
                    throw new PatchError("Fatal: Could not find " + LOADER_DEX_ASSET_PATH + " in the patcher resources!");
                }
                dstZFile.add(LOADER_DEX_ASSET_PATH, is);
            } catch (Throwable e) {
                throw new PatchError("Error when adding loader.bin", e);
            }

            logger.i("Adding native lib...");
            for (String arch : ARCHES) {
                String entryName = "assets/npatch/so/" + arch + "/libnpatch.so";
                try (var is = getClass().getClassLoader().getResourceAsStream(entryName)) {
                    if (is == null) {
                        throw new PatchError("Fatal: Could not find " + entryName + " in the patcher resources!");
                    }
                    dstZFile.add(entryName, is, false);
                    logger.d("added " + entryName);
                } catch (Throwable e) {
                    throw new PatchError("Error when adding native lib " + arch, e);
                }
            }

            if (!useManager) {
                logger.i("Embedding modules...");
                embedModules(dstZFile);
            }

            // create zip link
            logger.d("Creating nested apk link...");

            int maxDexIndex = 0;
            for (StoredEntry entry : srcZFile.entries()) {
                String name = entry.getCentralDirectoryHeader().getName();
                if (dstZFile.get(name) != null) continue;
                if (embedOriginal && !injectDex && name.startsWith("classes") && name.endsWith(".dex")) continue;
                if (name.equals("AndroidManifest.xml")) continue;
                if (isApkSignatureEntry(name))
                    continue;
                maxDexIndex = Math.max(maxDexIndex, getDexIndex(name));

                boolean linked = false;
                if (srcZFile instanceof NestedZip) {
                    try {
                        linked = ((NestedZip) srcZFile).addFileLink(name, name);
                    } catch (IOException e) {
                        logger.e("Failed to link entry: " + name + ", falling back to copy.");
                    }
                }

                if (!linked) {
                    try (InputStream is = entry.open()) {
                        if (name.endsWith(".so") || name.equals("resources.arsc")) {
                            dstZFile.add(name, is, false);
                        } else {
                            dstZFile.add(name, is);
                        }
                    } catch (IOException e) {
                        throw new PatchError("Failed to copy entry: " + name, e);
                    }
                }
            }

            logger.i("Adding metaloader dex...");
            try (var is = getClass().getClassLoader().getResourceAsStream(Constants.META_LOADER_DEX_ASSET_PATH)) {
                if (embedOriginal && !injectDex) {
                    dstZFile.add("classes.dex", is);
                } else {
                    int nextIdx = getNextAvailableDexIndex(dstZFile, srcZFile, false);
                    dstZFile.add("classes" + nextIdx + ".dex", is);
                    logger.i("Metaloader dex injected as classes" + nextIdx + ".dex");
                }
            } catch (Throwable e) {
                throw new PatchError("Error when adding metaloader dex", e);
            }

            dstZFile.realign();
            logger.i("Writing apk...");
        }
        logger.i("Done. Output APK: " + outputFile.getAbsolutePath());
    }

    private static int getNextAvailableDexIndex(ZFile dstZFile, ZFile srcZFile, boolean embedOriginal) {
        int maxIndex = 0;
        if (dstZFile != null) {
            for (StoredEntry entry : dstZFile.entries()) {
                maxIndex = Math.max(maxIndex, getDexIndex(entry.getCentralDirectoryHeader().getName()));
            }
        }
        if (!embedOriginal && srcZFile != null) {
            for (StoredEntry entry : srcZFile.entries()) {
                maxIndex = Math.max(maxIndex, getDexIndex(entry.getCentralDirectoryHeader().getName()));
            }
        }
        return Math.max(1, maxIndex) + 1;
    }

    private static int getDexIndex(String name) {
        if (name == null) {
            return 0;
        }
        Matcher matcher = DEX_NAME_PATTERN.matcher(name);
        if (!matcher.matches()) {
            return 0;
        }
        String group = matcher.group(1);
        if (group == null || group.isEmpty()) {
            return 1;
        }
        try {
            return Integer.parseInt(group);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static boolean isApkSignatureEntry(String name) {
        if (name == null || !name.startsWith(META_INF_PREFIX)) {
            return false;
        }
        String upperName = name.toUpperCase(Locale.ROOT);
        // v1 簽名的 Manifest 只能移除固定檔名，避免誤刪其他 .MF 資源。
        if (META_INF_MANIFEST.equals(upperName)) {
            return true;
        }
        int fileNameStart = upperName.lastIndexOf('/') + 1;
        if (fileNameStart >= upperName.length()) {
            return false;
        }
        String fileName = upperName.substring(fileNameStart);
        int extensionStart = fileName.lastIndexOf('.');
        // 只看 META-INF 下的實際檔名副檔名，避免子路徑或目錄名稱誤判。
        return extensionStart > 0
                && APK_SIGNATURE_EXTENSIONS.contains(fileName.substring(extensionStart));
    }

    private void embedModules(ZFile zFile) {
        for (var LoadedModule : modules) {
            File file = new File(LoadedModule);
            try (var apk = ZFile.openReadOnly(new File(LoadedModule));
                 var fileIs = new FileInputStream(file)) {

                var manifestEntry = apk.get(ANDROID_MANIFEST_XML);
                if (manifestEntry == null) throw new IOException("Manifest not found in LoadedModule");

                try (var xmlIs = manifestEntry.open()) {
                    var manifest = Objects.requireNonNull(ManifestParser.parseManifestFile(xmlIs));
                    var packageName = manifest.packageName;
                    logger.i("  - " + packageName);
                    zFile.add(EMBEDDED_MODULES_ASSET_PATH + packageName + ".apk", fileIs);
                }
            } catch (Exception e) {
                logger.e(LoadedModule + " does not exist or is not a valid apk file. error:" + e);
            }
        }
    }

    private void registerBuiltinSigner(KeyStore keyStore, ZFile dstZFile, String keystoreResource, String passwordToken, String aliasToken, int minSdkVersion) throws Exception {
        var password = decodeSecretChars(passwordToken);
        try {
            try (var is = getClass().getClassLoader().getResourceAsStream(keystoreResource)) {
                keyStore.load(is, password);
            }

            var alias = decodeSecretString(aliasToken);
            var entry = (KeyStore.PrivateKeyEntry) keyStore.getEntry(alias, new KeyStore.PasswordProtection(password));
            new SigningExtension(SigningOptions.builder()
                    .setMinSdkVersion(minSdkVersion)
                    .setV1SigningEnabled(false)
                    .setV2SigningEnabled(true)
                    .setV3SigningEnabled(true)
                    .setCertificates((X509Certificate[]) entry.getCertificateChain())
                    .setKey(entry.getPrivateKey())
                    .build()).register(dstZFile);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static char[] decodeSecretChars(String token) {
        byte[] encoded = Base64.getDecoder().decode(token);
        char[] decoded = new char[encoded.length];
        for (int i = 0; i < encoded.length; i++) {
            decoded[i] = (char) (encoded[i] ^ SECRET_XOR_KEY);
        }
        return decoded;
    }

    private static String decodeSecretString(String token) {
        char[] decoded = decodeSecretChars(token);
        try {
            return new String(decoded);
        } finally {
            Arrays.fill(decoded, '\0');
        }
    }

    private byte[] modifyManifestFile(InputStream is, String metadata, int minSdkVersion, String originPackage, String newPackage, String originalSignature) throws IOException {
        ModificationProperty property = new ModificationProperty();

        String targetPackage = (newPackage != null && !newPackage.isEmpty()) ? newPackage : originPackage;

        if (overrideVersionCode) {
            property.addManifestAttribute(new AttributeItem(NodeValue.Manifest.VERSION_CODE, overrideVersionCodeValue));
        }

        if (overrideTargetSdk) {
            property.addUsesSdkAttribute(new AttributeItem(NodeValue.UsesSDK.TARGET_SDK_VERSION, overrideTargetSdkValue));
        }

        if (minSdkVersion < 28) {
            property.addUsesSdkAttribute(new AttributeItem(NodeValue.UsesSDK.MIN_SDK_VERSION, 28));
        }
        property.addApplicationAttribute(new AttributeItem(NodeValue.Application.DEBUGGABLE, debuggableFlag));
        property.addApplicationAttribute(new AttributeItem("appComponentFactory", PROXY_APP_COMPONENT_FACTORY));
        if (usesCleartextTraffic) {
            property.addApplicationAttribute(new AttributeItem("usesCleartextTraffic", true));
        }

        if (!targetPackage.equals(originPackage)) {
            property.addManifestAttribute(new AttributeItem(NodeValue.Manifest.PACKAGE, targetPackage).setNamespace(null));
            property.setAuthorityMapper(authority -> remapAuthority(authority, originPackage, targetPackage));
        }

        if (!modules.isEmpty()) {
            addOrReplaceMetaData(property, "xposedmodule", "true");
            addOrReplaceMetaData(property, "xposeddescription", "NPatch Embed LoadedModule");
            addOrReplaceMetaData(property, "xposedminversion", "93");
        }

        addOrReplaceMetaData(property, "npatch", metadata);

        // 注入 MicroG 偽裝簽名與權限
        if (useMicroG && originalSignature != null && !originalSignature.isEmpty()) {
            try {
                addOrReplaceMetaData(property, "fake-signature", originalSignature);
                property.addUsesPermission("android.permission.FAKE_PACKAGE_SIGNATURE");
                logger.d("Added fake-signature metadata for MicroG compatibility");
            } catch (Exception e) {
                logger.e("Failed to add fake-signature: " + e.getMessage());
            }
        }

        // TODO: replace query_all with queries -> manager
        if (useManager)
            property.addUsesPermission("android.permission.QUERY_ALL_PACKAGES");

        // 處理注入 Provider 的邏輯
        if (isInjectProvider){
            String injectedAuthority = targetPackage + ".MTDataFilesProvider";
            List<AttributeItem> providerAttrs = new ArrayList<>();
            providerAttrs.add(new AttributeItem("name", "bin.mt.file.content.MTDataFilesProvider"));
            providerAttrs.add(new AttributeItem("permission", "android.permission.MANAGE_DOCUMENTS"));
            providerAttrs.add(new AttributeItem("exported", true));
            providerAttrs.add(new AttributeItem("authorities", injectedAuthority));
            providerAttrs.add(new AttributeItem("grantUriPermissions", true));

            property.addDeleteProviderAuthorities(injectedAuthority);
            property.addProvider(providerAttrs, "android.content.action.DOCUMENTS_PROVIDER");
        }

        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            new ManifestEditor(is, os, property).processManifest();
            return os.toByteArray();
        } finally {
            if (is != null) is.close();
        }
    }

    private static void addOrReplaceMetaData(ModificationProperty property, String name, String value) {
        property.addDeleteMetaData(name);
        property.addMetaData(new ModificationProperty.MetaData(name, value));
    }

    private static String remapAuthority(String authority, String originPackage, String targetPackage) {
        if (authority == null || originPackage == null || targetPackage == null || originPackage.equals(targetPackage)) {
            return authority;
        }
        if (authority.equals(originPackage)) {
            return targetPackage;
        }
        if (authority.startsWith(originPackage + ".")) {
            return targetPackage + authority.substring(originPackage.length());
        }
        return authority;
    }
}
