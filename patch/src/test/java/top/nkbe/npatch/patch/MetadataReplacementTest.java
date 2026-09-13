package top.nkbe.npatch.patch;

import com.wind.meditor.core.ManifestEditor;
import com.wind.meditor.property.ModificationProperty;
import org.junit.Test;
import pxb.android.axml.AxmlParser;
import pxb.android.axml.AxmlWriter;
import pxb.android.axml.NodeVisitor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class MetadataReplacementTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    @Test public void replacingMetadataRemovesTheWholeOldNode() throws Exception {
        byte[] xml = manifest();
        for (int i = 0; i < 3; i++) {
            ModificationProperty changes = new ModificationProperty();
            changes.addDeleteMetaData("npatch");
            changes.addMetaData(new ModificationProperty.MetaData("npatch", "new-" + i));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            new ManifestEditor(new ByteArrayInputStream(xml), output, changes).processManifest();
            xml = output.toByteArray();
            List<Map<String, Object>> metadata = metadata(xml);
            assertEquals("Replacing metadata must not leave a nameless node", 2, metadata.size());
            assertEquals("keep", metadata.get(0).get("name"));
            assertEquals("untouched", metadata.get(0).get("value"));
            assertEquals("npatch", metadata.get(1).get("name"));
            assertEquals("new-" + i, metadata.get(1).get("value"));
        }
    }

    @Test public void deletingMetadataDoesNotRemoveUnrelatedEntries() throws Exception {
        ModificationProperty changes = new ModificationProperty().addDeleteMetaData("npatch");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new ManifestEditor(new ByteArrayInputStream(manifest()), output, changes).processManifest();
        List<Map<String, Object>> metadata = metadata(output.toByteArray());
        assertEquals(1, metadata.size());
        assertEquals("keep", metadata.get(0).get("name"));
    }

    private static byte[] manifest() throws Exception {
        AxmlWriter writer = new AxmlWriter();
        writer.ns("android", ANDROID, 1);
        NodeVisitor root = writer.child(null, "manifest");
        root.attr(null, "package", -1, NodeVisitor.TYPE_STRING, "test.repatch");
        NodeVisitor app = root.child(null, "application");
        addMetadata(app, "npatch", "old");
        addMetadata(app, "keep", "untouched");
        app.end(); root.end(); writer.end();
        return writer.toByteArray();
    }

    private static void addMetadata(NodeVisitor app, String name, String value) {
        NodeVisitor node = app.child(null, "meta-data");
        node.attr(ANDROID, "name", 0x01010003, NodeVisitor.TYPE_STRING, name);
        node.attr(ANDROID, "value", 0x01010024, NodeVisitor.TYPE_STRING, value);
        node.end();
    }

    private static List<Map<String, Object>> metadata(byte[] xml) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        AxmlParser parser = new AxmlParser(xml);
        int event;
        while ((event = parser.next()) != AxmlParser.END_FILE) {
            if (event == AxmlParser.START_TAG && "meta-data".equals(parser.getName())) {
                Map<String, Object> attrs = new LinkedHashMap<>();
                for (int i = 0; i < parser.getAttrCount(); i++) attrs.put(parser.getAttrName(i), parser.getAttrValue(i));
                result.add(attrs);
            }
        }
        return result;
    }
}
