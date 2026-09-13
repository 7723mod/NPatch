package top.nkbe.npatch.patch.util;

import com.wind.meditor.utils.Utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import pxb.android.axml.AxmlParser;

/**
 * Created by Wind
 */
public class ManifestParser {

    public static Pair parseManifestFile(InputStream is) throws IOException {
        AxmlParser parser = new AxmlParser(Utils.getBytesFromInputStream(is));
        String packageName = null;
        String splitName = null;
        String appComponentFactory = null;
        int minSdkVersion = 0;
        List<String> permissions = new ArrayList<>();
        List<String> use_permissions = new ArrayList<>();
        List<String> authorities = new ArrayList<>();
        List<String> isolatedOrMultiProcessComponents = new ArrayList<>();
        try {

            while (true) {
                int type = parser.next();
                if (type == AxmlParser.END_FILE) {
                    break;
                }
                if (type == AxmlParser.START_TAG) {
                    String name = parser.getName();
                    int attrCount = parser.getAttributeCount();
                    for (int i = 0; i < attrCount; i++) {
                        String attrName = parser.getAttrName(i);
                        int attrNameRes = parser.getAttrResId(i);
                        
                        if ("manifest".equals(name)) {
                            if ("package".equals(attrName)) {
                                packageName = parser.getAttrValue(i).toString();
                            }
                            if ("split".equals(attrName) || attrNameRes == 0x01010549) {
                                splitName = parser.getAttrValue(i).toString();
                            }
                        }

                        if ("uses-sdk".equals(name)) {
                            if ("minSdkVersion".equals(attrName)) {
                                minSdkVersion = Integer.parseInt(parser.getAttrValue(i).toString());
                            }
                        }

                        if ("permission".equals(name)){
                            if ("name".equals(attrName)){
                                String permissionName = parser.getAttrValue(i).toString();
                                if (!permissionName.startsWith("android")){
                                    permissions.add(permissionName);
                                }
                            }
                        }

                        if ("uses-permission".equals(name)){
                            if ("name".equals(attrName)){
                                String permissionName = parser.getAttrValue(i).toString();
                                if (!permissionName.startsWith("android")){
                                    use_permissions.add(permissionName);
                                }
                            }
                        }

                        if ("provider".equals(name)){
                            if ("authorities".equals(attrName)){
                                String authority = parser.getAttrValue(i).toString();
                                authorities.add(authority);
                            }
                        }

                        if ("appComponentFactory".equals(attrName) || attrNameRes == 0x0101057a) {
                            appComponentFactory = parser.getAttrValue(i).toString();
                        }
                    }

                    if ("service".equals(name) || "activity".equals(name) || "activity-alias".equals(name)
                            || "provider".equals(name) || "receiver".equals(name)) {
                        String compName = null;
                        String processName = null;
                        boolean isolated = false;

                        for (int i = 0; i < attrCount; i++) {
                            String attrName = parser.getAttrName(i);
                            int attrNameRes = parser.getAttrResId(i);
                            Object attrVal = parser.getAttrValue(i);
                            String valStr = attrVal != null ? attrVal.toString() : "";

                            if ("name".equals(attrName) || attrNameRes == 0x01010003) {
                                compName = valStr;
                            } else if ("process".equals(attrName) || attrNameRes == 0x01010011) {
                                processName = valStr;
                            } else if ("isolatedProcess".equals(attrName) || attrNameRes == 0x01010376) {
                                isolated = "true".equalsIgnoreCase(valStr);
                            }
                        }

                        if (isolated || (processName != null && processName.startsWith(":"))) {
                            String desc = (compName != null ? compName : name)
                                    + (processName != null ? " [process=" + processName + "]" : "")
                                    + (isolated ? " [isolated=true]" : "");
                            isolatedOrMultiProcessComponents.add(desc);
                        }
                    }
                } else if (type == AxmlParser.END_TAG) {
                    // ignored
                }
            }
        } catch (Exception e) {
            return null;
        }

        Pair pair = new Pair(packageName, splitName, appComponentFactory, minSdkVersion);
        pair.setPermissions(permissions);
        pair.setUse_permissions(use_permissions);
        pair.setAuthorities(authorities);
        pair.setIsolatedOrMultiProcessComponents(isolatedOrMultiProcessComponents);
        return pair;
    }

    /**
     * Get the package name and the main application name from the manifest file or APK
     */
    public static Pair parseManifestFile(String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            return null;
        }
        try (ZipFile zipFile = new ZipFile(file)) {
            ZipEntry manifestEntry = zipFile.getEntry("AndroidManifest.xml");
            if (manifestEntry != null) {
                try (InputStream is = zipFile.getInputStream(manifestEntry)) {
                    return parseManifestFile(is);
                }
            }
        } catch (ZipException ignored) {
            // Not a zip/apk file, fallback to treating as standalone binary XML
        }
        try (var is = new FileInputStream(file)) {
            return parseManifestFile(is);
        }
    }

    public static class Pair {
        public String packageName;
        public String splitName;
        public String appComponentFactory;

        public int minSdkVersion;
        public List<String> permissions;
        public List<String> use_permissions;
        public List<String> authorities;
        public List<String> isolatedOrMultiProcessComponents = new ArrayList<>();

        public boolean hasIsolatedOrMultiProcessComponents() {
            return !isolatedOrMultiProcessComponents.isEmpty();
        }

        public int getIsolatedOrMultiProcessCount() {
            return isolatedOrMultiProcessComponents.size();
        }

        public List<String> getIsolatedOrMultiProcessComponents() {
            return isolatedOrMultiProcessComponents;
        }

        public void setIsolatedOrMultiProcessComponents(List<String> list) {
            if (list != null) {
                this.isolatedOrMultiProcessComponents = list;
            }
        }

        public Pair(String packageName, String appComponentFactory, int minSdkVersion) {
            this(packageName, null, appComponentFactory, minSdkVersion);
        }

        public Pair(String packageName, String splitName, String appComponentFactory, int minSdkVersion) {
            this.packageName = packageName;
            this.splitName = splitName;
            this.appComponentFactory = appComponentFactory;
            this.minSdkVersion = minSdkVersion;
        }

        public List<String> getPermissions() {
            return permissions;
        }

        public void setPermissions(List<String> permissions) {
            this.permissions = permissions;
        }

        public List<String> getUse_permissions() {
            return use_permissions;
        }

        public void setUse_permissions(List<String> use_permissions) {
            this.use_permissions = use_permissions;
        }

        public List<String> getAuthorities() {
            return authorities;
        }

        public void setAuthorities(List<String> authorities) {
            this.authorities = authorities;
        }
    }

}
