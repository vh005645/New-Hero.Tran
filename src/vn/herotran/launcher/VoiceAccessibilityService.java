package vn.herotran.launcher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;
import java.util.Locale;

public class VoiceAccessibilityService extends AccessibilityService {
    private static final String TAG = "VoiceA11yService";
    private static final String KATNISS_PKG = "com.google.android.katniss";
    private static volatile boolean sRunning = false;
    private static volatile VoiceAccessibilityService sInstance = null;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private long lastVoiceSearchTime = 0;
    private long voiceKeyDownTime = 0;
    private long homeKeyDownTime = 0;
    private boolean homeLongPressed = false;

    private final Runnable homeLongPressRunnable = new Runnable() {
        @Override
        public void run() {
            homeLongPressed = true;
            Log.i(TAG, "HOME key long-press detected -> open Recent Apps");
            openRecentApps();
        }
    };

    public static boolean isRunning() {
        return sRunning;
    }

    public static VoiceAccessibilityService getInstance() {
        return sInstance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        sRunning = true;
        Log.i(TAG, "VoiceAccessibilityService connected");

        // 1. Kích hoạt tức thì ADB và tiếng Anh
        try {
            AdbHelper.ensureAdbEnabled(this, true);
        } catch (Throwable t) {
            Log.w(TAG, "Failed ensureAdbEnabled in onServiceConnected", t);
        }
        try {
            LocaleHelper.enforceEnglish(this);
        } catch (Throwable t) {
            Log.w(TAG, "Failed enforceEnglish in onServiceConnected", t);
        }

        // 2. Chuyển thẳng về HomeActivity
        try {
            Intent home = new Intent(this, HomeActivity.class);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(home);
        } catch (Throwable t) {
            Log.w(TAG, "Failed startActivity in onServiceConnected", t);
        }

        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    VoiceLangFixer.checkAndRun(VoiceAccessibilityService.this);
                } catch (Throwable t) {
                    Log.w(TAG, "VoiceLangFixer error", t);
                }
            }
        }, 5000);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        sRunning = false;
        sInstance = null;
        Log.i(TAG, "VoiceAccessibilityService unbound");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        sRunning = false;
        sInstance = null;
        mHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        try {
            int type = event.getEventType();
            CharSequence pkg = event.getPackageName();
            String pkgName = pkg != null ? pkg.toString() : "";

            // Xử lý chặn FallbackHome của Xiaomi khi thức dậy / thoát app
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                CharSequence cls = event.getClassName();
                String clsName = cls != null ? cls.toString() : "";

                boolean isFallbackHome = false;
                if (clsName.contains("FallbackHome")) {
                    isFallbackHome = true;
                }

                if (isFallbackHome) {
                    Log.i(TAG, "Intercepted Xiaomi FallbackHome -> redirecting to HomeActivity");
                    try {
                        performGlobalAction(GLOBAL_ACTION_HOME);
                    } catch (Throwable ignored) {
                    }
                    try {
                        AdbHelper.ensureAdbEnabled(this, true);
                        Intent home = new Intent(this, HomeActivity.class);
                        home.setAction(Intent.ACTION_MAIN);
                        home.addCategory(Intent.CATEGORY_HOME);
                        home.putExtra("EXTRA_GO_HOME", true);
                        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                        startActivity(home);
                    } catch (Throwable t) {
                        Log.e(TAG, "Failed to redirect from FallbackHome", t);
                    }
                }
            }

            // Bắt sự kiện nhận diện giọng nói từ Google Katniss để mở app
            if (KATNISS_PKG.equals(pkgName)) {
                if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                        || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                    long now = SystemClock.uptimeMillis();
                    if (now - lastVoiceSearchTime < 5000) {
                        if (checkAndInterceptVoiceAppCommand()) {
                            mHandler.removeCallbacksAndMessages(null);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "onAccessibilityEvent error", t);
        }
    }

    @Override
    public void onInterrupt() {
        sRunning = false;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        try {
            int keyCode = event.getKeyCode();
            int action = event.getAction();

            // 1. Phím HOME: Nhấn nhanh -> Về Trang chủ. Nhấn giữ (Long-press > 750ms) -> Mở Recent Apps (Ứng dụng chạy nền)
            if (keyCode == KeyEvent.KEYCODE_HOME) {
                if (action == KeyEvent.ACTION_DOWN) {
                    if (event.getRepeatCount() == 0) {
                        homeKeyDownTime = SystemClock.uptimeMillis();
                        homeLongPressed = false;
                        mHandler.removeCallbacks(homeLongPressRunnable);
                        mHandler.postDelayed(homeLongPressRunnable, 750);
                    }
                    return true;
                } else if (action == KeyEvent.ACTION_UP) {
                    mHandler.removeCallbacks(homeLongPressRunnable);
                    if (!homeLongPressed) {
                        goHome();
                    }
                    return true;
                }
                return true;
            }

            // 1b. Phím APP_SWITCH (Recent Apps) hoặc phím chuyển ứng dụng
            if (keyCode == KeyEvent.KEYCODE_APP_SWITCH) {
                if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    openRecentApps();
                    return true;
                }
            }

            // 2. Phím VOICE trên remote Xiaomi: 0 (UNKNOWN), 231 (VOICE_ASSIST), 84 (SEARCH), 219 (ASSIST)
            if (keyCode == KeyEvent.KEYCODE_UNKNOWN
                    || keyCode == KeyEvent.KEYCODE_VOICE_ASSIST
                    || keyCode == KeyEvent.KEYCODE_SEARCH
                    || keyCode == KeyEvent.KEYCODE_ASSIST) {

                if (action == KeyEvent.ACTION_DOWN) {
                    // Chỉ trigger khi bắt đầu nhấn xuống (bỏ qua các sự kiện repeat khi giữ phím)
                    if (event.getRepeatCount() == 0) {
                        voiceKeyDownTime = SystemClock.uptimeMillis();
                        long now = voiceKeyDownTime;
                        if (now - lastVoiceSearchTime > 800) {
                            lastVoiceSearchTime = now;
                            startVoiceSearch();
                        }
                    }
                    return true;
                } else if (action == KeyEvent.ACTION_UP) {
                    long pressDuration = SystemClock.uptimeMillis() - voiceKeyDownTime;
                    Log.i(TAG, "Voice key released (ACTION_UP), duration=" + pressDuration + "ms");
                    
                    mHandler.removeCallbacksAndMessages(null);
                    
                    // Bước 1: Sau 120ms kiểm tra xem câu nói có phải khẩu lệnh mở app không
                    mHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (checkAndInterceptVoiceAppCommand()) {
                                return; // Đã mở app!
                            }
                            // Bước 2: Kiểm tra lại sau 220ms nếu Katniss vừa hoàn tất nhận diện âm cuối
                            mHandler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    if (checkAndInterceptVoiceAppCommand()) {
                                        return; // Đã mở app!
                                    }
                                    // Không phải app -> Submit Katniss để tìm kiếm YouTube
                                    boolean ok = finishAndSubmitSearch();
                                    if (!ok) {
                                        mHandler.postDelayed(new Runnable() {
                                            @Override
                                            public void run() {
                                                finishAndSubmitSearch();
                                            }
                                        }, 250);
                                    }
                                }
                            }, 220);
                        }
                    }, 120);
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "onKeyEvent error", t);
        }
        return super.onKeyEvent(event);
    }

    private void goHome() {
        try {
            Intent home = new Intent(this, HomeActivity.class);
            home.setAction(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.putExtra("EXTRA_GO_HOME", true);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(home);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to launch HomeActivity", e);
        }
    }

    private void openRecentApps() {
        boolean ok = false;
        try {
            ok = performGlobalAction(GLOBAL_ACTION_RECENTS);
            Log.i(TAG, "performGlobalAction(GLOBAL_ACTION_RECENTS) result: " + ok);
        } catch (Throwable t) {
            Log.w(TAG, "performGlobalAction(GLOBAL_ACTION_RECENTS) failed", t);
        }
        if (!ok) {
            try {
                Intent recents = new Intent();
                recents.setComponent(new ComponentName(
                        "com.xiaomi.mitv.tvmanager", "com.xiaomi.mitv.tvmanager.boost.ProcessManagerActivity"));
                recents.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(recents);
            } catch (Throwable t2) {
                Log.e(TAG, "Failed to start ProcessManagerActivity fallback", t2);
            }
        }
    }

    private void startVoiceSearch() {
        Log.i(TAG, "Starting voice search");
        try {
            Intent voice = new Intent(Intent.ACTION_ASSIST);
            voice.setComponent(new ComponentName(
                    KATNISS_PKG, "com.google.android.katniss.search.SearchActivity"));
            voice.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "vi-VN");
            voice.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN");
            voice.putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", new String[]{"vi-VN", "en-US"});
            voice.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(voice);
        } catch (Throwable e) {
            try {
                Intent web = new Intent("android.speech.action.WEB_SEARCH");
                web.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "vi-VN");
                web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(web);
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean finishAndSubmitSearch() {
        Log.i(TAG, "finishAndSubmitSearch triggered");
        boolean clicked = false;
        boolean isKatnissWindow = false;
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root != null) {
                CharSequence pkg = root.getPackageName();
                isKatnissWindow = pkg != null && KATNISS_PKG.contentEquals(pkg);

                // 1. Tìm biểu tượng mic / logo của Katniss (nhấp vào sẽ kết thúc nghe và gửi từ khóa)
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(
                        "com.google.android.katniss:id/logo_view");
                if (nodes != null && !nodes.isEmpty()) {
                    for (AccessibilityNodeInfo node : nodes) {
                        if (node != null) {
                            if (!clicked && node.isClickable()
                                    && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                                Log.i(TAG, "Clicked logo_view via AccessibilityNodeInfo");
                                clicked = true;
                            }
                            try {
                                node.recycle();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
                // 2. Nếu không có logo_view, thử click vào search_bar hoặc focus input
                if (!clicked && isKatnissWindow) {
                    AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                    if (focused != null) {
                        if (focused.isClickable() && focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            Log.i(TAG, "Clicked focus input node");
                            clicked = true;
                        }
                        try {
                            focused.recycle();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "Error clicking via AccessibilityNodeInfo", e);
        } finally {
            if (root != null) {
                try {
                    root.recycle();
                } catch (Throwable ignored) {
                }
            }
        }

        // 3. Fallback cử chỉ chạm: tap vào trung tâm nút mic (960, 130)
        if (!clicked && isKatnissWindow && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                DisplayMetrics dm = getResources().getDisplayMetrics();
                float tapX = dm.widthPixels > 0 ? (dm.widthPixels / 2.0f) : 960f;
                float tapY = dm.heightPixels > 0 ? (130f * dm.heightPixels / 1080f) : 130f;

                Path path = new Path();
                path.moveTo(tapX, tapY);
                GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 50);
                GestureDescription.Builder builder = new GestureDescription.Builder();
                builder.addStroke(stroke);
                dispatchGesture(builder.build(), null, null);
                Log.i(TAG, "Dispatched fallback gesture tap at (" + tapX + ", " + tapY + ")");
                clicked = true;
            } catch (Throwable e) {
                Log.e(TAG, "dispatchGesture error", e);
            }
        }
        return clicked;
    }

    private boolean checkAndInterceptVoiceAppCommand() {
        String voiceText = getKatnissSpokenText();
        if (voiceText != null && !voiceText.isEmpty()) {
            Log.i(TAG, "Katniss voice text detected: '" + voiceText + "'");
            boolean opened = HomeActivity.handleVoiceQuery(this, voiceText);
            if (opened) {
                Log.i(TAG, "App matched and launched for: '" + voiceText + "' -> Dismissing Katniss");
                performGlobalAction(GLOBAL_ACTION_BACK);
                return true;
            }
        }
        return false;
    }

    private String getKatnissSpokenText() {
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root == null) return null;
            CharSequence pkg = root.getPackageName();
            if (pkg == null || !KATNISS_PKG.contentEquals(pkg)) {
                return null;
            }
            return findBestVoiceText(root);
        } catch (Throwable t) {
            Log.e(TAG, "getKatnissSpokenText error", t);
            return null;
        } finally {
            if (root != null) {
                try {
                    root.recycle();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private String findBestVoiceText(AccessibilityNodeInfo node) {
        if (node == null) return null;
        try {
            CharSequence text = node.getText();
            if (text != null && text.length() > 0) {
                String str = text.toString().trim();
                if (isValidVoiceCandidate(str)) {
                    return str;
                }
            }
            CharSequence desc = node.getContentDescription();
            if (desc != null && desc.length() > 0) {
                String str = desc.toString().trim();
                if (isValidVoiceCandidate(str)) {
                    return str;
                }
            }
            int count = node.getChildCount();
            for (int i = 0; i < count; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    try {
                        String res = findBestVoiceText(child);
                        if (res != null) {
                            return res;
                        }
                    } finally {
                        child.recycle();
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "findBestVoiceText error", t);
        }
        return null;
    }

    private boolean isValidVoiceCandidate(String str) {
        if (str == null || str.length() < 2) return false;
        String s = str.toLowerCase(Locale.ROOT);
        if (s.contains("thử nói") || s.contains("try saying")
                || s.contains("say something") || s.contains("nói để tìm")
                || s.contains("đang nghe") || s.contains("listening")
                || s.contains("đang nhận diện") || s.contains("recognizing")) {
            return false;
        }
        return true;
    }
}
