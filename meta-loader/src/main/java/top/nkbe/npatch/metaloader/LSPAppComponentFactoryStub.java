package top.nkbe.npatch.metaloader;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityThread;
import android.app.AppComponentFactory;
import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.os.Build;
import android.os.Process;
import android.os.ServiceManager;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.JsonWriter;
import android.util.Log;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import top.nkbe.npatch.share.Constants;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;

@SuppressLint("UnsafeDynamicallyLoadedCode")
public class LSPAppComponentFactoryStub extends AppComponentFactory {
    private static final String TAG = "NPatch-MetaLoader";
    private static final Object BOOTSTRAP_LOCK = new Object();
    private static final Map<String, String> ABI_BY_INSTRUCTION_SET = new HashMap<>(4);
    private static final ThreadLocal<Boolean> RESOLVING_RUNTIME_LOADER = new ThreadLocal<>();

    public static byte[] dex;
    public static boolean hideLibs;
    public static volatile ClassLoader runtimeClassLoader;

    private static final int STATE_NOT_STARTED = 0;
    private static final int STATE_RUNNING = 1;
    private static final int STATE_SUCCEEDED = 2;
    private static final int STATE_FAILED = 3;
    private static final int STATE_SKIPPED_APP_ZYGOTE = 4;

    private static volatile int bootstrapState = STATE_NOT_STARTED;
    private static volatile String bootstrapStage = "not_started";
    private static volatile Throwable bootstrapError;
    private static volatile Thread bootstrapThread;
    private static volatile String originalFactoryName;
    private static volatile AppComponentFactory originalFactory;

    static {
        ABI_BY_INSTRUCTION_SET.put("arm64", "arm64-v8a");
        ABI_BY_INSTRUCTION_SET.put("x86_64", "x86_64");
    }

    private static String getBootstrapStateName(int state) {
        switch (state) {
            case STATE_RUNNING:
                return "RUNNING";
            case STATE_SUCCEEDED:
                return "SUCCEEDED";
            case STATE_FAILED:
                return "FAILED";
            case STATE_SKIPPED_APP_ZYGOTE:
                return "SKIPPED_APP_ZYGOTE";
            default:
                return "NOT_STARTED";
        }
    }

    @Override
    public ClassLoader instantiateClassLoader(
            ClassLoader classLoader,
            ApplicationInfo appInfo
    ) {
        ensureBootstrapped();

        // LSPApplication replaces ActivityThread's bound LoadedApk during bootstrap. Some Android
        // versions still pass the pre-bootstrap loader here, which loses the original APK's
        // native-library search path.
        ClassLoader runtimeLoader = resolveRuntimeClassLoader();
        if (runtimeLoader != null && runtimeLoader != classLoader) {
            return runtimeLoader;
        }

        // Do not ask resolveOriginalFactory() to resolve the runtime loader again from inside
        // instantiateClassLoader; that would recurse through LoadedApk.getClassLoader().
        AppComponentFactory delegate = resolveOriginalFactory(classLoader, false);
        return delegate == null
                ? super.instantiateClassLoader(classLoader, appInfo)
                : delegate.instantiateClassLoader(classLoader, appInfo);
    }

