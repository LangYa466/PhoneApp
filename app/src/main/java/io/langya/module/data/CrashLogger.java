package io.langya.module.data;

import android.content.Context;
import android.os.Build;
import timber.log.Timber;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 全局未捕获异常落盘崩溃发生时把堆栈写入 filesDir/crash.log 
 * 下次启动可以从设置里看上次崩了啥
 */
public final class CrashLogger {

    private static final String FILE_NAME = "crash.log";

    private CrashLogger() {}

    public static void install(Context ctx) {
        final var appCtx = ctx.getApplicationContext();
        final var previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                writeCrash(appCtx, thread, throwable);
            } catch (Throwable t) {
                Timber.e(t, "failed to persist crash");
            }
            if (previous != null) previous.uncaughtException(thread, throwable);
        });
        Timber.d("installed");
    }

    private static void writeCrash(Context ctx, Thread thread, Throwable t) {
        var sw = new StringWriter();
        var pw = new PrintWriter(sw);
        var now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        pw.println("====================================");
        pw.println("Time:    " + now);
        pw.println("Thread:  " + thread.getName() + " (id=" + thread.getId() + ")");
        pw.println("Model:   " + Build.MANUFACTURER + " " + Build.MODEL);
        pw.println("Android: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
        pw.println("App ABI: " + (Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?"));
        pw.println("------------------------------------");
        t.printStackTrace(pw);
        pw.println();
        pw.flush();

        var file = file(ctx);
        try (var fw = new java.io.FileWriter(file, false)) {  // 覆盖：只保留最近一次
            fw.write(sw.toString());
        } catch (Throwable e) {
            Timber.e(e, "write failed");
        }
    }

    public static boolean has(Context ctx) {
        return file(ctx).exists() && file(ctx).length() > 0;
    }

    public static String read(Context ctx) {
        var f = file(ctx);
        if (!f.exists()) return "";
        try (var r = new java.io.BufferedReader(new java.io.FileReader(f))) {
            var sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Throwable e) {
            Timber.e(e, "read failed");
            return "";
        }
    }

    public static void clear(Context ctx) {
        var f = file(ctx);
        if (f.exists()) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE_NAME);
    }
}
