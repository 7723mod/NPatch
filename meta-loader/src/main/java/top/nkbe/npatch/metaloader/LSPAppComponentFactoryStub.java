package top.nkbe.npatch.metaloader;

import android.annotation.SuppressLint;
import android.app.ActivityThread;
import android.app.AppComponentFactory;
import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.os.Build;
import android.os.Process;
import android.os.ServiceManager;
import android.util.JsonReader;
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
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The AppComponentFactory the patched manifest names. Its only job is to run the native bootstrap.
 *
 * <p>It deliberately overrides <b>none</b> of the {@code instantiateXxx} component hooks. Upstream
 * LSPatch reached the same shape: once the native hook is installed, the host bootstrap points
 * {@code ApplicationInfo.appComponentFactory} back at whatever the <i>original</i> apk declared, so
 * the system instantiates the real factory and this class is never consulted again. Overriding the
 * component hooks here would mean answering for the app's own factory from inside a class loader
 * that cannot even see it, which is what the removed delegation layer kept getting wrong.
 *
 * <p>The error path follows from that: if bootstrap fails this class does nothing further, because
 * the framework's default factory is a strictly better answer than a half-initialised NPatch.
 */
@SuppressLint("UnsafeDynamicallyLoadedCode")
public class LSPAppComponentFactoryStub extends AppComponentFactory {
    private static final String TAG = "NPatch-MetaLoader";
    private static final Map<String, String> ABI_BY_INSTRUCTION_SET = new HashMap<>(4);

    /** Consumed by the native bootstrap; published before the library is loaded. */
    public static byte[] dex;
    public static boolean hideLibs;

    static {
        ABI_BY_INSTRUCTION_SET.put("arm64", "arm64-v8a");
        ABI_BY_INSTRUCTION_SET.put("x86_64", "x86_64");

        // Class initialization is what installs the hook, exactly as upstream LSPatch does it. The
        // framework loads this class to run whichever instantiateXxx it needs first, so the native
        // hook is always in place before the original factory is consulted -- no component hook has
        // to be overridden to get that ordering, and this class stops being reachable once
        // ApplicationInfo.appComponentFactory names the original factory again.
        if (ActivityThread.currentActivityThread() == null) {
            Log.i(TAG, "Skip bootstrap in app zygote");
        } else {
            bootstrap();
        }
    }

    /**
     * Loads the native bootstrap for this process.
     *
     * <p>Failure is not fatal to the app: the exception is caught here rather than propagated, since
     * a throw out of a static initializer would poison this class's initialization state for the
     * process and take component instantiation down with it. The framework falls back to its default
     * factory, which starts the app without NPatch rather than not at all.
     */
    private static void bootstrap() {
        try {
            bootstrapOrThrow();
        } catch (Throwable error) {
            clearDexBuffer();
            Log.e(TAG, "Bootstrap failed", error);
        }
    }

    private static void bootstrapOrThrow() throws Throwable {
        exemptHiddenApi();

        ClassLoader loader = Objects.requireNonNull(
                LSPAppComponentFactoryStub.class.getClassLoader(),
                "MetaLoader class loader is null"
        );

        int sigBypassLevel = readConfig(loader);
        hideLibs = hideLibs && sigBypassLevel > Constants.SIGBYPASS_NONE;

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

        try (InputStream input = requireResource(loader, Constants.LOADER_DEX_ASSET_PATH);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            transfer(input, output);
            dex = output.toByteArray();
        }

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

    /**
     * Reads the two fields the native bootstrap needs, before any host class is reachable.
     *
     * <p>{@code appComponentFactory} is still parsed so its absence is not mistaken for a parse
     * failure, but the value is no longer used here: the host loader restores the original factory
     * onto {@code ApplicationInfo} before the app's class loader is built, which is the only point
     * at which that decision can be made correctly.
     */
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
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
        }
        return sigBypassLevel;
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
}