    @Override
    public Application instantiateApplication(ClassLoader loader, String name)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        ensureBootstrapped();
        ClassLoader targetLoader = resolveTargetClassLoader(loader, name);
        AppComponentFactory delegate = resolveOriginalFactory(targetLoader, true);
        return delegate == null
                ? super.instantiateApplication(targetLoader, name)
                : delegate.instantiateApplication(targetLoader, name);
    }

    @Override
    public Activity instantiateActivity(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        ensureBootstrapped();
        ClassLoader targetLoader = resolveTargetClassLoader(loader, name);
        AppComponentFactory delegate = resolveOriginalFactory(targetLoader, true);
        return delegate == null
                ? super.instantiateActivity(targetLoader, name, intent)
                : delegate.instantiateActivity(targetLoader, name, intent);
    }

    @Override
    public BroadcastReceiver instantiateReceiver(
            ClassLoader loader,
            String name,
            Intent intent
    ) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        ensureBootstrapped();
        ClassLoader targetLoader = resolveTargetClassLoader(loader, name);
        AppComponentFactory delegate = resolveOriginalFactory(targetLoader, true);
        return delegate == null
                ? super.instantiateReceiver(targetLoader, name, intent)
                : delegate.instantiateReceiver(targetLoader, name, intent);
    }

    @Override
    public Service instantiateService(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        ensureBootstrapped();
        ClassLoader targetLoader = resolveTargetClassLoader(loader, name);
        AppComponentFactory delegate = resolveOriginalFactory(targetLoader, true);
        return delegate == null
                ? super.instantiateService(targetLoader, name, intent)
                : delegate.instantiateService(targetLoader, name, intent);
    }

    @Override
    public ContentProvider instantiateProvider(ClassLoader loader, String name)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        ensureBootstrapped();
        ClassLoader targetLoader = resolveTargetClassLoader(loader, name);
        AppComponentFactory delegate = resolveOriginalFactory(targetLoader, true);
        return delegate == null
                ? super.instantiateProvider(targetLoader, name)
                : delegate.instantiateProvider(targetLoader, name);
    }

    private static ClassLoader resolveTargetClassLoader(ClassLoader fallback, String componentName)
            throws ClassNotFoundException {
        ClassLoader runtime = resolveRuntimeClassLoader();
        if (runtime != null) {
            return runtime;
        }
        if (bootstrapState == STATE_FAILED && bootstrapError != null) {
            Log.e(TAG, "CRITICAL: Bootstrap failed at stage " + bootstrapStage
                    + "; cannot load component " + componentName + " from runtime ClassLoader", bootstrapError);
            throw new ClassNotFoundException("Failed to load component " + componentName
                    + " because NPatch bootstrap failed at stage " + bootstrapStage, bootstrapError);
        }
        Log.e(TAG, "CRITICAL: Runtime ClassLoader is null when instantiating "
                + componentName + "! Falling back to caller loader: " + fallback
                + ". This will likely cause ClassNotFoundException if the component resides in origin.apk.");
        return fallback;
    }

    private static void ensureBootstrapped() {
        int state = bootstrapState;
        if (state == STATE_SUCCEEDED
                || state == STATE_FAILED
                || state == STATE_SKIPPED_APP_ZYGOTE) {
            return;
        }
        if (state == STATE_RUNNING) {
            if (Thread.currentThread() == bootstrapThread) {
                return;
            }
            synchronized (BOOTSTRAP_LOCK) {
                // The bootstrap owner publishes a terminal state before releasing this lock.
                return;
            }
        }

        synchronized (BOOTSTRAP_LOCK) {
            if (bootstrapState != STATE_NOT_STARTED) {
                return;
            }
            if (ActivityThread.currentActivityThread() == null) {
                bootstrapStage = "app_zygote";
                bootstrapState = STATE_SKIPPED_APP_ZYGOTE;
                Log.i(TAG, "Skip bootstrap in app zygote");
                writeDiagnostic(null);
                return;
            }

            bootstrapState = STATE_RUNNING;
            bootstrapThread = Thread.currentThread();
            try {
                bootstrap();
                bootstrapStage = "complete";
                bootstrapState = STATE_SUCCEEDED;
                Log.i(TAG, "Bootstrap completed");
                writeDiagnostic(null);
            } catch (Throwable error) {
                bootstrapState = STATE_FAILED;
                bootstrapError = error;
                clearDexBuffer();
                Log.e(TAG, "Bootstrap failed at " + bootstrapStage, error);
                writeDiagnostic(error);
                // AppComponentFactory is also responsible for constructing the original app.
                // Do not poison class initialization: component methods below can still delegate
                // to the original factory or framework default after NPatch bootstrap fails.
            } finally {
                bootstrapThread = null;
            }
        }
    }

    private static void bootstrap() throws Throwable {
        exemptHiddenApi();

        bootstrapStage = "resolve_meta_loader";
        ClassLoader loader = Objects.requireNonNull(
                LSPAppComponentFactoryStub.class.getClassLoader(),
                "MetaLoader class loader is null"
        );

        bootstrapStage = "read_config";
        int sigBypassLevel = readConfig(loader);
        hideLibs = hideLibs && sigBypassLevel > Constants.SIGBYPASS_NONE;

        bootstrapStage = "resolve_abi";
        Class<?> runtimeClass = Class.forName("dalvik.system.VMRuntime");
        Method getRuntime = runtimeClass.getDeclaredMethod("getRuntime");
        Method instructionSet = runtimeClass.getDeclaredMethod("vmInstructionSet");
        getRuntime.setAccessible(true);
        instructionSet.setAccessible(true);
        String instruction = (String) instructionSet.invoke(getRuntime.invoke(null));
        String abi = ABI_BY_INSTRUCTION_SET.get(instruction);
        if (abi == null) {
            throw new IOException("Unsupported instruction set: " + instruction);
        }

        bootstrapStage = "read_loader_dex";
        try (InputStream input = requireResource(loader, Constants.LOADER_DEX_ASSET_PATH);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            transfer(input, output);
            dex = output.toByteArray();
        }

        bootstrapStage = "extract_native";
        File nativeFile = createTempSoFile(Process.myUid() / 100000);
        String nativeAsset = "assets/npatch/so/" + abi + "/libnpatch.so";
        try (InputStream input = requireResource(loader, nativeAsset);
             FileOutputStream output = new FileOutputStream(nativeFile)) {
            transfer(input, output);
            output.getFD().sync();
        }

        try {
            nativeFile.setReadOnly();
        } catch (Throwable ignored) {
        }

        bootstrapStage = "load_native";
        Log.i(TAG, "Loading native bootstrap: " + nativeFile);
        System.load(nativeFile.getAbsolutePath());
        clearDexBuffer();
    }

    private static void exemptHiddenApi() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return;
        }
        boolean exempted = false;
        try {
            Class<?> vmRuntimeClass = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime");
            getRuntime.setAccessible(true);
            Object vmRuntime = getRuntime.invoke(null);
            Method setExemptions = vmRuntimeClass.getDeclaredMethod("setHiddenApiExemptions", String[].class);
            setExemptions.setAccessible(true);
            setExemptions.invoke(vmRuntime, (Object) new String[]{"L"});
            exempted = true;
        } catch (Throwable ignored) {
        }

        if (!exempted) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("L");
            } catch (Throwable t) {
                try {
                    HiddenApiBypass.addHiddenApiExemptions("");
                } catch (Throwable t2) {
                    Log.w(TAG, "Hidden API exemption fallback failed", t2);
                }
            }
        }
    }

    private static int readConfig(ClassLoader loader) throws IOException {
        int sigBypassLevel = Constants.SIGBYPASS_NONE;
        try (InputStream input = requireResource(loader, Constants.CONFIG_ASSET_PATH);
             JsonReader reader =
                     new JsonReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if ("hideLibs".equals(name)) {
                    hideLibs = reader.nextBoolean();
                } else if ("sigBypassLevel".equals(name)) {
                    sigBypassLevel = reader.nextInt();
                } else if ("appComponentFactory".equals(name)) {
                    if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull();
                        originalFactoryName = null;
                    } else {
                        originalFactoryName = reader.nextString();
                    }
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
        }
        return sigBypassLevel;
    }

    private static AppComponentFactory resolveOriginalFactory(
            ClassLoader requested,
            boolean includeRuntimeLoader
    ) {
        String name = originalFactoryName;
        if (name == null || name.isEmpty()
                || Constants.PROXY_APP_COMPONENT_FACTORY.equals(name)) {
            return null;
        }
        AppComponentFactory cached = originalFactory;
        if (cached != null) {
            return cached;
        }

        synchronized (BOOTSTRAP_LOCK) {
            if (originalFactory != null) {
                return originalFactory;
            }
            Throwable firstFailure = null;
            for (ClassLoader candidate : factoryClassLoaders(requested, includeRuntimeLoader)) {
                try {
                    Object instance = candidate.loadClass(name).getDeclaredConstructor().newInstance();
                    if (!(instance instanceof AppComponentFactory)) {
                        throw new IllegalStateException(name + " is not an AppComponentFactory");
                    }
                    originalFactory = (AppComponentFactory) instance;
                    return originalFactory;
                } catch (Throwable error) {
                    if (firstFailure == null) {
                        firstFailure = error;
                    }
                }
            }
            Log.e(TAG, "Unable to restore original AppComponentFactory: " + name, firstFailure);
            return null;
        }
    }

    private static Iterable<ClassLoader> factoryClassLoaders(
            ClassLoader requested,
            boolean includeRuntimeLoader
    ) {
        LinkedHashSet<ClassLoader> candidates = new LinkedHashSet<>(3);
        if (includeRuntimeLoader) {
            ClassLoader runtime = resolveRuntimeClassLoader();
            if (runtime != null) {
                candidates.add(runtime);
            }
        }
        if (requested != null) {
            candidates.add(requested);
        }
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (context != null) {
            candidates.add(context);
        }
        return candidates;
    }

    private static ClassLoader resolveRuntimeClassLoader() {
        ClassLoader direct = runtimeClassLoader;
        if (direct != null) {
            return direct;
        }
        if (Boolean.TRUE.equals(RESOLVING_RUNTIME_LOADER.get())) {
            return null;
        }
        RESOLVING_RUNTIME_LOADER.set(Boolean.TRUE);
        try {
            ActivityThread thread = ActivityThread.currentActivityThread();
            if (thread == null) {
                return null;
            }
            Field boundField = ActivityThread.class.getDeclaredField("mBoundApplication");
            boundField.setAccessible(true);
            Object boundApplication = boundField.get(thread);
            if (boundApplication == null) {
                return null;
            }
            Field infoField = boundApplication.getClass().getDeclaredField("info");
            infoField.setAccessible(true);
            Object loadedApk = infoField.get(boundApplication);
            if (loadedApk == null) {
                return null;
            }
            Method getClassLoader = loadedApk.getClass().getDeclaredMethod("getClassLoader");
            getClassLoader.setAccessible(true);
            ClassLoader loader = (ClassLoader) getClassLoader.invoke(loadedApk);
            if (loader != null) {
                runtimeClassLoader = loader;
            }
            return loader;
        } catch (Throwable error) {
            Log.d(TAG, "Runtime class loader is not ready", error);
            return null;
        } finally {
            RESOLVING_RUNTIME_LOADER.remove();
        }
    }

    private static InputStream requireResource(ClassLoader loader, String path)
            throws IOException {
        InputStream input = loader.getResourceAsStream(path);
        if (input == null) {
            throw new IOException("Missing bootstrap resource: " + path);
        }
        return input;
    }

    private static void clearDexBuffer() {
        byte[] current = dex;
        if (current != null) {
            Arrays.fill(current, (byte) 0);
            dex = null;
        }
    }

    private static void transfer(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
    }

    private static File createTempSoFile(int userId) throws IOException {
        File cache = resolveCacheDir(userId);
        if (!cache.isDirectory() && !cache.mkdirs()) {
            throw new IOException("Unable to create cache directory: " + cache);
        }
        return File.createTempFile("libnpatch-", ".so", cache);
    }

    private static File resolveCacheDir(int userId) throws IOException {
        String packageName = resolvePackageName();
        if (packageName == null || packageName.isEmpty()) {
            throw new IOException("Unable to resolve current package name");
        }
        return new File(resolveDataDir(packageName, userId), "cache/npatch");
    }

    private static String resolvePackageName() {
        try {
            Method method = ActivityThread.class.getDeclaredMethod("currentPackageName");
            method.setAccessible(true);
            return (String) method.invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String resolveDataDir(String packageName, int userId) {
        try {
            Application application = ActivityThread.currentApplication();
            if (application != null && application.getApplicationInfo() != null) {
                String dataDir = application.getApplicationInfo().dataDir;
                if (dataDir != null && !dataDir.isEmpty()) {
                    return dataDir;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            IPackageManager packageManager =
                    IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
            ApplicationInfo info;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                info = (ApplicationInfo) HiddenApiBypass.invoke(
                        IPackageManager.class,
                        packageManager,
                        "getApplicationInfo",
                        packageName,
                        0L,
                        userId
                );
            } else {
                info = packageManager.getApplicationInfo(packageName, 0, userId);
            }
            if (info != null && info.dataDir != null && !info.dataDir.isEmpty()) {
                return info.dataDir;
            }
        } catch (Throwable ignored) {
        }
        return "/data/user/" + userId + "/" + packageName;
    }

    private static void writeDiagnostic(Throwable error) {
        try {
            File directory = resolveCacheDir(Process.myUid() / 100000);
            if (!directory.isDirectory() && !directory.mkdirs()) {
                return;
            }
            File target = new File(directory, "bootstrap_state.json");
            File temporary = new File(directory, "bootstrap_state.json.tmp");
            try (JsonWriter writer = new JsonWriter(new OutputStreamWriter(
                    new FileOutputStream(temporary),
                    StandardCharsets.UTF_8
            ))) {
                writer.setIndent("  ");
                writer.beginObject();
                writer.name("state").value(getBootstrapStateName(bootstrapState));
                writer.name("stage").value(bootstrapStage);
                writer.name("timestamp").value(System.currentTimeMillis());
                writer.name("pid").value(Process.myPid());
                writer.name("uid").value(Process.myUid());
                writer.name("packageName").value(resolvePackageName());
                writer.name("errorClass").value(
                        error == null ? null : error.getClass().getName());
                writer.name("errorMessage").value(error == null ? null : error.getMessage());
                writer.endObject();
            }
            if (target.exists() && !target.delete()) {
                return;
            }
            if (!temporary.renameTo(target)) {
                Log.w(TAG, "Unable to publish bootstrap diagnostic");
            }
        } catch (Throwable diagnosticError) {
            Log.w(TAG, "Unable to write bootstrap diagnostic", diagnosticError);
        }
    }
}
