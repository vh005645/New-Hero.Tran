package vn.herotran.launcher;

import android.content.ContentResolver;
import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import java.lang.reflect.Method;

public class AdbHelper {
    private static final String TAG = "AdbHelper";
    private static final long MIN_RECHECK_INTERVAL_MS = 5 * 60 * 1000L; // 5 phút
    private static long lastTvServiceCallTime = 0;

    public static void ensureAdbEnabled(Context context) {
        ensureAdbEnabled(context, false);
    }

    public static void ensureAdbEnabled(Context context, boolean force) {
        ContentResolver cr = context.getContentResolver();
        boolean alreadyEnabled = false;
        try {
            alreadyEnabled = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1;
        } catch (Throwable ignored) {
        }

        // 1. Cài đặt adb_enabled trong Settings Global & Secure nếu chưa bật hoặc khi ép bật
        if (force || !alreadyEnabled) {
            try {
                Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1);
            } catch (Throwable t) {
                Log.w(TAG, "Global.adb_enabled error", t);
            }
            try {
                Settings.Secure.putInt(cr, "adb_enabled", 1);
            } catch (Throwable t) {
                Log.w(TAG, "Secure.adb_enabled error", t);
            }
        }

        // 2. Xiaomi TvService transaction 4004 (bật adb gốc + sys.mitv.adb.default = true)
        long now = SystemClock.elapsedRealtime();
        if (!force && alreadyEnabled && lastTvServiceCallTime > 0
                && (now - lastTvServiceCallTime) < MIN_RECHECK_INTERVAL_MS) {
            return;
        }

        try {
            Method getService = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, "TvService");
            if (binder != null) {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken("mitv.internal.ITvService");
                    data.writeInt(1); // 1 = enable ADB
                    binder.transact(4004, data, reply, 0);
                    reply.readException();
                    lastTvServiceCallTime = now;
                    Log.i(TAG, "TvService 4004 enable ADB executed successfully!");
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            } else {
                Log.w(TAG, "TvService binder is null");
            }
        } catch (Throwable t) {
            Log.e(TAG, "TvService binder call failed", t);
        }
    }
}
