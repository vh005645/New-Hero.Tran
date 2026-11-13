package vn.herotran.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;

/**
 * Tự đặt lại ngôn ngữ giọng nói của Google (katniss) thành Tiếng Việt khi phát hiện app bị cài lại
 * (firstInstallTime đổi). Chạy bằng cách điều khiển màn hình SpeechSettingsActivity qua
 * VoiceAccessibilityService, không đụng tới locale hệ thống.
 */
public class VoiceLangFixer {
    private static final String TAG = "VoiceLangFixer";
    private static final String KATNISS_PKG = "com.google.android.katniss";
    private static final String SPEECH_ACTIVITY = "com.google.android.katniss.setting.SpeechSettingsActivity";
    private static final String LANG_LABEL = "Language";
    public static final String VI_LABEL = "Tiếng Việt (Việt Nam)";
    private static final String PREF = "voice_lang";
    private static final String KEY_INSTALL = "katniss_first_install";
    private static final int MAX_TICKS = 120;
    private static final long TICK_MS = 500;

    private static boolean sAttempted = false;
    private static boolean sRunning = false;

    private final AccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long installTime;
    private final String target;
    private int state;
    private int ticks;
    private int doneTicks;

    private VoiceLangFixer(AccessibilityService s, long installTime, String target) {
        this.service = s;
        this.installTime = installTime;
        this.target = target;
    }

    private static SharedPreferences prefs(Context c) {
        Context ctx = c;
        android.os.UserManager um = (android.os.UserManager) c.getSystemService(Context.USER_SERVICE);
        if (um != null && !um.isUserUnlocked()) {
            ctx = c.createDeviceProtectedStorageContext();
        }
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** Gọi từ VoiceAccessibilityService (đã bind). Chỉ chạy khi katniss mới được cài lại. */
    public static void checkAndRun(AccessibilityService s) {
        if (sAttempted || sRunning) {
            return;
        }
        try {
            PackageInfo pi = s.getPackageManager().getPackageInfo(KATNISS_PKG, 0);
            long saved = prefs(s).getLong(KEY_INSTALL, -1L);
            if (saved == pi.firstInstallTime) {
                return;
            }
            Log.i(TAG, "Katniss install time changed (" + saved + " -> " + pi.firstInstallTime + "), fixing voice language");
            sAttempted = true;
            sRunning = true;
            VoiceLangFixer f = new VoiceLangFixer(s, pi.firstInstallTime, VI_LABEL);
            f.start();
        } catch (Throwable t) {
            Log.e(TAG, "checkAndRun error", t);
        }
    }

    /** Người dùng chọn tay từ menu Cài đặt: đặt ngôn ngữ giọng nói Google thành label (vd VI_LABEL). */
    public static boolean applyNow(AccessibilityService s, String label) {
        if (sRunning) {
            return false;
        }
        sAttempted = true;
        sRunning = true;
        new VoiceLangFixer(s, -1L, label).start();
        return true;
    }

    private void start() {
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName(KATNISS_PKG, SPEECH_ACTIVITY));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            service.startActivity(i);
        } catch (Throwable t) {
            Log.e(TAG, "cannot open speech settings", t);
            sRunning = false;
            return;
        }
        state = 0;
        ticks = 0;
        handler.postDelayed(tick, 1500);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            boolean again = false;
            try {
                again = step();
            } catch (Throwable t) {
                Log.e(TAG, "step error", t);
            }
            ticks++;
            if (again && ticks < MAX_TICKS) {
                handler.postDelayed(this, TICK_MS);
            } else {
                if (again) {
                    Log.w(TAG, "timeout, state=" + state + " - voice language NOT confirmed");
                    goHome();
                }
                sRunning = false;
            }
        }
    };

    /** @return true nếu cần tick tiếp. */
    private boolean step() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) {
            return true;
        }
        try {
            CharSequence pkg = root.getPackageName();
            if (pkg == null || !KATNISS_PKG.contentEquals(pkg)) {
                return true;
            }
            if (state == 0) {
                // Màn hình Speech: nếu subtitle đã là Tiếng Việt thì xong luôn
                if (hasText(root, target)) {
                    return finish();
                }
                if (clickText(root, LANG_LABEL)) {
                    state = 1;
                }
                return true;
            }
            if (state == 1) {
                if (clickText(root, target)) {
                    state = 2;
                    doneTicks = 0;
                    return true;
                }
                scrollList(root);
                return true;
            }
            // state 2: chờ quay lại màn hình Speech và xác nhận subtitle
            doneTicks++;
            if (hasText(root, LANG_LABEL) && hasText(root, target)) {
                return finish();
            }
            if (doneTicks > 12) {
                state = 0;
            }
            return true;
        } finally {
            root.recycle();
        }
    }

    private boolean finish() {
        if (installTime > 0) {
            prefs(service).edit().putLong(KEY_INSTALL, installTime).apply();
        }
        Log.i(TAG, "Voice language set to " + target + "");
        goHome();
        sRunning = false;
        return false;
    }

    private void goHome() {
        try {
            Intent home = new Intent(service, HomeActivity.class);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            service.startActivity(home);
        } catch (Throwable t) {
            Log.e(TAG, "goHome error", t);
        }
    }

    private static boolean hasText(AccessibilityNodeInfo root, String text) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        boolean found = nodes != null && !nodes.isEmpty();
        if (nodes != null) {
            for (AccessibilityNodeInfo n : nodes) {
                n.recycle();
            }
        }
        return found;
    }

    private static boolean clickText(AccessibilityNodeInfo root, String text) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes == null || nodes.isEmpty()) {
            return false;
        }
        boolean clicked = false;
        for (AccessibilityNodeInfo n : nodes) {
            if (!clicked) {
                AccessibilityNodeInfo cur = n;
                int depth = 0;
                while (cur != null && depth < 6) {
                    if (cur.isClickable() && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        clicked = true;
                        Log.i(TAG, "clicked '" + text + "'");
                        break;
                    }
                    AccessibilityNodeInfo parent = cur.getParent();
                    if (cur != n) {
                        cur.recycle();
                    }
                    cur = parent;
                    depth++;
                }
                if (cur != null && cur != n) {
                    cur.recycle();
                }
            }
            n.recycle();
        }
        return clicked;
    }

    private static boolean scrollList(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        if (node.isScrollable() && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c != null) {
                boolean r = scrollList(c);
                c.recycle();
                if (r) {
                    return true;
                }
            }
        }
        return false;
    }
}


