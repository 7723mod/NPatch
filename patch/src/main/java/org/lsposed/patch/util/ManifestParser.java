package org.lsposed.patch.util;

import com.wind.meditor.utils.Utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import pxb.android.axml.AxmlParser;

/**
 * Created by Wind
 */
public class ManifestParser {

    public static Pair parseManifestFile(InputStream is) throws IOException {
        AxmlParser parser = new AxmlParser(Utils.getBytesFromInputStream(is));
        String packageName = null;
        String appComponentFactory = null;
        int minSdkVersion = 0;
        List<String> permissions = new ArrayList<>();
        List<String> use_permissions = new ArrayList<>();
        List<String> authorities = new ArrayList<>();
        try {

            while (true) {
                int type = parser.next();
                if (type == AxmlParser.END_FILE) {
                    break;
                }
                if (type == AxmlParser.START_TAG) {
                    int attrCount = parser.getAttributeCount();
                    for (int i = 0; i < attrCount; i++) {
                        String attrName = parser.getAttrName(i);
                        int attrNameRes = parser.getAttrResId(i);

                        String name = parser.getName();
                        
                        if ("manifest".equals(name)) {
                            if ("package".equals(attrName)) {
                                packageName = parser.getAttrValue(i).toString();
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

//                        if (packageName != null && packageName.length() > 0 &&
//                                appComponentFactory != null && appComponentFactory.length() > 0 &&
//                                minSdkVersion > 0
//                        ) {
//                            return new Pair(packageName, appComponentFactory, minSdkVersion);
//                        }
                    }
                } else if (type == AxmlParser.END_TAG) {
                    // ignored
                }
            }
        } catch (Exception e) {
            return null;
        }

        Pair pair = new Pair(packageName, appComponentFactory, minSdkVersion);
        pair.setPermissions(permissions);
        pair.setUse_permissions(use_permissions);
        pair.setAuthorities(authorities);
        return pair;
    }

    /**
     * Get the package name and the main application name from the manifest file
     */
    public static Pair parseManifestFile(String filePath) throws IOException {
        File file = new File(filePath);
        try (var is = new FileInputStream(file)) {
            return parseManifestFile(is);
        }
    }

    public static class Pair {
        public String packageName;
        public String appComponentFactory;

        public int minSdkVersion;
        public List<String> permissions;
        public List<String> use_permissions;
        public List<String> authorities;

        public Pair(String packageName, String appComponentFactory, int minSdkVersion) {
            this.packageName = packageName;
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