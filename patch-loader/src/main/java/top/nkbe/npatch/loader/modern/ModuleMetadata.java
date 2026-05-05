package top.nkbe.npatch.loader.modern;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ModuleMetadata {
    private final String packageName;
    private final int minApiVersion;
    private final int targetApiVersion;
    private final boolean staticScope;
    private final String author;
    private final String version;
    private final String name;
    private final String description;
    private final List<String> scopes;
    private final List<String> javaInitList;
    private final List<String> nativeInitList;
    private final ModulePipeline pipeline;

    public ModuleMetadata(
            String packageName,
            int minApiVersion,
            int targetApiVersion,
            boolean staticScope,
            String author,
            String version,
            String name,
            String description,
            List<String> scopes,
            List<String> javaInitList,
            List<String> nativeInitList,
            ModulePipeline pipeline) {
        this.packageName = packageName;
        this.minApiVersion = minApiVersion;
        this.targetApiVersion = targetApiVersion;
        this.staticScope = staticScope;
        this.author = author;
        this.version = version;
        this.name = name;
        this.description = description;
        this.scopes = Collections.unmodifiableList(new ArrayList<>(scopes == null ? Collections.emptyList() : scopes));
        this.javaInitList = Collections.unmodifiableList(new ArrayList<>(javaInitList == null ? Collections.emptyList() : javaInitList));
        this.nativeInitList = Collections.unmodifiableList(new ArrayList<>(nativeInitList == null ? Collections.emptyList() : nativeInitList));
        this.pipeline = pipeline == null ? ModulePipeline.UNSUPPORTED : pipeline;
    }

    public String getPackageName() {
        return packageName;
    }

    public int getMinApiVersion() {
        return minApiVersion;
    }

    public int getTargetApiVersion() {
        return targetApiVersion;
    }

    public boolean isStaticScope() {
        return staticScope;
    }

    public String getAuthor() {
        return author;
    }

    public String getVersion() {
        return version;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getScopes() {
        return scopes;
    }

    public List<String> getJavaInitList() {
        return javaInitList;
    }

    public List<String> getNativeInitList() {
        return nativeInitList;
    }

    public ModulePipeline getPipeline() {
        return pipeline;
    }

    public boolean isModern() {
        return pipeline == ModulePipeline.MODERN;
    }

    public boolean isLegacy() {
        return pipeline == ModulePipeline.LEGACY;
    }

    public boolean isUnsupported() {
        return pipeline == ModulePipeline.UNSUPPORTED;
    }
}
