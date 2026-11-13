package vn.herotran.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(final Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        Log.i(TAG, "BootReceiver received action: " + action);

        try {
            LocaleHelper.enforceEnglish(context);
        } catch (Throwable ignored) {
        }

        // BẮT BUỘC dùng force=true để gọi TvService transaction 4004 mở lại cổng 5555
        try {
            AdbHelper.ensureAdbEnabled(context, true);
        } catch (Throwable ignored) {
        }

        try {
            VoiceHelper.ensureVoiceConfigured(context);
        } catch (Throwable ignored) {
        }

        launchHome(context);

        try {
            Intent serviceIntent = new Intent(context, HomeWatcherService.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed start HomeWatcherService from BootReceiver", t);
        }

        // Hẹn giờ phục hồi lại lần 2, lần 3 và lần 4 (khi Wi-Fi ổn định) để đè bẹp FallbackHome và mở lại ADB
        final Context appCtx = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                launchHome(appCtx);
                AdbHelper.ensureAdbEnabled(appCtx, true);
                VoiceHelper.ensureVoiceConfigured(appCtx);
            }
        }, 500);

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                launchHome(appCtx);
                AdbHelper.ensureAdbEnabled(appCtx, true);
                VoiceHelper.ensureVoiceConfigured(appCtx);
            }
        }, 1500);

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                AdbHelper.ensureAdbEnabled(appCtx, true);
            }
        }, 3500);
    }

    private static void launchHome(Context context) {
        try {
            VoiceAccessibilityService vas = VoiceAccessibilityService.getInstance();
            if (vas != null) {
                vas.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
            }
        } catch (Throwable ignored) {
        }
        try {
            Intent home = new Intent(context, HomeActivity.class);
            home.setAction(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            context.startActivity(home);
            Log.i(TAG, "BootReceiver launched HomeActivity successfully");
        } catch (Throwable t) {
            Log.e(TAG, "BootReceiver launchHome failed", t);
        }
    }
}
