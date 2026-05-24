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
import java.util.stream.Collectors;

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

    @Parameter(names = {"-l", "--sigbypasslv"}, description = "Signature bypass mode. 0: None, 1: Basic, 2: High, 3: Extreme, 4: Seccomp. default 1")
    private int sigbypassLevel = 1;

    @Parameter(names = {"--injectdex"}, description = "Inject directly the loader dex file into the original application package")
    private boolean injectDex = false;

    @Parameter(names = {"--provider"}, description = "Inject Provider to manager data files")
    private boolean isInjectProvider = false;

    @Parameter(names = {"--installerSource"}, description = "Original app installer source")
    private String installerSource = "";

    @Parameter(names = {"--useMicroG"}, description = "Redirect GMS calls to community MicroG")
    private boolean useMicroG = false;

    @Parameter(names = {"--outputLog"}, description = "Output Log to Media")
    private boolean outputLog = true;

    @Parameter(names = {"-k", "--keystore"}, arity = 4, description = "Set custom signature keystore. Followed by 4 arguments: keystore path, keystore password, keystore alias, keystore alias password")
    private List<String> keystoreArgs = null;

    @Parameter(names = {"-npa", "--npatch-keystore"}, description = "Use built-in NPatch keystore")
    private boolean useNpatchKeystore = false;

    @Parameter(names = {"-fpa", "--fpa-keystore"}, description = "Use built-in FPA keystore")
    private boolean useFpaKeystore = false;

    @Parameter(names = {"--manager"}, description = "Use manager (Cannot work with embedding modules)")
    private boolean useManager = false;

    @Parameter(names = {"-r", "--allowdown"}, description = "Allow downgrade installation by overriding versionCode to 1 (In most cases, the app can still get the correct versionCode)")
    private boolean overrideVersionCode = false;

    @Parameter(names = {"-v", "--verbose"}, description = "Verbose output")
    private boolean verbose = false;

    @Parameter(names = {"-m", "--embed"}, description = "Embed provided modules to apk")
    private List<String> modules = new ArrayList<>();

    private String packageName;

    private static final String ANDROID_MANIFEST_XML = "AndroidManifest.xml";
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
            .setNoTimestamps(true)
            .setAlignmentRule(AlignmentRules.compose(
                    AlignmentRules.constantForSuffix(".so", 16384),
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
                    Locale.getDefault(), "%s-%d-npatched.apk",
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

        boolean embedOriginal = sigbypassLevel >= Constants.SIGBYPASS_BASIC;

        try (ZFile dstZFile = ZFile.openReadWrite(outputFile, Z_FILE_OPTIONS);
             ZFile srcZFile = embedOriginal
                     ? dstZFile.addNestedZip((ignore) -> Constants.ORIGINAL_APK_ASSET_PATH, srcApkFile, false)
                     : ZFile.openReadOnly(srcApkFile)) {

            // sign apk
            try {
                var keyStore = KeyStore.getInstance("BKS");
                if (useNpatchKeystore || (!useFpaKeystore && keystoreArgs == null)) {
                    logger.i("Register apk signer with built-in NPatch keystore...");
                    registerBuiltinSigner(keyStore, dstZFile, "assets/npatch.key", NPATCH_KEYSTORE_PASSWORD_ENC, NPATCH_KEY_ALIAS_ENC);
                } else if (useFpaKeystore) {
                    logger.i("Register apk signer with built-in FPA keystore...");
                    registerBuiltinSigner(keyStore, dstZFile, "assets/fpa_app.key", FPA_KEYSTORE_PASSWORD_ENC, FPA_KEY_ALIAS_ENC);
                } else if (keystoreArgs != null) {
                    logger.i("Register apk signer with custom keystore...");
                    try (var is = new FileInputStream(keystoreArgs.get(0))) {
                        keyStore.load(is, keystoreArgs.get(1).toCharArray());
                    }
                    var entry = (KeyStore.PrivateKeyEntry) keyStore.getEntry(keystoreArgs.get(2), new KeyStore.PasswordProtection(keystoreArgs.get(3).toCharArray()));
                    new SigningExtension(SigningOptions.builder()
                            .setMinSdkVersion(27)
                            .setV2SigningEnabled(true)
                            .setCertificates((X509Certificate[]) entry.getCertificateChain())
                            .setKey(entry.getPrivateKey())
                            .build()).register(dstZFile);
                }
            } catch (Exception e) {
                throw new PatchError("Failed to register signer", e);
            }

            String originalSignature = null;
            if (sigbypassLevel > Constants.SIGBYPASS_NONE) {
                originalSignature = ApkSignatureHelper.getApkSignInfo(srcApkFile.getAbsolutePath());
                if (originalSignature == null || originalSignature.isEmpty()) {
                    throw new PatchError("get original signature failed");
                }
                logger.d("Original signature\n" + originalSignature);
            }

            // copy out manifest file from zlib
            var manifestEntry = srcZFile.get(ANDROID_MANIFEST_XML);
            if (manifestEntry == null)
                throw new PatchError("Provided file is not a valid apk");

            String newPackage = newPackageName;

            int minSdkVersion;
            // parse the app appComponentFactory full name from the manifest file
            final String appComponentFactory;
            ManifestParser.Pair pair;
            try (var is = manifestEntry.open()) {
                pair = ManifestParser.parseManifestFile(is);
                if (pair == null)
                    throw new PatchError("Failed to parse AndroidManifest.xml");
                appComponentFactory = pair.appComponentFactory;
                minSdkVersion = pair.minSdkVersion;
                packageName = pair.packageName;
                logger.d("original appComponentFactory class: " + appComponentFactory);
                logger.d("original minSdkVersion: " + minSdkVersion);

                if (newPackage == null || newPackage.isEmpty()) {
                    newPackage = pair.packageName;
                }

                logger.i("permissions size: " + (pair.permissions == null ? 0 : pair.permissions.size()));
                logger.i("use-permissions size: " + (pair.use_permissions == null ? 0 : pair.use_permissions.size()));
                logger.i("authorities size: " + (pair.authorities == null ? 0 : pair.authorities.size()));
            }

            final boolean skipSplit = apkPaths.size() > 1 && srcApkFile.getName().startsWith("split_") && appComponentFactory == null;
            if (skipSplit) {
                logger.i("Packing split apk...");
                for (StoredEntry entry : srcZFile.entries()) {
                    String name = entry.getCentralDirectoryHeader().getName();
                    if (dstZFile.get(name) != null) continue;
                    if (isApkSignatureEntry(name))
                        continue;
                    if (srcZFile instanceof NestedZip) {
                        ((NestedZip) srcZFile).addFileLink(name, name);
                    } else {
                        try (InputStream is = entry.open()) {
                            dstZFile.add(name, is);
                        }
                    }
                }
                return;
            }

            logger.i("Patching apk...");
            // modify manifest
            final var config = new PatchConfig(useManager, debuggableFlag, overrideVersionCode, sigbypassLevel, originalSignature, appComponentFactory, isInjectProvider, outputLog, newPackage, useMicroG);
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

            logger.i("Adding metaloader dex...");
            try (var is = getClass().getClassLoader().getResourceAsStream(Constants.META_LOADER_DEX_ASSET_PATH)) {
                if (is == null) throw new PatchError("Meta loader dex not found");
                if (embedOriginal) {
                    dstZFile.add("classes.dex", is);
                } else {
                    var dexCount = srcZFile.entries().stream().filter(entry -> {
                        var name = entry.getCentralDirectoryHeader().getName();
                        return name.startsWith("classes") && name.endsWith(".dex");
                    }).count() + 1; // Used .count() instead of .collect().size() for efficiency
                    dstZFile.add("classes" + dexCount + ".dex", is);
                }
            } catch (Throwable e) {
                throw new PatchError("Error when adding dex", e);
            }

            if (isInjectProvider){
                try (var is = getClass().getClassLoader().getResourceAsStream("assets/mtprovider.dex")) {
                    dstZFile.add("assets/npatch/mtprovider.dex", is);
                } catch (Throwable e) {
                    throw new PatchError("Error when adding dex", e);
                }
            }

            if (!useManager) {
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
                // copy so and dex files into the unzipped apk
                // do not put libnpatch.so into apk!lib because x86 native bridge causes crash
                for (String arch : ARCHES) {
                    String entryName = "assets/npatch/so/" + arch + "/libnpatch.so";
                    try (var is = getClass().getClassLoader().getResourceAsStream(entryName)) {
                        if (is != null) {
                            dstZFile.add(entryName, is, false); // no compress for so
                            logger.d("added " + entryName);
                        } else {
                            logger.e("Native lib not found: " + entryName);
                        }
                    } catch (Throwable e) {
                        // More exception info
                        throw new PatchError("Error when adding native lib", e);
                    }
                }

                logger.i("Embedding modules...");
                embedModules(dstZFile);
            }

            // create zip link
            logger.d("Creating nested apk link...");

            for (StoredEntry entry : srcZFile.entries()) {
                String name = entry.getCentralDirectoryHeader().getName();
                if (dstZFile.get(name) != null) continue;
                if (embedOriginal && name.startsWith("classes") && name.endsWith(".dex")) continue;
                if (name.equals("AndroidManifest.xml")) continue;
                if (isApkSignatureEntry(name))
                    continue;

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

            dstZFile.realign();
            logger.i("Writing apk...");
        }
        logger.i("Done. Output APK: " + outputFile.getAbsolutePath());
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
        for (var module : modules) {
            File file = new File(module);
            try (var apk = ZFile.openReadOnly(new File(module));
                 var fileIs = new FileInputStream(file)) {

                var manifestEntry = apk.get(ANDROID_MANIFEST_XML);
                if (manifestEntry == null) throw new IOException("Manifest not found in module");

                try (var xmlIs = manifestEntry.open()) {
                    var manifest = Objects.requireNonNull(ManifestParser.parseManifestFile(xmlIs));
                    var packageName = manifest.packageName;
                    logger.i("  - " + packageName);
                    zFile.add(EMBEDDED_MODULES_ASSET_PATH + packageName + ".apk", fileIs);
                }
            } catch (Exception e) {
                logger.e(module + " does not exist or is not a valid apk file. error:" + e);
            }
        }
    }

    private void registerBuiltinSigner(KeyStore keyStore, ZFile dstZFile, String keystoreResource, String passwordToken, String aliasToken) throws Exception {
        var password = decodeSecretChars(passwordToken);
        try {
            try (var is = getClass().getClassLoader().getResourceAsStream(keystoreResource)) {
                keyStore.load(is, password);
            }

            var alias = decodeSecretString(aliasToken);
            var entry = (KeyStore.PrivateKeyEntry) keyStore.getEntry(alias, new KeyStore.PasswordProtection(password));
            new SigningExtension(SigningOptions.builder()
                    .setMinSdkVersion(27)
                    .setV2SigningEnabled(true)
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
            property.addManifestAttribute(new AttributeItem(NodeValue.Manifest.VERSION_CODE, 1));
        }

        if (minSdkVersion > 0)
            property.addUsesSdkAttribute(new AttributeItem(NodeValue.UsesSDK.MIN_SDK_VERSION, minSdkVersion));
        else
            property.addUsesSdkAttribute(new AttributeItem(NodeValue.UsesSDK.MIN_SDK_VERSION, 27));
        property.addApplicationAttribute(new AttributeItem(NodeValue.Application.DEBUGGABLE, debuggableFlag));
        property.addApplicationAttribute(new AttributeItem("appComponentFactory", PROXY_APP_COMPONENT_FACTORY));
        property.addApplicationAttribute(new AttributeItem("isSplitRequired", false));

        if (!targetPackage.equals(originPackage)) {
            property.addManifestAttribute(new AttributeItem(NodeValue.Manifest.PACKAGE, targetPackage).setNamespace(null));
        }

        modules.forEach(module -> {
            property.addMetaData(new ModificationProperty.MetaData("xposedmodule", "true"));
            property.addMetaData(new ModificationProperty.MetaData("xposeddescription", "NPatch Embed Module"));
            property.addMetaData(new ModificationProperty.MetaData("xposedminversion", "93"));
        });

        property.addMetaData(new ModificationProperty.MetaData("npatch", metadata));

        // 注入 MicroG 偽裝簽名與權限
        if (useMicroG && originalSignature != null && !originalSignature.isEmpty()) {
            try {
                byte[] sigBytes = Base64.getDecoder().decode(originalSignature);
                StringBuilder hex = new StringBuilder();
                for (byte b : sigBytes) {
                    hex.append(String.format("%02x", b));
                }
                property.addMetaData(new ModificationProperty.MetaData("fake-signature", hex.toString()));
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
            HashMap<String,String> providerMap = new HashMap<>();
            providerMap.put("name","bin.mt.file.content.MTDataFilesProvider");
            providerMap.put("permission","android.permission.MANAGE_DOCUMENTS");
            providerMap.put("exported","true");
            providerMap.put("authorities", targetPackage + ".MTDataFilesProvider");
            providerMap.put("grantUriPermissions","true");

            property.addProvider(providerMap,"android.content.action.DOCUMENTS_PROVIDER");

        }

        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            new ManifestEditor(is, os, property).processManifest();
            return os.toByteArray();
        } finally {
            if (is != null) is.close();
        }
    }
}
