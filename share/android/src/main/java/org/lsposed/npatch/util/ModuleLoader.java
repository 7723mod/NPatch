package org.lsposed.npatch.util;

import android.os.SharedMemory;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.util.Log;

import org.lsposed.lspd.models.PreLoadedApk;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

public class ModuleLoader {

    private static final String TAG = "NPatch";
    private static final String MODERN_JAVA_INIT = "META-INF/xposed/java_init.list";
    private static final String MODERN_NATIVE_INIT = "META-INF/xposed/native_init.list";
    private static final String LEGACY_JAVA_INIT = "assets/xposed_init";
    private static final String LEGACY_NATIVE_INIT = "assets/native_init";

    private static void readDexes(ZipFile apkFile, List<SharedMemory> preLoadedDexes) {
        int secondary = 2;
        for (var dexFile = apkFile.getEntry("classes.dex"); dexFile != null;
             dexFile = apkFile.getEntry("classes" + secondary + ".dex"), secondary++) {
            try (var in = apkFile.getInputStream(dexFile)) {
                var memory = SharedMemory.create(null, in.available());
                var byteBuffer = memory.mapReadWrite();
                Channels.newChannel(in).read(byteBuffer);
                SharedMemory.unmap(byteBuffer);
                memory.setProtect(OsConstants.PROT_READ);
                preLoadedDexes.add(memory);
            } catch (IOException | ErrnoException e) {
                Log.w(TAG, "Can not load " + dexFile + " in " + apkFile, e);
            }
        }
    }

    private static void readName(ZipFile apkFile, String initName, List<String> names) {
        var initEntry = apkFile.getEntry(initName);
        if (initEntry == null) return;
        try (var in = apkFile.getInputStream(initEntry)) {
            var reader = new BufferedReader(new InputStreamReader(in));
            String name;
            while ((name = reader.readLine()) != null) {
                name = name.trim();
                if (name.isEmpty() || name.startsWith("#")) continue;
                names.add(name);
            }
        } catch (IOException e) {
            Log.e(TAG, "Can not open " + initEntry, e);
        }
    }

    private static boolean readNamesWithFallback(ZipFile apkFile, String modernName, String legacyName, List<String> names) {
        readName(apkFile, modernName, names);
        if (names.isEmpty()) {
            readName(apkFile, legacyName, names);
            return true;
        }
        return false;
    }

    public static PreLoadedApk loadModule(String path) {
        if (path == null) return null;
        var file = new PreLoadedApk();
        var preLoadedDexes = new ArrayList<SharedMemory>();
        var moduleClassNames = new ArrayList<String>(1);
        var moduleLibraryNames = new ArrayList<String>(1);
        boolean isLegacy = false;
        try (var apkFile = new ZipFile(path)) {
            readDexes(apkFile, preLoadedDexes);
            isLegacy = readNamesWithFallback(apkFile, MODERN_JAVA_INIT, LEGACY_JAVA_INIT, moduleClassNames);
            readNamesWithFallback(apkFile, MODERN_NATIVE_INIT, LEGACY_NATIVE_INIT, moduleLibraryNames);
        } catch (IOException e) {
            Log.e(TAG, "Can not open " + path, e);
            return null;
        }
        if (preLoadedDexes.isEmpty()) return null;
        if (moduleClassNames.isEmpty()) return null;
        file.preLoadedDexes = preLoadedDexes;
        file.moduleClassNames = moduleClassNames;
        file.moduleLibraryNames = moduleLibraryNames;
        file.legacy = isLegacy;
        return file;
    }
}
