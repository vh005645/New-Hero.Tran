package vn.herotran.launcher;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

public class HomeWatcherService extends Service {
    private static final String TAG = "HomeWatcherService";
    private BroadcastReceiver screenReceiver;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        startAsForeground();
        registerScreenReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground();
        registerScreenReceiver();
        return START_STICKY;
    }

    private void startAsForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                String channelId = "tv_launcher_fg";
                NotificationChannel channel = new NotificationChannel(
                        channelId,
                        "TV Launcher Service",
                        NotificationManager.IMPORTANCE_LOW);
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) {
                    nm.createNotificationChannel(channel);
                }
                Notification notification = new Notification.Builder(this, channelId)
                        .setContentTitle("TV Launcher")
                        .setSmallIcon(android.R.drawable.ic_menu_info_details)
                        .build();
                startForeground(1001, notification);
            } catch (Exception e) {
                Log.e(TAG, "startForeground error", e);
            }
        }
    }

    private void registerScreenReceiver() {
        if (screenReceiver == null) {
            Log.d(TAG, "registerScreenReceiver called");
            screenReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(final Context context, Intent intent) {
                    String action = intent != null ? intent.getAction() : null;
                    Log.i(TAG, "onReceive wake action: " + action);

                    if (Intent.ACTION_SCREEN_ON.equals(action)
                            || Intent.ACTION_USER_PRESENT.equals(action)
                            || Intent.ACTION_DREAMING_STOPPED.equals(action)
                            || "mitv.action.SCREEN_TURN_ON".equals(action)
                            || "mitv.action.STR_BOOT_COMPLETED".equals(action)
                            || "mitv.action.STR_RESUME".equals(action)
                            || "com.xiaomi.action.STR_RESUME".equals(action)
                            || "mitv.action.RESUME".equals(action)
                            || "mitv.action.WAKE_UP".equals(action)) {
                        
                        // 1. Phục hồi ngay lập tức
                        LocaleHelper.enforceEnglish(context);
                        AdbHelper.ensureAdbEnabled(context, true);
                        VoiceHelper.ensureVoiceConfigured(context);
                        goHome(context);

                        // 2. Nhắc lại nhiều lần để đè bẹp FallbackHome và mọi app cũ
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                goHome(context);
                                AdbHelper.ensureAdbEnabled(context, true);
                                VoiceHelper.ensureVoiceConfigured(context);
                            }
                        }, 150);

                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                goHome(context);
                                AdbHelper.ensureAdbEnabled(context, true);
                            }
                        }, 500);

                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                goHome(context);
                                AdbHelper.ensureAdbEnabled(context, true);
                            }
                        }, 1200);

                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                AdbHelper.ensureAdbEnabled(context, true);
                            }
                        }, 3000);
                    }
                }
            };

            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            filter.addAction(Intent.ACTION_DREAMING_STOPPED);
            filter.addAction("mitv.action.SCREEN_TURN_ON");
            filter.addAction("mitv.action.STR_BOOT_COMPLETED");
            filter.addAction("mitv.action.STR_RESUME");
            filter.addAction("com.xiaomi.action.STR_RESUME");
            filter.addAction("mitv.action.RESUME");
            filter.addAction("mitv.action.WAKE_UP");
            filter.addAction(Intent.ACTION_BOOT_COMPLETED);
            registerReceiver(screenReceiver, filter);
        }
    }

    private void goHome(Context context) {
        Log.d(TAG, "goHome executing");
        try {
            VoiceAccessibilityService vas = VoiceAccessibilityService.getInstance();
            if (vas != null) {
                vas.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
            }
        } catch (Throwable t) {
            Log.w(TAG, "performGlobalAction failed", t);
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
        } catch (Exception e) {
            Log.e(TAG, "goHome error", e);
        }
    }

    @Override
    public void onDestroy() {
        if (screenReceiver != null) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (Exception ignored) {
            }
            screenReceiver = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
