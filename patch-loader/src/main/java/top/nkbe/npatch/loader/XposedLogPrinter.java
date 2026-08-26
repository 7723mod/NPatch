package top.nkbe.npatch.loader;

import android.app.ActivityThread;
import android.os.Environment;
import android.os.Process;
import android.util.Log;
import android.util.LogPrinter;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Writes structured NPatch framework, Legacy API, Modern API, and Java crash events to Media asynchronously.
 * Direct module calls to {@code android.util.Log}, native fatal signals, and system tombstones are
 * deliberately outside this pipeline.
 */
public class XposedLogPrinter extends LogPrinter {

    private static final String TAG = "NPatch-LogWriter";
    private final int priority;
    private final String tag;

    private static final int MAX_QUEUE_CAPACITY = 2048;
    private static final BlockingQueue<String> LOG_QUEUE = new LinkedBlockingQueue<>(MAX_QUEUE_CAPACITY);
    private static final AtomicBoolean WORKER_STARTED = new AtomicBoolean(false);

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
        ensureWorkerStarted();
    }

    @Override
    public void println(String x) {
        log(priority, tag, x, null);
    }

    private static final SimpleDateFormat FILE_DATE_FORMAT =
            new SimpleDateFormat("yyyyMMdd", Locale.ROOT);
    private static final SimpleDateFormat LOG_TIME_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.ROOT);

    public static void log(
            int priority,
            String tag,
            String message,
            Throwable throwable
    ) {
        ensureWorkerStarted();

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

        if (!LOG_QUEUE.offer(line.toString())) {
            LOG_QUEUE.poll();
            LOG_QUEUE.offer(line.toString());
        }
    }

    private static void ensureWorkerStarted() {
        if (WORKER_STARTED.compareAndSet(false, true)) {
            Thread worker = new Thread(XposedLogPrinter::drainLogLoop, "NPatch-LogFlusher");
            worker.setDaemon(true);
            worker.setPriority(Thread.MIN_PRIORITY);
            worker.start();
        }
    }

    private static void drainLogLoop() {
        BufferedWriter writer = null;
        String openedDate = null;
        List<String> batch = new ArrayList<>(64);

        while (true) {
            try {
                String first = LOG_QUEUE.poll(1, TimeUnit.SECONDS);
                if (first != null) {
                    batch.add(first);
                    LOG_QUEUE.drainTo(batch, 63);
                }

                if (!batch.isEmpty()) {
                    String currentDate = FILE_DATE_FORMAT.format(new Date());
                    if (writer == null || !currentDate.equals(openedDate)) {
                        if (writer != null) {
                            try {
                                writer.flush();
                                writer.close();
                            } catch (Exception ignored) {
                            }
                            writer = null;
                        }

                        String pkgName = ActivityThread.currentPackageName();
                        if (pkgName != null && !pkgName.isEmpty()) {
                            File f = new File(Environment.getExternalStorageDirectory() + "/Android/media/" + pkgName + "/npatch/log/");
                            if (f.isDirectory() || f.mkdirs()) {
                                File logFile = new File(f, currentDate + ".log");
                                writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8), 8192);
                                openedDate = currentDate;
                            }
                        }
                    }

                    if (writer != null) {
                        for (String item : batch) {
                            writer.write(item);
                            writer.newLine();
                        }
                        writer.flush();
                    }
                    batch.clear();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable error) {
                Log.e(TAG, "Error in log flush worker", error);
                if (writer != null) {
                    try {
                        writer.close();
                    } catch (Exception ignored) {
                    }
                    writer = null;
                }
                batch.clear();
            }
        }
    }
}
