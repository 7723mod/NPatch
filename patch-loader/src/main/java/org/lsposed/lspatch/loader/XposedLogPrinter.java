package org.lsposed.lspatch.loader;

import android.app.ActivityThread;
import android.os.Environment;
import android.util.Log;
import android.util.LogPrinter;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

public class XposedLogPrinter extends LogPrinter {

    /**
     * Create a new Printer that sends to the log with the given priority
     * and tag.
     *
     * @param priority The desired log priority:
     *                 {@link Log#VERBOSE Log.VERBOSE},
     *                 {@link Log#DEBUG Log.DEBUG},
     *                 {@link Log#INFO Log.INFO},
     *                 {@link Log#WARN Log.WARN}, or
     *                 {@link Log#ERROR Log.ERROR}.
     * @param tag      A string tag to associate with each printed log statement.
     */
    public XposedLogPrinter(int priority, String tag) {
        super(priority, tag);
    }

    @Override
    public void println(String x) {
        writeLine(x);
    }
    private static SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd");
    private static FileOutputStream out;
    private static synchronized void writeLine(String text){
        try {
            if (out == null){
                File f = new File(Environment.getExternalStorageDirectory() + "/Android/media/" + ActivityThread.currentPackageName() + "/npatch/log/");
                f.mkdirs();
                out = new FileOutputStream(new File(f,format.format(new Date()) + ".log"),true);
            }
            out.write(text.getBytes());
            out.write("\n".getBytes());
        }catch (Exception ignored){ }
    }
}
