package top.nkbe.npatch.patch;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import top.nkbe.nza.zip.ZipFile;
import top.nkbe.nza.zip.ZipMaker;
import top.nkbe.npatch.patch.util.JavaLogger;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class ProviderDexInjectionTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private File createDummyZip(File dir, String... entryNames) throws Exception {
        File zipFile = new File(dir, "test.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            for (String name : entryNames) {
                zos.putNextEntry(new ZipEntry(name));
                zos.write(("content of " + name).getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return zipFile;
    }

    @Test
    public void testInjectProviderDexStandardModeWithoutShim() throws Exception {
        File workDir = tempFolder.newFolder("standard-no-shim");
        File srcZipFile = createDummyZip(workDir, "AndroidManifest.xml", "classes.dex");
        File outApk = new File(workDir, "output.apk");

        NPatch npatch = new NPatch(new JavaLogger(), srcZipFile.getAbsolutePath());

        Set<String> addedEntries = new HashSet<>();
        addedEntries.add("classes.dex"); // metaloader added

        try (ZipFile srcZip = new ZipFile(srcZipFile);
             ZipMaker zipMaker = new ZipMaker(outApk)) {
            npatch.injectProviderDex(srcZip, zipMaker, addedEntries);
        }

        assertTrue("classes2.dex should be registered in addedEntries", addedEntries.contains("classes2.dex"));
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(outApk)) {
            assertNotNull("classes2.dex must exist in generated apk", zip.getEntry("classes2.dex"));
            assertTrue("classes2.dex must have content", zip.getEntry("classes2.dex").getSize() > 0);
        }
    }

    @Test
    public void testInjectProviderDexStandardModeWithShim() throws Exception {
        File workDir = tempFolder.newFolder("standard-with-shim");
        File srcZipFile = createDummyZip(workDir, "AndroidManifest.xml", "classes.dex");
        File outApk = new File(workDir, "output.apk");

        NPatch npatch = new NPatch(new JavaLogger(), srcZipFile.getAbsolutePath());

        Set<String> addedEntries = new HashSet<>();
        addedEntries.add("classes.dex");  // metaloader
        addedEntries.add("classes2.dex"); // factory shim

        try (ZipFile srcZip = new ZipFile(srcZipFile);
             ZipMaker zipMaker = new ZipMaker(outApk)) {
            npatch.injectProviderDex(srcZip, zipMaker, addedEntries);
        }

        assertTrue("classes3.dex should be registered in addedEntries", addedEntries.contains("classes3.dex"));
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(outApk)) {
            assertNotNull("classes3.dex must exist in generated apk", zip.getEntry("classes3.dex"));
            assertTrue("classes3.dex must have content", zip.getEntry("classes3.dex").getSize() > 0);
        }
    }

    @Test
    public void testInjectProviderDexWithInjectDexMode() throws Exception {
        File workDir = tempFolder.newFolder("inject-dex-mode");
        File srcZipFile = createDummyZip(workDir, "AndroidManifest.xml", "classes.dex", "classes2.dex", "classes3.dex");
        File outApk = new File(workDir, "output.apk");

        NPatch npatch = new NPatch(new JavaLogger(), "--injectdex", srcZipFile.getAbsolutePath());

        Set<String> addedEntries = new HashSet<>();
        addedEntries.add("classes4.dex"); // metaloader in injectdex mode
        addedEntries.add("classes5.dex"); // factory shim

        try (ZipFile srcZip = new ZipFile(srcZipFile);
             ZipMaker zipMaker = new ZipMaker(outApk)) {
            npatch.injectProviderDex(srcZip, zipMaker, addedEntries);
        }

        assertTrue("classes6.dex should be registered in addedEntries", addedEntries.contains("classes6.dex"));
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(outApk)) {
            assertNotNull("classes6.dex must exist in generated apk", zip.getEntry("classes6.dex"));
            assertTrue("classes6.dex must have content", zip.getEntry("classes6.dex").getSize() > 0);
        }
    }
}
