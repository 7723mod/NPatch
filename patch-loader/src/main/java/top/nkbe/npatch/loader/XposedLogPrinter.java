package top.nkbe.npatch.loader;

import android.app.ActivityThread;
import android.os.Environment;
import android.os.Process;
import android.util.Log;
import android.util.LogPrinter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Writes structured NPatch framework, Legacy API, Modern API, and Java crash events to Media.
 * Direct module calls to {@code android.util.Log}, native fatal signals, and system tombstones are
 * deliberately outside this pipeline.
 */
public class XposedLogPrinter extends LogPrinter {

    private static final String TAG = "NPatch-LogWriter";
    private final int priority;
    private final String tag;

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
        this.priority = priority;
        this.tag = tag;
    }

    @Override
    public void println(String x) {
        log(priority, tag, x, null);
    }

    private static final SimpleDateFormat FILE_DATE_FORMAT =
            new SimpleDateFormat("yyyyMMdd", Locale.ROOT);
    private static final SimpleDateFormat LOG_TIME_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.ROOT);
    private static FileOutputStream out;
    private static String openedDate;

    public static synchronized void log(
            int priority,
            String tag,
            String message,
            Throwable throwable
    ) {
        String level;
        switch (priority) {
            case Log.VERBOSE:
                level = "V";
                break;
            case Log.DEBUG:
                level = "D";
                break;
            case Log.INFO:
                level = "I";
                break;
            case Log.WARN:
                level = "W";
                break;
            case Log.ERROR:
                level = "E";
                break;
            default:
                level = Integer.toString(priority);
                break;
        }
        StringBuilder line = new StringBuilder()
                .append('[').append(LOG_TIME_FORMAT.format(new Date())).append(']')
                .append('[').append(ActivityThread.currentProcessName())
                .append(':').append(Process.myPid())
                .append(';').append(Thread.currentThread().getName())
                .append(':').append(Process.myTid()).append(']')
                .append('[').append(level).append('/').append(tag).append("] ")
                .append(message);
        if (throwable != null) {
            line.append('\n').append(org.matrix.vector.util.Log.getStackTraceString(throwable));
        }
        writeLine(line.toString());
    }

    private static synchronized void writeLine(String text) {
        try {
            String currentDate = FILE_DATE_FORMAT.format(new Date());
            if (out == null || !currentDate.equals(openedDate)) {
                if (out != null) {
                    out.close();
                }
                File f = new File(Environment.getExternalStorageDirectory() + "/Android/media/" + ActivityThread.currentPackageName() + "/npatch/log/");
                if (!f.isDirectory() && !f.mkdirs()) {
                    throw new IOException("Cannot create log directory: " + f);
                }
                out = new FileOutputStream(
                        new File(f, currentDate + ".log"),
                        true);
                openedDate = currentDate;
            }
            out.write((text + '\n').getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception error) {
            Log.e(TAG, "Failed to write media log", error);
        }
    }
}
