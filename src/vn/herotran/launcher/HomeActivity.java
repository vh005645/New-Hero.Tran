package vn.herotran.launcher;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.content.DialogInterface;
import android.net.Uri;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;

import java.io.File;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Màn hình chính Redmi TV Launcher:
 * - Top Status Bar (Tabs Home/Apps, Voice search, Giờ & Ngày tháng ở giữa, Công cụ & CPU/RAM/ROM ở phải)
 * - Dashboard 2 card cân đối (Thời tiết + Âm lịch & Sự kiện, Giá xăng dầu & Giá vàng)
 * - Bottom App Dock & Danh sách tất cả ứng dụng
 */
public class HomeActivity extends Activity {

    private static volatile HomeActivity sInstance;

    public static HomeActivity getInstance() {
        return sInstance;
    }

    private static final int CARD_COLOR = Color.parseColor("#590B132B");
    private static final String YOUTUBE_PKG = "com.google.android.youtube.tv";

    private final Handler handler = new Handler();

    private FrameLayout body;
    private static final List<String> PINNED_PACKAGES = Arrays.asList(
            "com.google.android.youtube.tv",
            "com.dinhlap.dlstore",
            "net.fptplay.ottbox",
            "cm.aptoidetv.pt",
            "com.iqiyi.i18n.tv",
            "dev.dworks.apps.anexplorer",
            "vn.vtv.vtvgotv",
            "com.viettel.tv360.tv",
            "com.vieon.tv"
    );

    private LinearLayout tabApps;
    private TextView clockTime;
    private TextView clockDate;
    private TextView cardLunar;
    private TextView cardEvent;
    private TextView weatherTemp;
    private TextView weatherIcon;
    private TextView weatherCond;
    private TextView weatherCity;
    private LinearLayout weatherDays;
    private TextView weatherFooter;
    private TextView fuelE5;
    private TextView fuelE10;
    private TextView fuelDiesel;
    private TextView goldSjc;
    private TextView goldNhan;
    private TextView goldWorld;
    private TextView marketFooter;

    private List<ResolveInfo> apps = new ArrayList<>();
    // Toan bo app (ke ca app bi an), da sap xep theo thu tu nguoi dung chon
    private List<ResolveInfo> allApps = new ArrayList<>();
    // Package dang o che do 'Sap xep vi tri' (null = khong sap xep)
    private String movingPkg = null;
    private final Map<String, String> appLabelCache = new HashMap<>();
    private final Map<String, Integer> drawableResCache = new HashMap<>();
    private boolean appsDirty = false;
    private View homeView;
    private View appsView;
    private LinearLayout homeAppRow;
    private LinearLayout appsGrid;
    private TextView appsCountText;
    private TextView sysInfoText;
    private ImageView wifiIconView;
    private LinearLayout dashboardView;
    private View weatherCardView;
    private View marketCardView;

    private android.os.HandlerThread bgThread;
    private Handler bgHandler;
    private String cachedRomText = "";
    private long lastRomUpdateTime = 0;

    private final SimpleDateFormat clockTimeFormat = new SimpleDateFormat("HH:mm", Locale.ENGLISH);
    private final SimpleDateFormat clockDateFormat = new SimpleDateFormat("EEEE, dd/MM", new Locale("vi", "VN"));
    private int lastCalculatedDayOfYear = -1;
    private int lastCalculatedYear = -1;
    private String cachedLunarText = "";
    private String cachedEventText = "";

    private final android.util.LruCache<String, Bitmap> thumbCache = new android.util.LruCache<String, Bitmap>(32) {};

    private String lastSysInfoRendered = "";

    private final Runnable sysInfoWorkerTask = new Runnable() {
        @Override
        public void run() {
            final int cpu = getCpuUsagePercent();
            final String ram = getRamUsageText();
            long now = android.os.SystemClock.elapsedRealtime();
            if (cachedRomText.isEmpty() || (now - lastRomUpdateTime) > 60_000L) {
                cachedRomText = getRomUsageText();
                lastRomUpdateTime = now;
            }
            StringBuilder sb = new StringBuilder(48);
            sb.append("CPU: ").append(cpu).append("%");
            if (!ram.isEmpty()) {
                sb.append(" · RAM: ").append(ram);
            }
            if (!cachedRomText.isEmpty()) {
                sb.append(" · ROM: ").append(cachedRomText);
            }
            final String newText = sb.toString();
            if (newText.equals(lastSysInfoRendered)) {
                return;
            }
            lastSysInfoRendered = newText;
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (sysInfoText != null && !newText.contentEquals(sysInfoText.getText())) {
                        sysInfoText.setText(newText);
                    }
                }
            });
        }
    };

    private final Runnable sysInfoTick = new Runnable() {
        @Override
        public void run() {
            if (bgHandler != null) {
                bgHandler.removeCallbacks(sysInfoWorkerTask);
                bgHandler.post(sysInfoWorkerTask);
            }
            handler.postDelayed(this, 5000);
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            appsDirty = true;
            lastRomUpdateTime = 0; // Làm mới dung lượng ROM sau khi cài/gỡ ứng dụng
            refreshApps();
        }
    };

    private final BroadcastReceiver timeTickReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateDateViews();
        }
    };

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            updateDateViews();
            handler.postDelayed(this, 30_000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sInstance = this;
        bgThread = new android.os.HandlerThread("LauncherBgWorker");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());

        apps = loadLeanbackApps();
        appsDirty = false;
        setContentView(buildUi());
        showTab(true);

        if (android.os.Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] {
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, 101);
            }
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        registerReceiver(packageReceiver, filter);

        IntentFilter timeFilter = new IntentFilter(Intent.ACTION_TIME_TICK);
        registerReceiver(timeTickReceiver, timeFilter);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sInstance == this) {
            sInstance = null;
        }
        handler.removeCallbacksAndMessages(null);
        if (bgHandler != null) {
            bgHandler.removeCallbacksAndMessages(null);
        }
        if (bgThread != null) {
            bgThread.quitSafely();
            bgThread = null;
        }
        try {
            unregisterReceiver(packageReceiver);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(timeTickReceiver);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            android.provider.Settings.Global.putString(getContentResolver(), "disable_back_home_whitelist", "disable");
            android.provider.Settings.Global.putInt(getContentResolver(), "device_provisioned", 1);
            android.provider.Settings.Secure.putInt(getContentResolver(), "user_setup_complete", 1);
        } catch (Throwable ignored) {
        }
        LocaleHelper.enforceEnglish(this);
        AdbHelper.ensureAdbEnabled(this, true);
        VoiceHelper.ensureVoiceConfigured(this);
        updateDateViews();
        handler.removeCallbacks(clockTick);
        handler.postDelayed(clockTick, 30_000);
        loadWeather();
        loadMarket();
        startHomeWatcher();
        if (wifiIconView != null) {
            wifiIconView.setAlpha(isWifiConnected() ? 1.0f : 0.5f);
        }
        if (appsDirty) {
            refreshApps();
        }
        handler.removeCallbacks(sysInfoTick);
        handler.post(sysInfoTick);
    }

    private void startHomeWatcher() {
        try {
            Intent watcher = new Intent(this, HomeWatcherService.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(watcher);
            } else {
                startService(watcher);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Màn hình chính: nút Back không thoát launcher (dialog vẫn tự đóng khi bấm
     * Back).
     */
    @Override
    public void onBackPressed() {
    }

    private final List<android.app.Dialog> openDialogs = new ArrayList<>();

    /** Hiện dialog và nhớ lại để đóng khi bấm HOME. */
    private void showTracked(android.app.Dialog d) {
        openDialogs.add(d);
        d.show();
    }

    private void dismissAllOpenDialogs() {
        for (android.app.Dialog d : openDialogs) {
            try {
                if (d != null && d.isShowing()) {
                    d.dismiss();
                }
            } catch (Throwable t) {
                android.util.Log.w("HomeActivity", "dismiss dialog failed", t);
            }
        }
        openDialogs.clear();
    }

    /** Phím HOME: đóng mọi dialog, về tab Trang chủ. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        cancelMoveMode();
        dismissAllOpenDialogs();
        showTab(true);
        if (homeView != null) {
            homeView.requestFocus();
        }
    }

    /** Phím voice/search/home trên remote. */
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode == android.view.KeyEvent.KEYCODE_HOME) {
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                cancelMoveMode();
                dismissAllOpenDialogs();
                showTab(true);
                if (homeView != null) {
                    homeView.requestFocus();
                }
            }
            return true;
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_VOICE_ASSIST
                || keyCode == android.view.KeyEvent.KEYCODE_SEARCH
                || keyCode == android.view.KeyEvent.KEYCODE_UNKNOWN
                || keyCode == android.view.KeyEvent.KEYCODE_ASSIST) {
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                startVoiceSearch();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_HOME) {
            dismissAllOpenDialogs();
            showTab(true);
            if (homeView != null) {
                homeView.requestFocus();
            }
            return true;
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_VOICE_ASSIST
                || keyCode == android.view.KeyEvent.KEYCODE_SEARCH
                || keyCode == android.view.KeyEvent.KEYCODE_UNKNOWN
                || keyCode == android.view.KeyEvent.KEYCODE_ASSIST) {
            startVoiceSearch();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void startVoiceSearch() {
        try {
            Intent katniss = new Intent(Intent.ACTION_ASSIST);
            katniss.setComponent(new android.content.ComponentName(
                    "com.google.android.katniss", "com.google.android.katniss.search.SearchActivity"));
            katniss.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "vi-VN");
            katniss.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN");
            katniss.putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", new String[] { "vi-VN", "en-US" });
            katniss.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP 
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(katniss);
        } catch (Exception e) {
            try {
                Intent voice = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                voice.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                voice.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "vi-VN");
                voice.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN");
                voice.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Nói để tìm kiếm...");
                startActivity(voice);
            } catch (Exception e2) {
                showSearchDialog();
            }
        }
    }

    private void showSearchDialog() {
        final EditText input = new EditText(this);
        input.setHint("Tìm kênh, ứng dụng, video YouTube...");
        input.setHintTextColor(Color.parseColor("#80FFFFFF"));
        input.setTextColor(Color.WHITE);
        input.setTextSize(16);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setBackground(rounded(Color.parseColor("#33FFFFFF"), 14, Color.parseColor("#3D7BFF")));
        input.setPadding(dp(18), dp(14), dp(18), dp(14));

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(24), dp(16), dp(24), dp(12));
        container.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("Tìm kiếm")
                .setView(container)
                .setPositiveButton("Tìm kiếm", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        performSearch(input.getText().toString());
                    }
                })
                .setNegativeButton("Hủy", null)
                .create();

        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                                && event.getAction() == KeyEvent.ACTION_DOWN)) {
                    performSearch(v.getText().toString());
                    dialog.dismiss();
                    return true;
                }
                return false;
            }
        });

        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        showTracked(dialog);

        input.post(new Runnable() {
            @Override
            public void run() {
                input.requestFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
                }
            }
        });
    }

    private static final Pattern DIACRITICS = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");

    public static String normalize(String s) {
        if (s == null) return "";
        String temp = Normalizer.normalize(s, Normalizer.Form.NFD);
        String result = DIACRITICS.matcher(temp).replaceAll("");
        result = result.replace('đ', 'd').replace('Đ', 'D');
        return result.toLowerCase(Locale.ROOT).trim();
    }

    public static boolean handleVoiceQuery(Context context, String rawQuery) {
        if (rawQuery == null || rawQuery.trim().isEmpty()) return false;
        HomeActivity inst = sInstance;
        if (inst != null) {
            return inst.tryLaunchAppFromVoice(rawQuery);
        }
        return fallbackLaunchAppFromVoice(context, rawQuery);
    }

    public boolean tryLaunchAppFromVoice(String rawQuery) {
        if (rawQuery == null || rawQuery.trim().isEmpty()) return false;
        String q = normalize(rawQuery);
        String cleanQ = q.replaceAll("^(mo|bat|chay|open|start|vao)\\s+", "").trim();
        if (cleanQ.isEmpty()) cleanQ = q;

        // 1. Kiểm tra bí danh / alias phát âm tiếng Việt thông dụng
        String aliasPkg = resolveAliasToPackage(cleanQ);
        if (aliasPkg != null) {
            toast("Đang mở ứng dụng");
            launchPackage(aliasPkg);
            return true;
        }

        // 2. So khớp với danh sách ứng dụng đã cài đặt trên TV
        PackageManager pm = getPackageManager();
        if (allApps != null && !allApps.isEmpty()) {
            for (ResolveInfo info : allApps) {
                if (info == null || info.activityInfo == null) continue;
                String rawLabel = getAppLabel(info, pm);
                String label = normalize(rawLabel);
                String pkg = info.activityInfo.packageName.toLowerCase(Locale.ROOT);

                boolean match = (!label.isEmpty() && (label.equals(cleanQ) 
                                || (cleanQ.length() >= 2 && (label.contains(cleanQ) || cleanQ.contains(label)))))
                                || (cleanQ.length() >= 3 && pkg.contains(cleanQ));

                if (match) {
                    toast("Đang mở " + rawLabel);
                    launchPackage(info.activityInfo.packageName);
                    return true;
                }
            }
        }
        return false;
    }

    private String resolveAliasToPackage(String q) {
        if (q == null) return null;
        if (q.contains("youtube") || q.contains("du tup") || q.contains("du tub") 
                || q.contains("iutub") || q.contains("u tub") || q.contains("you tube")) {
            return YOUTUBE_PKG;
        }
        if (q.contains("cai dat") || q.contains("thiet lap") || q.contains("setting")) {
            return "com.xiaomi.mitv.settings";
        }
        if (q.contains("vtv") || q.contains("ve te ve")) {
            return findPackageByKeyword("vtv");
        }
        if (q.contains("fpt") || q.contains("ep pe te")) {
            return findPackageByKeyword("fpt");
        }
        if (q.contains("tv360") || q.contains("tv 360") || q.contains("viettel")) {
            return findPackageByKeyword("tv360");
        }
        if (q.contains("vieon") || q.contains("vi e on")) {
            return findPackageByKeyword("vieon");
        }
        if (q.contains("iqiyi") || q.contains("ai ci di") || q.contains("ai ki di")) {
            return findPackageByKeyword("iqiyi");
        }
        if (q.contains("file") || q.contains("tap tin") || q.contains("anexplorer") || q.contains("quan ly")) {
            return findPackageByKeyword("anexplorer");
        }
        if (q.contains("dl store") || q.contains("dlstore") || q.contains("cua hang") || q.contains("store")) {
            String dl = findPackageByKeyword("dlstore");
            if (dl != null) return dl;
            return findPackageByKeyword("store");
        }
        return null;
    }

    private String findPackageByKeyword(String keyword) {
        if (allApps != null) {
            for (ResolveInfo ri : allApps) {
                if (ri != null && ri.activityInfo != null && ri.activityInfo.packageName != null) {
                    if (ri.activityInfo.packageName.toLowerCase(Locale.ROOT).contains(keyword)) {
                        return ri.activityInfo.packageName;
                    }
                }
            }
        }
        return null;
    }

    private static boolean fallbackLaunchAppFromVoice(Context context, String rawQuery) {
        if (context == null || rawQuery == null) return false;
        try {
            String q = normalize(rawQuery);
            String cleanQ = q.replaceAll("^(mo|bat|chay|open|start|vao)\\s+", "").trim();
            if (cleanQ.isEmpty()) cleanQ = q;

            PackageManager pm = context.getPackageManager();
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory("android.intent.category.LEANBACK_LAUNCHER");
            List<ResolveInfo> list = pm.queryIntentActivities(intent, 0);
            if (list == null) list = new ArrayList<>();
            Intent normal = new Intent(Intent.ACTION_MAIN);
            normal.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> normList = pm.queryIntentActivities(normal, 0);
            if (normList != null) list.addAll(normList);

            for (ResolveInfo ri : list) {
                if (ri == null || ri.activityInfo == null) continue;
                CharSequence cs = ri.loadLabel(pm);
                String label = normalize(cs != null ? cs.toString() : "");
                String pkg = ri.activityInfo.packageName.toLowerCase(Locale.ROOT);
                if (label.contains(cleanQ) || cleanQ.contains(label) || pkg.contains(cleanQ)) {
                    Intent launch = pm.getLeanbackLaunchIntentForPackage(ri.activityInfo.packageName);
                    if (launch == null) launch = pm.getLaunchIntentForPackage(ri.activityInfo.packageName);
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(launch);
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void performSearch(String rawQuery) {
        if (rawQuery == null || rawQuery.trim().isEmpty()) return;

        // 1. Thử mở ứng dụng nếu khớp
        if (tryLaunchAppFromVoice(rawQuery)) {
            return;
        }

        // 2. Không thấy app trên TV -> Đẩy sang YouTube
        try {
            Intent yt = new Intent(Intent.ACTION_VIEW);
            yt.setData(Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(rawQuery)));
            yt.setPackage(YOUTUBE_PKG);
            yt.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(yt);
            return;
        } catch (Exception ignored) {
        }

        // 3. Fallback trình duyệt
        try {
            Intent web = new Intent(Intent.ACTION_VIEW);
            web.setData(Uri.parse("https://www.google.com/search?q=" + Uri.encode(rawQuery)));
            web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(web);
        } catch (Exception e) {
            toast("Không tìm thấy kết quả");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(clockTick);
        handler.removeCallbacks(sysInfoTick);
    }

    private SharedPreferences getSafePrefs() {
        Context ctx = this;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            android.os.UserManager um = (android.os.UserManager) getSystemService(Context.USER_SERVICE);
            if (um != null && !um.isUserUnlocked()) {
                ctx = createDeviceProtectedStorageContext();
            }
        }
        return ctx.getSharedPreferences("launcher", MODE_PRIVATE);
    }

    // ----- Dựng giao diện -----

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        String wallpaper = getSafePrefs().getString("wallpaper", "wallpaper_balloons");
        View bgView = null;
        if (wallpaper != null && (wallpaper.startsWith("/") || wallpaper.startsWith("file:"))) {
            String path = wallpaper.startsWith("file:") ? wallpaper.substring(5) : wallpaper;
            File f = new File(path);
            if (f.exists() && f.canRead()) {
                Bitmap bmp = loadWallpaperBitmap(path);
                if (bmp != null) {
                    ImageView wall = new ImageView(this);
                    wall.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    wall.setImageBitmap(bmp);
                    bgView = wall;
                }
            } else {
                getSafePrefs().edit().putString("wallpaper", "wallpaper_balloons").apply();
                wallpaper = "wallpaper_balloons";
            }
        }
        if (bgView == null) {
            int wallRes = getResources().getIdentifier(wallpaper, "drawable", getPackageName());
            if (wallRes != 0) {
                ImageView wall = new ImageView(this);
                wall.setScaleType(ImageView.ScaleType.CENTER_CROP);
                wall.setImageResource(wallRes);
                bgView = wall;
            } else {
                int defRes = getResources().getIdentifier("wallpaper_balloons", "drawable", getPackageName());
                if (defRes != 0) {
                    ImageView wall = new ImageView(this);
                    wall.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    wall.setImageResource(defRes);
                    bgView = wall;
                } else {
                    bgView = new View(this);
                    bgView.setBackgroundColor(Color.parseColor("#0B132B"));
                }
            }
        }
        root.addView(bgView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setPadding(dp(36), dp(16), dp(36), dp(10));
        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        content.addView(buildTopBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        body = new FrameLayout(this);
        content.addView(body, withTop(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f), 14));

        homeView = buildHomeBody();
        appsView = buildAppsBody();
        body.addView(homeView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        body.addView(appsView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        return root;
    }

    /** A. TopStatusBarComponent: Tabs Home/Apps & Voice search (trái), Đồng hồ (chính giữa), Cụm công cụ (phải). */
    private View buildTopBar() {
        FrameLayout bar = new FrameLayout(this);
        bar.setClipChildren(false);
        bar.setClipToPadding(false);

        // 1. Cụm bên trái: Tabs Home/Apps + Nút Voice Search
        LinearLayout leftCluster = new LinearLayout(this);
        leftCluster.setOrientation(LinearLayout.HORIZONTAL);
        leftCluster.setGravity(Gravity.CENTER_VERTICAL);
        leftCluster.setClipChildren(false);
        leftCluster.setClipToPadding(false);

        tabApps = tabIcon("ic_apps", false);
        tabApps.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTab(homeView != null && homeView.getVisibility() != View.VISIBLE);
            }
        });
        leftCluster.addView(tabApps, sized(44, 44));

        LinearLayout voiceBtn = new LinearLayout(this);
        voiceBtn.setGravity(Gravity.CENTER);
        voiceBtn.setFocusable(true);
        voiceBtn.setClickable(true);
        voiceBtn.setClipChildren(false);
        voiceBtn.setClipToPadding(false);
        voiceBtn.setBackground(circle(Color.parseColor("#33FFFFFF"), 0));

        ImageView micIcon = new ImageView(this);
        micIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int micRes = getResources().getIdentifier("ic_assistant_mic", "drawable", getPackageName());
        if (micRes != 0) {
            micIcon.setImageResource(micRes);
        }
        micIcon.setAlpha(0.75f);
        voiceBtn.addView(micIcon, sized(24, 24));

        voiceBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startVoiceSearch();
            }
        });
        attachFocusScale(voiceBtn, 0, Color.WHITE);
        LinearLayout.LayoutParams vbp = sized(44, 44);
        vbp.leftMargin = dp(14);
        leftCluster.addView(voiceBtn, vbp);

        FrameLayout.LayoutParams lpLeft = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpLeft.gravity = Gravity.START | Gravity.TOP;
        bar.addView(leftCluster, lpLeft);

        // 2. Chính giữa màn hình: Đồng hồ số HH:mm và ngày EEEE, dd/MM thẳng hàng, cùng style
        LinearLayout centerClock = new LinearLayout(this);
        centerClock.setOrientation(LinearLayout.HORIZONTAL);
        centerClock.setGravity(Gravity.CENTER_VERTICAL);
        centerClock.setClipChildren(false);
        centerClock.setClipToPadding(false);
        centerClock.setFocusable(false);
        centerClock.setClickable(false);

        clockTime = text("", 20, Color.WHITE, true);
        clockDate = text("", 20, Color.WHITE, true);
        centerClock.addView(clockTime);

        TextView dot = text("  ·  ", 18, Color.parseColor("#E0FFFFFF"), true);
        centerClock.addView(dot);

        centerClock.addView(clockDate);

        FrameLayout.LayoutParams lpCenter = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpCenter.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
        lpCenter.topMargin = dp(9);
        bar.addView(centerClock, lpCenter);

        // 3. Cụm bên phải: Cụm trạng thái (Icon công cụ + Badge RAM/CPU bên dưới icon)
        LinearLayout rightCluster = new LinearLayout(this);
        rightCluster.setOrientation(LinearLayout.VERTICAL);
        rightCluster.setGravity(Gravity.END);
        rightCluster.setClipChildren(false);
        rightCluster.setClipToPadding(false);

        // Hàng icon công cụ: Wi-Fi, Bluetooth, Cài đặt (nguồn vào đã chuyển vào Cài đặt)
        LinearLayout iconsRow = new LinearLayout(this);
        iconsRow.setOrientation(LinearLayout.HORIZONTAL);
        iconsRow.setGravity(Gravity.CENTER_VERTICAL);
        iconsRow.setClipChildren(false);
        iconsRow.setClipToPadding(false);

        String[] icons = { "ic_wifi", "ic_bluetooth", "ic_gear" };
        for (int i = 0; i < icons.length; i++) {
            final int index = i;
            LinearLayout btn = new LinearLayout(this);
            btn.setGravity(Gravity.CENTER);
            btn.setClipChildren(false);
            btn.setClipToPadding(false);
            btn.setBackground(circle(Color.parseColor("#33FFFFFF"), 0));
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int res = getDrawableResId(icons[i]);
            if (res != 0) {
                iv.setImageResource(res);
            }
            if ("ic_wifi".equals(icons[i])) {
                wifiIconView = iv;
                iv.setAlpha(isWifiConnected() ? 1.0f : 0.5f);
            }
            btn.addView(iv, sized(20, 20));
            btn.setFocusable(true);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onTopIcon(index);
                }
            });
            attachFocusScale(btn, 0, Color.WHITE);
            LinearLayout.LayoutParams ip = sized(40, 40);
            if (i > 0) {
                ip.leftMargin = dp(8);
            }
            iconsRow.addView(btn, ip);
        }

        LinearLayout.LayoutParams trp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        trp.topMargin = dp(2);
        rightCluster.addView(iconsRow, trp);

        FrameLayout.LayoutParams lpRight = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpRight.gravity = Gravity.END | Gravity.TOP;
        bar.addView(rightCluster, lpRight);

        return bar;
    }

    private boolean isWifiConnected() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                NetworkInfo ni = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                return ni != null && ni.isConnected();
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** B. DashboardWidgetSection: Chia 2 Card cân đối ở giữa (WeatherCalendarCard và MarketFinanceCard). */
    private View buildHomeBody() {
        LinearLayout home = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL);
        home.setClipChildren(false);
        home.setClipToPadding(false);

        // Hàng Dashboard cân đối 2 card
        dashboardView = new LinearLayout(this);
        dashboardView.setOrientation(LinearLayout.HORIZONTAL);
        dashboardView.setClipChildren(false);
        dashboardView.setClipToPadding(false);

        // Thẻ 1: WeatherCalendarCard (Thời tiết + Lịch âm/dương)
        weatherCardView = buildWeatherCalendarCard();
        LinearLayout.LayoutParams weatherParams = new LinearLayout.LayoutParams(0, dp(165), 1f);
        dashboardView.addView(weatherCardView, weatherParams);

        // Thẻ 2: MarketFinanceCard (Hợp nhất Xăng Dầu & Giá Vàng 2 cột)
        marketCardView = buildMarketFinanceCard();
        LinearLayout.LayoutParams marketParams = withLeft(new LinearLayout.LayoutParams(0, dp(165), 1f), 16);
        dashboardView.addView(marketCardView, marketParams);

        home.addView(dashboardView);

        // Cập nhật ẩn/hiện widget theo cài đặt
        updateDashboardVisibility();

        // Spacer đẩy App Dock xuống sát phía dưới màn hình
        home.addView(new View(this), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // C. BottomAppDockSection
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setBackground(rounded(Color.TRANSPARENT, 22, 0));
        scroll.setPadding(dp(4), dp(8), dp(4), dp(4));
        homeAppRow = new LinearLayout(this);
        homeAppRow.setOrientation(LinearLayout.HORIZONTAL);
        homeAppRow.setClipChildren(false);
        homeAppRow.setClipToPadding(false);
        populateHomeApps();
        scroll.addView(homeAppRow);
        home.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // D. Cụm CPU · RAM · ROM góc dưới cùng bên phải (text trần, chữ nhỏ, màu đỏ nhạt)
        sysInfoText = text("CPU: --% · RAM: --", 9, Color.parseColor("#FF8A80"), false);
        sysInfoText.setShadowLayer(dp(2), 0, dp(1), Color.parseColor("#66000000"));
        sysInfoText.setFocusable(false);
        sysInfoText.setClickable(false);
        LinearLayout.LayoutParams sip = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sip.gravity = Gravity.END;
        sip.topMargin = dp(2);
        sip.rightMargin = dp(4);
        home.addView(sysInfoText, sip);

        return home;
    }

    /** Thẻ WeatherCalendarCard: Gom nhiệt độ, icon thời tiết, trạng thái, dự báo 4 ngày, ngày dương & âm lịch. */
    private View buildWeatherCalendarCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(CARD_COLOR, 18, Color.parseColor("#1AFFFFFF")));
        card.setPadding(dp(20), dp(12), dp(20), dp(8));
        card.setFocusable(false);
        card.setFocusableInTouchMode(false);
        card.setClickable(false);
        card.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

        // Hàng trên: Thời tiết chính (Icon + Nhiệt độ) bên trái, (Tình trạng + Địa điểm) bên phải
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        weatherIcon = text("⛅", 28, Color.WHITE, false);
        topRow.addView(weatherIcon);

        weatherTemp = text("--°C", 34, Color.WHITE, true);
        weatherTemp.setPadding(dp(8), 0, 0, 0);
        topRow.addView(weatherTemp);

        LinearLayout descBlock = new LinearLayout(this);
        descBlock.setOrientation(LinearLayout.VERTICAL);
        descBlock.setGravity(Gravity.CENTER_VERTICAL);

        weatherCond = text("Đang tải thời tiết...", 13, Color.WHITE, true);
        weatherCond.setSingleLine(true);
        weatherCond.setEllipsize(TextUtils.TruncateAt.END);

        weatherCity = text("", 12, Color.parseColor("#E2E8F0"), false);
        weatherCity.setSingleLine(true);
        weatherCity.setEllipsize(TextUtils.TruncateAt.END);

        descBlock.addView(weatherCond);
        LinearLayout.LayoutParams cityLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cityLp.topMargin = dp(3);
        descBlock.addView(weatherCity, cityLp);

        LinearLayout.LayoutParams dbp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        dbp.leftMargin = dp(16);
        topRow.addView(descBlock, dbp);

        card.addView(topRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        card.addView(new View(this), new LinearLayout.LayoutParams(1, dp(4)));

        // Hàng giữa: Bên trái là Dự báo 4 ngày, Bên phải là Lịch âm & Sự kiện
        LinearLayout middleRow = new LinearLayout(this);
        middleRow.setOrientation(LinearLayout.HORIZONTAL);
        middleRow.setGravity(Gravity.CENTER_VERTICAL);

        weatherDays = new LinearLayout(this);
        weatherDays.setOrientation(LinearLayout.HORIZONTAL);
        weatherDays.setGravity(Gravity.CENTER_VERTICAL);
        middleRow.addView(weatherDays, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Vách ngăn mờ giữa thời tiết và khối lịch âm / sự kiện
        View vDivider = new View(this);
        vDivider.setBackgroundColor(Color.parseColor("#26FFFFFF"));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(dp(1), dp(52));
        divLp.leftMargin = dp(12);
        divLp.rightMargin = dp(14);
        divLp.gravity = Gravity.CENTER_VERTICAL;
        middleRow.addView(vDivider, divLp);

        // Khối Âm lịch & Sự kiện bên phải (ô trống)
        LinearLayout calendarBlock = new LinearLayout(this);
        calendarBlock.setOrientation(LinearLayout.VERTICAL);
        calendarBlock.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout lunarRow = new LinearLayout(this);
        lunarRow.setOrientation(LinearLayout.HORIZONTAL);
        lunarRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView moonIcon = text("🌙", 15, Color.WHITE, false);
        moonIcon.setPadding(0, 0, dp(5), 0);
        cardLunar = text("", 15, Color.WHITE, true);
        lunarRow.addView(moonIcon);
        lunarRow.addView(cardLunar);
        calendarBlock.addView(lunarRow);

        cardEvent = text("", 13, Color.parseColor("#FDE047"), false);
        cardEvent.setSingleLine(false);
        cardEvent.setMaxLines(2);
        cardEvent.setEllipsize(TextUtils.TruncateAt.END);
        calendarBlock.addView(cardEvent, withTop(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), 5));

        middleRow.addView(calendarBlock, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        card.addView(middleRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

        // Footer ghi nguồn và trạng thái cập nhật (cân bằng hoàn hảo với thẻ thị trường bên phải)
        weatherFooter = text("Cập nhật hôm nay · Nguồn: Open-Meteo", 9, Color.parseColor("#94A3B8"), false);
        weatherFooter.setTypeface(null, Typeface.ITALIC);
        card.addView(weatherFooter, withTop(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), 2));

        return card;
    }

    /** Thẻ MarketFinanceCard: Hợp nhất Xăng Dầu & Giá Vàng 2 cột đều nhau với font monospace. */
    private View buildMarketFinanceCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(CARD_COLOR, 18, Color.parseColor("#1AFFFFFF")));
        card.setPadding(dp(20), dp(12), dp(20), dp(8));
        card.setFocusable(false);
        card.setFocusableInTouchMode(false);
        card.setClickable(false);
        card.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

        // 2 Cột dữ liệu
        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);

        // Cột 1: GIÁ XĂNG DẦU
        LinearLayout fuelCol = new LinearLayout(this);
        fuelCol.setOrientation(LinearLayout.VERTICAL);
        TextView fuelBadge = text("GIÁ XĂNG DẦU", 11, Color.WHITE, true);
        fuelBadge.setBackground(rounded(Color.parseColor("#E0408F"), 4, 0));
        fuelBadge.setPadding(dp(8), dp(2), dp(8), dp(2));
        fuelCol.addView(fuelBadge);

        fuelE5 = text("--", 14, Color.WHITE, true);
        fuelE5.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        fuelE10 = text("--", 14, Color.WHITE, true);
        fuelE10.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        fuelDiesel = text("--", 14, Color.WHITE, true);
        fuelDiesel.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);

        fuelCol.addView(financeRow("E5 RON 92", fuelE5, " đ/l"), withTop(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 9));
        fuelCol.addView(financeRow("E10 RON 95-III", fuelE10, " đ/l"), withTop(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 7));
        fuelCol.addView(financeRow("Diesel 0.05S", fuelDiesel, " đ/l"), withTop(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 7));
        cols.addView(fuelCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f));

        // Cột 2: GIÁ VÀNG
        LinearLayout goldCol = new LinearLayout(this);
        goldCol.setOrientation(LinearLayout.VERTICAL);
        TextView goldBadge = text("GIÁ VÀNG", 11, Color.WHITE, true);
        goldBadge.setBackground(rounded(Color.parseColor("#D9A441"), 4, 0));
        goldBadge.setPadding(dp(8), dp(2), dp(8), dp(2));
        goldCol.addView(goldBadge);

        goldSjc = text("--", 14, Color.WHITE, true);
        goldSjc.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        goldNhan = text("--", 14, Color.WHITE, true);
        goldNhan.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        goldWorld = text("--", 14, Color.WHITE, true);
        goldWorld.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);

        goldCol.addView(financeRow("Vàng miếng SJC", goldSjc, " tr/l"), withTop(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 9));
        goldCol.addView(financeRow("Vàng Nhẫn", goldNhan, " tr/l"), withTop(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 7));
        goldCol.addView(financeRow("Vàng thế giới", goldWorld, " USD/oz"), withTop(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 7));
        cols.addView(goldCol, withLeft(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f), 18));

        card.addView(cols, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Footer ghi nguồn và trạng thái cập nhật
        marketFooter = text("Cập nhật hôm nay · Nguồn: VietNamNet", 9, Color.parseColor("#94A3B8"), false);
        marketFooter.setTypeface(null, Typeface.ITALIC);
        card.addView(marketFooter, withTop(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), 2));

        return card;
    }

    private LinearLayout financeRow(String label, TextView value, String unitText) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(1), 0, dp(1));
        TextView name = text(label, 12, Color.parseColor("#D0FFFFFF"), false);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(value);
        TextView unit = text(unitText, 10, Color.parseColor("#CBD5E1"), false);
        row.addView(unit);
        return row;
    }

    private View buildAppsBody() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setClipChildren(false);
        container.setClipToPadding(false);

        // Thanh tiêu đề của tab Tất cả ứng dụng
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setClipChildren(false);
        header.setClipToPadding(false);
        header.setPadding(dp(4), 0, dp(4), dp(12));

        TextView title = text("TẤT CẢ ỨNG DỤNG", 16, Color.WHITE, true);
        header.addView(title);

        appsCountText = text(" (" + apps.size() + ")", 14, Color.parseColor("#90FFFFFF"), false);
        header.addView(appsCountText);

        TextView hint = text(" · Giữ OK hoặc phím Menu để mở tuỳ chọn", 13, Color.parseColor("#70FFFFFF"), false);
        header.addView(hint);

        header.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        // Nút Gỡ bỏ ứng dụng
        LinearLayout btnUninstall = new LinearLayout(this);
        btnUninstall.setOrientation(LinearLayout.HORIZONTAL);
        btnUninstall.setGravity(Gravity.CENTER_VERTICAL);
        btnUninstall.setFocusable(true);
        btnUninstall.setClickable(true);
        btnUninstall.setBackground(rounded(Color.parseColor("#33FFFFFF"), 14, 0));
        btnUninstall.setPadding(dp(14), dp(8), dp(14), dp(8));

        ImageView trashIcon = new ImageView(this);
        trashIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int trashRes = getResources().getIdentifier("ic_trash", "drawable", getPackageName());
        if (trashRes != 0) {
            trashIcon.setImageResource(trashRes);
        }
        btnUninstall.addView(trashIcon, sized(18, 18));

        TextView btnText = text("Gỡ bỏ ứng dụng", 13, Color.WHITE, true);
        btnText.setPadding(dp(8), 0, 0, 0);
        btnUninstall.addView(btnText);

        btnUninstall.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showUninstallListDialog();
            }
        });
        attachFocusScale(btnUninstall);
        header.addView(btnUninstall);

        container.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(0, 0, 0, dp(32));
        appsGrid = new LinearLayout(this);
        appsGrid.setClipChildren(false);
        appsGrid.setClipToPadding(false);
        appsGrid.setOrientation(LinearLayout.VERTICAL);
        populateAllApps();
        scroll.addView(appsGrid);
        container.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return container;
    }

    private void populateAllApps() {
        if (appsGrid == null)
            return;
        appsGrid.removeAllViews();
        int perRow = 5;
        for (int start = 0; start < apps.size(); start += perRow) {
            LinearLayout row = new LinearLayout(this);
            row.setClipChildren(false);
            row.setClipToPadding(false);
            row.setOrientation(LinearLayout.HORIZONTAL);
            int end = Math.min(start + perRow, apps.size());
            for (int i = start; i < end; i++) {
                int mr = (i - start == perRow - 1) ? 0 : 16;
                row.addView(appCircle(apps.get(i)), withRight(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), mr));
            }
            appsGrid.addView(row, withTop(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), 14));
        }
    }

    private void populateHomeApps() {
        if (homeAppRow == null)
            return;
        homeAppRow.removeAllViews();
        for (int i = 0; i < apps.size(); i++) {
            homeAppRow.addView(appCircle(apps.get(i)), withRight(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), 14));
        }
        homeAppRow.addView(addAppCircle(), withRight(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), 14));
    }

    private void refreshApps() {
        appsDirty = false;
        View currentFocus = getCurrentFocus();
        Object focusedTag = currentFocus != null ? currentFocus.getTag() : null;

        apps = loadLeanbackApps();
        if (appsCountText != null) {
            appsCountText.setText(" (" + apps.size() + ")");
        }
        populateHomeApps();
        populateAllApps();

        // Khôi phục focus cho ô ứng dụng trước đó nếu vừa làm mới danh sách
        if (focusedTag instanceof String) {
            View targetParent = (homeView != null && homeView.getVisibility() == View.VISIBLE) ? homeAppRow : appsGrid;
            if (targetParent != null) {
                View toFocus = targetParent.findViewWithTag(focusedTag);
                if (toFocus != null) {
                    toFocus.requestFocus();
                }
            }
        }
    }

    private String getAppLabel(ResolveInfo info, PackageManager pm) {
        if (info == null || info.activityInfo == null) return "";
        String pkg = info.activityInfo.packageName;
        if ("com.xiaomi.mitv.settings".equals(pkg)) {
            return "Cài đặt gốc TV";
        }
        String cached = appLabelCache.get(pkg);
        if (cached != null) {
            return cached;
        }
        CharSequence cs = info.loadLabel(pm);
        String label = cs != null ? cs.toString() : pkg;
        appLabelCache.put(pkg, label);
        return label;
    }

    private int getDrawableResId(String name) {
        if (name == null || name.isEmpty()) return 0;
        Integer cached = drawableResCache.get(name);
        if (cached != null) {
            return cached;
        }
        int resId = getResources().getIdentifier(name, "drawable", getPackageName());
        drawableResCache.put(name, resId);
        return resId;
    }

    // ----- Thành phần nhỏ -----

    /**
     * Ô ứng dụng dạng box tỷ lệ 16:9 bo góc 12dp:
     * - Banner nếu có (hoặc banner custom cho YouTube)
     * - Icon 42dp + tên app ngắn gọn phía dưới nếu không có banner
     * - Focus: Phóng to nhẹ 1.08x và viền sáng trắng 2dp
     */
    private LinearLayout appCircle(final ResolveInfo info) {
        final String pkg = info.activityInfo.packageName;
        PackageManager pm = getPackageManager();
        LinearLayout tile = new LinearLayout(this);
        tile.setTag(pkg);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setLongClickable(true);
        tile.setPadding(dp(3), dp(3), dp(3), dp(3));
        tile.setBackground(rounded(Color.TRANSPARENT, 14, 0));

        FrameLayout box = new FrameLayout(this);
        box.setBackground(rounded(Color.parseColor("#22FFFFFF"), 12, 0));
        box.setClipToOutline(true);

        Drawable bannerDrawable = null;
        if ("com.google.android.youtube.tv".equals(pkg)) {
            int ytRes = getDrawableResId("banner_youtube");
            if (ytRes != 0) {
                bannerDrawable = getResources().getDrawable(ytRes);
            }
        } else if ("com.xiaomi.mitv.settings".equals(pkg)) {
            int setRes = getDrawableResId("banner_settings");
            if (setRes != 0) {
                bannerDrawable = getResources().getDrawable(setRes);
            }
        }
        if (bannerDrawable == null) {
            bannerDrawable = info.activityInfo.loadBanner(pm);
        }

        if (bannerDrawable != null) {
            ImageView banner = new ImageView(this);
            banner.setScaleType(ImageView.ScaleType.CENTER_CROP);
            banner.setImageDrawable(bannerDrawable);
            box.addView(banner, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            LinearLayout iconLayout = new LinearLayout(this);
            iconLayout.setOrientation(LinearLayout.VERTICAL);
            iconLayout.setGravity(Gravity.CENTER);
            iconLayout.setPadding(dp(4), dp(3), dp(4), dp(3));

            ImageView icon = new ImageView(this);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setImageDrawable(info.loadIcon(pm));
            iconLayout.addView(icon, sized(29, 29));

            TextView label = text(getAppLabel(info, pm), 8, Color.WHITE, true);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(3);
            iconLayout.addView(label, lp);

            box.addView(iconLayout, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        tile.addView(box, sized(TILE_W, TILE_H));

        tile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchPackage(pkg);
            }
        });

        tile.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showAppOptions(info);
                return true;
            }
        });

        tile.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                // Che do sap xep: trai/phai de di chuyen, OK/Back de xac nhan
                if (movingPkg != null && movingPkg.equals(pkg)) {
                    boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
                    switch (keyCode) {
                        case KeyEvent.KEYCODE_DPAD_LEFT:
                            if (down) moveApp(pkg, -1);
                            return true;
                        case KeyEvent.KEYCODE_DPAD_RIGHT:
                            if (down) moveApp(pkg, 1);
                            return true;
                        case KeyEvent.KEYCODE_DPAD_UP:
                        case KeyEvent.KEYCODE_DPAD_DOWN:
                            return true;
                        case KeyEvent.KEYCODE_DPAD_CENTER:
                        case KeyEvent.KEYCODE_ENTER:
                        case KeyEvent.KEYCODE_NUMPAD_ENTER:
                        case KeyEvent.KEYCODE_BACK:
                            if (down && event.getRepeatCount() == 0) finishMoveMode();
                            return true;
                        default:
                            break;
                    }
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO) {
                        showAppOptions(info);
                        return true;
                    }
                }
                return false;
            }
        });

        if (pkg.equals(movingPkg)) {
            int amber = Color.parseColor("#FFC107");
            tile.setBackground(rounded(Color.parseColor("#55FFC107"), 14, amber));
            attachFocusScale(tile, amber, amber);
        } else {
            attachFocusScale(tile, 0, Color.WHITE);
        }
        return tile;
    }

    /** Menu thao tác với app: Mở, Sắp xếp vị trí, Ẩn icon, Gỡ ứng dụng. */
    private void showAppOptions(final ResolveInfo info) {
        final PackageManager pm = getPackageManager();
        final String pkg = info.activityInfo.packageName;
        final String appName = getAppLabel(info, pm);
        final boolean isSystem = (info.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        final boolean isSelf = pkg.equals(getPackageName());
        final boolean isSettings = "com.xiaomi.mitv.settings".equals(pkg);

        final List<String[]> options = new ArrayList<>();
        options.add(new String[] { "Mở ứng dụng", "ic_play" });
        options.add(new String[] { "Sắp xếp vị trí", "ic_apps" });
        options.add(new String[] { "Ẩn icon app", "ic_widget" });
        options.add(new String[] { "Gỡ ứng dụng", "ic_trash" });

        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle(appName);
        builder.setIcon(info.loadIcon(pm));

        ArrayAdapter<String[]> adapter = new ArrayAdapter<String[]>(this, 0, options) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout row;
                ImageView icon;
                TextView label;
                if (convertView instanceof LinearLayout && ((LinearLayout) convertView).getChildCount() == 2) {
                    row = (LinearLayout) convertView;
                    icon = (ImageView) row.getChildAt(0);
                    label = (TextView) row.getChildAt(1);
                } else {
                    row = new LinearLayout(getContext());
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(20), dp(14), dp(20), dp(14));
                    row.setFocusable(false);
                    row.setClickable(false);

                    icon = new ImageView(getContext());
                    icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    icon.setFocusable(false);
                    row.addView(icon, sized(28, 28));

                    label = text("", 16, Color.WHITE, false);
                    label.setPadding(dp(16), 0, 0, 0);
                    label.setFocusable(false);
                    row.addView(label, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                }

                String[] item = getItem(position);
                int resId = getDrawableResId(item[1]);
                if (resId != 0) {
                    icon.setImageResource(resId);
                } else {
                    icon.setImageDrawable(null);
                }
                label.setText(item[0]);

                return row;
            }
        };

        builder.setAdapter(adapter, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String action = options.get(which)[0];
                if ("Mở ứng dụng".equals(action)) {
                    launchPackage(pkg);
                } else if ("Sắp xếp vị trí".equals(action)) {
                    startMoveMode(pkg);
                } else if ("Ẩn icon app".equals(action)) {
                    hideApp(pkg, appName);
                } else if ("Gỡ ứng dụng".equals(action)) {
                    if (isSelf) {
                        toast("Không thể gỡ bỏ trình khởi chạy!");
                    } else if (isSystem || isSettings) {
                        toast("Đây là ứng dụng hệ thống, không thể gỡ bỏ!");
                    } else {
                        uninstallApp(pkg);
                    }
                }
            }
        });
        android.app.AlertDialog dialog = builder.create();
        showTracked(dialog);
        if (dialog.getListView() != null) {
            dialog.getListView().requestFocus();
        }
    }

    // ===== Sap xep / An icon ung dung =====

    private SharedPreferences launcherPrefs() {
        return getSharedPreferences("launcher_prefs", MODE_PRIVATE);
    }

    private Set<String> getHiddenApps() {
        Set<String> saved = launcherPrefs().getStringSet("hidden_apps", null);
        return saved == null ? new HashSet<String>() : new HashSet<String>(saved);
    }

    private List<ResolveInfo> filterVisibleApps(List<ResolveInfo> source) {
        Set<String> hidden = getHiddenApps();
        List<ResolveInfo> out = new ArrayList<>();
        for (ResolveInfo info : source) {
            if (!hidden.contains(info.activityInfo.packageName)) {
                out.add(info);
            }
        }
        return out;
    }

    /** Ap dung thu tu nguoi dung da luu (app chua co trong thu tu thi giu nguyen vi tri mac dinh o phia sau). */
    private void applySavedOrder(List<ResolveInfo> list) {
        String raw = launcherPrefs().getString("app_order", "");
        if (raw == null || raw.isEmpty()) return;
        final Map<String, Integer> idx = new HashMap<>();
        String[] parts = raw.split(",");
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].isEmpty()) idx.put(parts[i], i);
        }
        Collections.sort(list, new Comparator<ResolveInfo>() {
            @Override
            public int compare(ResolveInfo a, ResolveInfo b) {
                Integer ia = idx.get(a.activityInfo.packageName);
                Integer ib = idx.get(b.activityInfo.packageName);
                int va = ia == null ? Integer.MAX_VALUE : ia;
                int vb = ib == null ? Integer.MAX_VALUE : ib;
                return Integer.compare(va, vb);
            }
        });
    }

    private void saveAppOrder() {
        StringBuilder sb = new StringBuilder();
        for (ResolveInfo info : allApps) {
            if (sb.length() > 0) sb.append(',');
            sb.append(info.activityInfo.packageName);
        }
        launcherPrefs().edit().putString("app_order", sb.toString()).apply();
    }

    /** Dung lai giao dien danh sach app (khong quet lai he thong). */
    private void rebuildAppViews(String focusPkg) {
        if (appsCountText != null) {
            appsCountText.setText(" (" + apps.size() + ")");
        }
        populateHomeApps();
        populateAllApps();
        if (focusPkg != null) {
            View parent = (homeView != null && homeView.getVisibility() == View.VISIBLE) ? homeAppRow : appsGrid;
            if (parent != null) {
                View t = parent.findViewWithTag(focusPkg);
                if (t != null) {
                    t.requestFocus();
                }
            }
        }
    }

    private void startMoveMode(String pkg) {
        movingPkg = pkg;
        rebuildAppViews(pkg);
        toast("Bấm ← → để di chuyển, bấm OK để xác nhận");
    }

    private void finishMoveMode() {
        String p = movingPkg;
        movingPkg = null;
        rebuildAppViews(p);
        toast("Đã lưu vị trí ứng dụng");
    }

    private void cancelMoveMode() {
        if (movingPkg != null) {
            movingPkg = null;
            rebuildAppViews(null);
        }
    }

    /** Di chuyen app sang trai (-1) hoac phai (+1) trong danh sach dang hien. */
    private void moveApp(String pkg, int delta) {
        int vi = -1;
        for (int i = 0; i < apps.size(); i++) {
            if (pkg.equals(apps.get(i).activityInfo.packageName)) {
                vi = i;
                break;
            }
        }
        int vj = vi + delta;
        if (vi < 0 || vj < 0 || vj >= apps.size()) return;
        String other = apps.get(vj).activityInfo.packageName;
        int ai = -1;
        int aj = -1;
        for (int i = 0; i < allApps.size(); i++) {
            String p = allApps.get(i).activityInfo.packageName;
            if (p.equals(pkg)) ai = i;
            else if (p.equals(other)) aj = i;
        }
        if (ai < 0 || aj < 0) return;
        Collections.swap(allApps, ai, aj);
        saveAppOrder();
        apps = filterVisibleApps(allApps);
        rebuildAppViews(pkg);
    }

    /** An icon app khoi man hinh chinh (bat lai trong Cai dat). */
    private void hideApp(String pkg, String appName) {
        String neighbor = null;
        for (int i = 0; i < apps.size(); i++) {
            if (pkg.equals(apps.get(i).activityInfo.packageName)) {
                if (i + 1 < apps.size()) {
                    neighbor = apps.get(i + 1).activityInfo.packageName;
                } else if (i > 0) {
                    neighbor = apps.get(i - 1).activityInfo.packageName;
                }
                break;
            }
        }
        Set<String> hidden = getHiddenApps();
        hidden.add(pkg);
        launcherPrefs().edit().putStringSet("hidden_apps", hidden).apply();
        apps = filterVisibleApps(allApps);
        rebuildAppViews(neighbor);
        toast("Đã ẩn " + appName + ". Bật lại trong Cài đặt > Hiện / Ẩn icon ứng dụng");
    }

    /** Menu Cài đặt: tích chọn app nào hiện / ẩn trên màn hình TV. */
    private void showAppVisibilityMenu() {
        if (allApps.isEmpty()) {
            toast("Không có ứng dụng nào");
            return;
        }
        final PackageManager pm = getPackageManager();
        final List<ResolveInfo> list = new ArrayList<>(allApps);
        final Set<String> hidden = getHiddenApps();
        CharSequence[] items = new CharSequence[list.size()];
        final boolean[] checked = new boolean[list.size()];
        for (int i = 0; i < list.size(); i++) {
            ResolveInfo ri = list.get(i);
            items[i] = getAppLabel(ri, pm);
            checked[i] = !hidden.contains(ri.activityInfo.packageName);
        }
        showTracked(new android.app.AlertDialog.Builder(this)
                .setTitle("Hiện / Ẩn icon ứng dụng (có tích = hiện)")
                .setMultiChoiceItems(items, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                        checked[which] = isChecked;
                        Set<String> h = getHiddenApps();
                        String p = list.get(which).activityInfo.packageName;
                        if (isChecked) {
                            h.remove(p);
                        } else {
                            h.add(p);
                        }
                        launcherPrefs().edit().putStringSet("hidden_apps", h).apply();
                        apps = filterVisibleApps(allApps);
                        rebuildAppViews(null);
                    }
                })
                .setPositiveButton("Đóng", null)
                .create());
    }

    /** Danh sách chọn nhanh ứng dụng cần gỡ bỏ. */
    private void showUninstallListDialog() {
        final PackageManager pm = getPackageManager();
        final List<ResolveInfo> uninstallable = new ArrayList<>();
        String self = getPackageName();
        for (ResolveInfo info : apps) {
            String p = info.activityInfo.packageName;
            boolean isSystem = (info.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (!p.equals(self) && !isSystem && !"com.xiaomi.mitv.settings".equals(p)) {
                uninstallable.add(info);
            }
        }
        if (uninstallable.isEmpty()) {
            toast("Không có ứng dụng nào có thể gỡ bỏ (toàn bộ là ứng dụng hệ thống)");
            return;
        }
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
        b.setTitle("Chọn ứng dụng cần gỡ bỏ (" + uninstallable.size() + ")");
        ArrayAdapter<ResolveInfo> adapter = new ArrayAdapter<ResolveInfo>(this, 0, uninstallable) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout row;
                ImageView iv;
                TextView tvName;
                TextView tvPkg;
                if (convertView instanceof LinearLayout && ((LinearLayout) convertView).getChildCount() == 3) {
                    row = (LinearLayout) convertView;
                    iv = (ImageView) row.getChildAt(0);
                    LinearLayout textCol = (LinearLayout) row.getChildAt(1);
                    tvName = (TextView) textCol.getChildAt(0);
                    tvPkg = (TextView) textCol.getChildAt(1);
                } else {
                    row = new LinearLayout(getContext());
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(20), dp(10), dp(20), dp(10));
                    row.setFocusable(false);
                    row.setClickable(false);

                    iv = new ImageView(getContext());
                    iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    iv.setFocusable(false);
                    row.addView(iv, sized(32, 32));

                    LinearLayout textCol = new LinearLayout(getContext());
                    textCol.setOrientation(LinearLayout.VERTICAL);
                    textCol.setPadding(dp(16), 0, dp(16), 0);
                    textCol.setFocusable(false);
                    tvName = text("", 16, Color.WHITE, false);
                    tvName.setFocusable(false);
                    tvPkg = text("", 11, Color.parseColor("#80FFFFFF"), false);
                    tvPkg.setFocusable(false);
                    textCol.addView(tvName);
                    textCol.addView(tvPkg);
                    row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                    ImageView trash = new ImageView(getContext());
                    trash.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    trash.setFocusable(false);
                    int res = getDrawableResId("ic_trash");
                    if (res != 0) {
                        trash.setImageResource(res);
                    }
                    row.addView(trash, sized(22, 22));
                }

                ResolveInfo ri = getItem(position);
                iv.setImageDrawable(ri.loadIcon(pm));
                tvName.setText(getAppLabel(ri, pm));
                tvPkg.setText(ri.activityInfo.packageName);
                return row;
            }
        };
        b.setAdapter(adapter, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                ResolveInfo target = uninstallable.get(which);
                uninstallApp(target.activityInfo.packageName);
            }
        });
        android.app.AlertDialog d = b.create();
        showTracked(d);
        if (d.getListView() != null) {
            d.getListView().requestFocus();
        }
    }

    /** Yêu cầu mã PIN trước khi cho phép gỡ cài đặt ứng dụng. */
    private void uninstallApp(final String pkg) {
        if (pkg.equals(getPackageName())) {
            toast("Không thể gỡ bỏ trình khởi chạy!");
            return;
        }

        final SharedPreferences sp = getSharedPreferences("launcher_prefs", MODE_PRIVATE);
        final String savedPin = sp.getString("uninstall_pin", "0000");

        final EditText pinInput = new EditText(this);
        pinInput.setHint("Nhập mã PIN (mặc định: 0000)");
        pinInput.setHintTextColor(Color.parseColor("#80FFFFFF"));
        pinInput.setTextColor(Color.WHITE);
        pinInput.setTextSize(18);
        pinInput.setGravity(Gravity.CENTER);
        pinInput.setSingleLine(true);
        pinInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinInput.setTransformationMethod(PasswordTransformationMethod.getInstance());
        pinInput.setBackground(rounded(Color.parseColor("#33FFFFFF"), 14, Color.parseColor("#3D7BFF")));
        pinInput.setPadding(dp(18), dp(14), dp(18), dp(14));

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(24), dp(16), dp(24), dp(8));
        container.addView(pinInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("Khóa bảo vệ gỡ ứng dụng")
                .setMessage("Nhập mã PIN để tiếp tục gỡ bỏ ứng dụng:")
                .setView(container)
                .setPositiveButton("Xác nhận", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String entered = pinInput.getText().toString().trim();
                        if (savedPin.equals(entered)) {
                            executeUninstall(pkg);
                        } else {
                            toast("Sai mã PIN! Không thể gỡ ứng dụng.");
                        }
                    }
                })
                .setNegativeButton("Hủy", null)
                .create();

        showTracked(dialog);
        pinInput.post(new Runnable() {
            @Override
            public void run() {
                pinInput.requestFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(pinInput, InputMethodManager.SHOW_IMPLICIT);
                }
            }
        });
    }

    /** Gọi màn hình gỡ cài đặt chính thức của hệ thống Android sau khi đúng mã PIN. */
    private void executeUninstall(String pkg) {
        try {
            Intent intent = new Intent(Intent.ACTION_DELETE);
            intent.setData(Uri.parse("package:" + pkg));
            intent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
            startActivity(intent);
        } catch (Exception e) {
            try {
                Intent intent = new Intent(Intent.ACTION_UNINSTALL_PACKAGE);
                intent.setData(Uri.parse("package:" + pkg));
                intent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
                startActivity(intent);
            } catch (Exception e2) {
                toast("Không thể mở trình gỡ cài đặt: " + e2.getMessage());
            }
        }
    }

    /** Mở trang Chi tiết ứng dụng trong Cài đặt hệ thống. */
    private void openAppDetails(String pkg) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + pkg));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            toast("Không mở được Thông tin ứng dụng");
        }
    }

    private static final int TILE_W = 98;
    private static final int TILE_H = 55;

    private LinearLayout addAppCircle() {
        LinearLayout tile = new LinearLayout(this);
        tile.setGravity(Gravity.CENTER);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setPadding(dp(3), dp(3), dp(3), dp(3));
        TextView plus = text("+  Thêm app", 14, Color.parseColor("#D0FFFFFF"), false);
        plus.setGravity(Gravity.CENTER);
        GradientDrawable dashed = new GradientDrawable();
        dashed.setCornerRadius(dp(12));
        dashed.setColor(Color.TRANSPARENT);
        dashed.setStroke(dp(2), Color.parseColor("#66FFFFFF"));
        tile.setBackground(dashed);
        tile.addView(plus, sized(TILE_W, TILE_H));
        tile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTab(false);
            }
        });
        attachFocusScale(tile, Color.parseColor("#66FFFFFF"), Color.WHITE);
        return tile;
    }

    private LinearLayout tabIcon(String iconName, boolean selected) {
        LinearLayout pill = new LinearLayout(this);
        pill.setGravity(Gravity.CENTER);
        pill.setFocusable(true);
        pill.setClickable(true);
        pill.setClipChildren(false);
        pill.setClipToPadding(false);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int res = getResources().getIdentifier(iconName, "drawable", getPackageName());
        if (res != 0) {
            iv.setImageResource(res);
        }
        iv.setAlpha(selected ? 1.0f : 0.7f);
        pill.addView(iv, sized(24, 24));
        styleTab(pill, selected);
        attachFocusScale(pill, 0, Color.WHITE);
        return pill;
    }

    private void showTab(boolean home) {
        if (homeView != null) homeView.setVisibility(home ? View.VISIBLE : View.GONE);
        if (appsView != null) appsView.setVisibility(home ? View.GONE : View.VISIBLE);
        if (tabApps != null) styleTab(tabApps, !home);
    }

    private void styleTab(LinearLayout pill, boolean selected) {
        int stroke = pill.hasFocus() ? Color.WHITE : 0;
        pill.setBackground(
                circle(selected ? Color.parseColor("#55FFFFFF") : Color.parseColor("#33FFFFFF"), stroke));
        if (pill.getChildCount() > 0 && pill.getChildAt(0) instanceof ImageView) {
            ((ImageView) pill.getChildAt(0)).setAlpha(selected ? 1.0f : 0.7f);
        }
    }

    /**
     * Nguồn vào: {tên hiển thị, icon, action, activity của TV, input (-1 nếu không
     * có)}.
     */
    private static final Object[][] SOURCES = {
            { "Analog TV", "ic_atv", "com.xiaomi.mitv.tvplayer.ATV_PLAY", "com.xiaomi.mitv.tvplayer/.AtvActivity", -1 },
            { "HDMI 1", "ic_hdmi", "com.xiaomi.mitv.tvplayer.EXTSRC_PLAY",
                    "com.xiaomi.mitv.tvplayer/.ExternalSourceActivity",
                    23 },
            { "HDMI 2", "ic_hdmi", "com.xiaomi.mitv.tvplayer.EXTSRC_PLAY",
                    "com.xiaomi.mitv.tvplayer/.ExternalSourceActivity",
                    24 },
            { "HDMI 3", "ic_hdmi", "com.xiaomi.mitv.tvplayer.EXTSRC_PLAY",
                    "com.xiaomi.mitv.tvplayer/.ExternalSourceActivity",
                    25 },
            { "ScreenCast (Chiếu màn hình)", "ic_cast", "android.intent.action.MAIN",
                    "com.xiaomi.mitv.smartshare/.MainActivity",
                    -1 },
    };

    /** Mở nguồn vào bằng đúng intent mà các app HDMI của TV vẫn dùng. */
    private void openSource(Object[] src) {
        String action = (String) src[2];
        String component = (String) src[3];
        int input = (Integer) src[4];

        Intent intent = new Intent(action);
        if (component != null && !component.isEmpty()) {
            intent.setComponent(android.content.ComponentName.unflattenFromString(component));
        }
        if (input >= 0) {
            intent.putExtra("input", input);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            startActivity(intent);
        } catch (Exception e) {
            if (component != null && component.contains("smartshare")) {
                try {
                    Intent fb = getPackageManager().getLaunchIntentForPackage("com.xiaomi.mitv.smartshare");
                    if (fb != null) {
                        fb.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(fb);
                        return;
                    }
                } catch (Exception ignored) {}
                try {
                    Intent castSettings = new Intent("android.settings.CAST_SETTINGS");
                    castSettings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(castSettings);
                    return;
                } catch (Exception ignored) {}
            }
            toast("Không mở được " + src[0]);
        }
    }

    /** Menu chọn nguồn: mở app tương ứng của từng nguồn với icon minh họa. */
    private void showSourceMenu() {
        ListAdapter adapter = new ArrayAdapter<Object[]>(this, 0, (Object[][]) SOURCES) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout row = new LinearLayout(getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(20), dp(12), dp(20), dp(12));

                ImageView icon = new ImageView(getContext());
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                String iconName = (String) SOURCES[position][1];
                int resId = getDrawableResId(iconName);
                if (resId != 0) {
                    icon.setImageResource(resId);
                }
                row.addView(icon, sized(28, 28));

                TextView label = text((String) SOURCES[position][0], 16, Color.WHITE, false);
                label.setPadding(dp(16), 0, 0, 0);
                row.addView(label, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                return row;
            }
        };

        showTracked(new android.app.AlertDialog.Builder(this)
                .setTitle("Chọn nguồn")
                .setAdapter(adapter, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        openSource(SOURCES[which]);
                    }
                })
                .create());
    }

    private void onTopIcon(int index) {
        switch (index) {
            case 0:
                openWifiSettings();
                break;
            case 1:
                openBluetoothSettings();
                break;
            case 2:
                showSettingsMenu();
                break;
            default:
                break;
        }
    }

    private static final String[][] SETTINGS_ITEMS = {
            { "Chọn nguồn vào (AnalogTV, HDMI, Cast)", "ic_source" },
            { "Bật / Tắt Widget (Thời tiết, Giá vàng)", "ic_widget" },
            { "Vào tất cả cài đặt gốc của TV", "ic_gear" },
            { "Cố định tiếng Anh khi khởi động lại TV", "ic_language" },
            { "Bật gỡ lỗi ADB (Enable Android Debug Bridge)", "ic_adb" },
            { "Đổi mã PIN gỡ ứng dụng (mặc định: 0000)", "ic_gear" },
            { "Đổi hình nền của TV", "ic_wallpaper" },
            { "Chọn ngôn ngữ Voice Search của Google", "ic_language" },
            { "Hiện / Ẩn icon ứng dụng trên màn hình", "ic_apps" },
    };

    /** Menu Cài đặt: các tùy chọn hệ thống và đổi hình nền. */
    private void showSettingsMenu() {
        ListAdapter adapter = new ArrayAdapter<String[]>(this, 0, SETTINGS_ITEMS) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout row = new LinearLayout(getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(20), dp(12), dp(20), dp(12));

                ImageView icon = new ImageView(getContext());
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                String iconName = SETTINGS_ITEMS[position][1];
                int resId = getDrawableResId(iconName);
                if (resId != 0) {
                    icon.setImageResource(resId);
                }
                row.addView(icon, sized(28, 28));

                TextView label = text(SETTINGS_ITEMS[position][0], 16, Color.WHITE, false);
                label.setPadding(dp(16), 0, 0, 0);
                row.addView(label, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                return row;
            }
        };

        showTracked(new android.app.AlertDialog.Builder(this)
                .setTitle("Cài đặt (viết bởi fb: herotran)")
                .setAdapter(adapter, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        switch (which) {
                            case 0:
                                showSourceMenu();
                                break;
                            case 1:
                                showWidgetSettingsMenu();
                                break;
                            case 2:
                                openSystemSettings();
                                break;
                            case 3:
                                LocaleHelper.enforceEnglish(HomeActivity.this);
                                toast("Đã chuyển ngôn ngữ sang tiếng Anh!");
                                break;
                            case 4:
                                AdbHelper.ensureAdbEnabled(HomeActivity.this, true);
                                toast("Đã kích hoạt gỡ lỗi ADB!");
                                break;
                            case 5:
                                showChangePinDialog();
                                break;
                            case 6:
                                showWallpaperMenu();
                                break;
                            case 7:
                                showVoiceLanguageMenu();
                                break;
                            case 8:
                                showAppVisibilityMenu();
                                break;
                        }
                    }
                })
                .create());
    }

    /** Đổi mã PIN gỡ ứng dụng */
    private void showChangePinDialog() {
        final SharedPreferences sp = getSharedPreferences("launcher_prefs", MODE_PRIVATE);
        final String currentPin = sp.getString("uninstall_pin", "0000");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(16), dp(24), dp(8));

        final EditText oldInput = new EditText(this);
        oldInput.setHint("Nhập mã PIN hiện tại");
        oldInput.setHintTextColor(Color.parseColor("#80FFFFFF"));
        oldInput.setTextColor(Color.WHITE);
        oldInput.setTextSize(16);
        oldInput.setSingleLine(true);
        oldInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        oldInput.setTransformationMethod(PasswordTransformationMethod.getInstance());
        oldInput.setBackground(rounded(Color.parseColor("#33FFFFFF"), 14, 0));
        oldInput.setPadding(dp(16), dp(12), dp(16), dp(12));
        layout.addView(oldInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final EditText newInput = new EditText(this);
        newInput.setHint("Nhập mã PIN mới (ít nhất 4 số)");
        newInput.setHintTextColor(Color.parseColor("#80FFFFFF"));
        newInput.setTextColor(Color.WHITE);
        newInput.setTextSize(16);
        newInput.setSingleLine(true);
        newInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        newInput.setTransformationMethod(PasswordTransformationMethod.getInstance());
        newInput.setBackground(rounded(Color.parseColor("#33FFFFFF"), 14, 0));
        newInput.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = dp(12);
        layout.addView(newInput, np);

        showTracked(new android.app.AlertDialog.Builder(this)
                .setTitle("Đổi mã PIN gỡ ứng dụng")
                .setView(layout)
                .setPositiveButton("Lưu", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String oldPin = oldInput.getText().toString().trim();
                        String newPin = newInput.getText().toString().trim();
                        if (!currentPin.equals(oldPin)) {
                            toast("Mã PIN hiện tại không đúng!");
                            return;
                        }
                        if (newPin.length() < 4) {
                            toast("Mã PIN mới phải có ít nhất 4 số!");
                            return;
                        }
                        sp.edit().putString("uninstall_pin", newPin).apply();
                        toast("Đã đổi mã PIN thành công!");
                    }
                })
                .setNegativeButton("Hủy", null)
                .create());
    }

    /** Bật/tắt 2 tiện ích (Weather và Market) trên màn hình chính */
    private void showWidgetSettingsMenu() {
        final SharedPreferences sp = getSharedPreferences("launcher_prefs", MODE_PRIVATE);
        final boolean[] checked = new boolean[] {
                sp.getBoolean("show_weather_widget", true),
                sp.getBoolean("show_market_widget", true)
        };
        CharSequence[] items = new CharSequence[] {
                "Tiện ích Thời tiết & Lịch",
                "Tiện ích Xăng dầu, Giá vàng"
        };
        showTracked(new android.app.AlertDialog.Builder(this)
                .setTitle("Bật / Tắt Tiện ích")
                .setMultiChoiceItems(items, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                        checked[which] = isChecked;
                        sp.edit()
                                .putBoolean("show_weather_widget", checked[0])
                                .putBoolean("show_market_widget", checked[1])
                                .apply();
                        updateDashboardVisibility();
                    }
                })
                .setPositiveButton("Đóng", null)
                .create());
    }

    /** Cập nhật ẩn/hiện và kích thước các widget trên Dashboard */
    private void updateDashboardVisibility() {
        if (dashboardView == null || weatherCardView == null || marketCardView == null) return;
        SharedPreferences sp = getSharedPreferences("launcher_prefs", MODE_PRIVATE);
        boolean showWeather = sp.getBoolean("show_weather_widget", true);
        boolean showMarket = sp.getBoolean("show_market_widget", true);

        if (!showWeather && !showMarket) {
            dashboardView.setVisibility(View.GONE);
            weatherCardView.setVisibility(View.GONE);
            marketCardView.setVisibility(View.GONE);
            return;
        }

        dashboardView.setVisibility(View.VISIBLE);
        if (showWeather && showMarket) {
            weatherCardView.setVisibility(View.VISIBLE);
            marketCardView.setVisibility(View.VISIBLE);
            LinearLayout.LayoutParams wp = (LinearLayout.LayoutParams) weatherCardView.getLayoutParams();
            wp.width = 0;
            wp.weight = 1f;
            weatherCardView.setLayoutParams(wp);

            LinearLayout.LayoutParams mp = (LinearLayout.LayoutParams) marketCardView.getLayoutParams();
            mp.width = 0;
            mp.weight = 1f;
            mp.leftMargin = dp(16);
            marketCardView.setLayoutParams(mp);
        } else if (showWeather) {
            weatherCardView.setVisibility(View.VISIBLE);
            marketCardView.setVisibility(View.GONE);
            LinearLayout.LayoutParams wp = (LinearLayout.LayoutParams) weatherCardView.getLayoutParams();
            wp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            wp.weight = 0;
            weatherCardView.setLayoutParams(wp);
        } else {
            weatherCardView.setVisibility(View.GONE);
            marketCardView.setVisibility(View.VISIBLE);
            LinearLayout.LayoutParams mp = (LinearLayout.LayoutParams) marketCardView.getLayoutParams();
            mp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            mp.weight = 0;
            mp.leftMargin = 0;
            marketCardView.setLayoutParams(mp);
        }
    }

    /** Chọn ngôn ngữ Voice Search của Google (katniss); tự bấm trong màn hình Speech qua AccessibilityService. */
    private void showVoiceLanguageMenu() {
        final String[] labels = {
                "Tiếng Việt (Việt Nam)",
                "English (US)",
                "Mở cài đặt Speech của Google để chọn lại"
        };
        showTracked(new android.app.AlertDialog.Builder(this)
                .setTitle("Ngôn ngữ Voice Search")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 2) {
                            openKatnissSpeechSettings();
                            return;
                        }
                        VoiceAccessibilityService svc = VoiceAccessibilityService.getInstance();
                        if (svc == null) {
                            toast("Dịch vụ Voice chưa chạy, mở cài đặt để chọn tay");
                            openKatnissSpeechSettings();
                            return;
                        }
                        String target = which == 0 ? VoiceLangFixer.VI_LABEL : "English (US)";
                        if (VoiceLangFixer.applyNow(svc, target)) {
                            toast("Đang đặt ngôn ngữ giọng nói: " + target);
                        } else {
                            toast("Đang xử lý, đợi vài giây rồi thử lại");
                        }
                    }
                })
                .create());
    }

    private void openKatnissSpeechSettings() {
        try {
            Intent i = new Intent();
            i.setComponent(new android.content.ComponentName("com.google.android.katniss",
                    "com.google.android.katniss.setting.SpeechSettingsActivity"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            android.util.Log.e("HomeActivity", "open katniss speech settings failed", e);
            toast("Không mở được cài đặt Speech của Google");
        }
    }

    private void showWallpaperMenu() {
        final List<String[]> options = new ArrayList<>();
        options.add(new String[] { "Khinh khí cầu (mặc định)", "wallpaper_balloons", "ic_wallpaper" });
        options.add(new String[] { "Chọn hình khác (chờ 1 tý)", "custom", "ic_wallpaper" });

        ArrayAdapter<String[]> adapter = new ArrayAdapter<String[]>(this, 0, options) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout row = new LinearLayout(getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(20), dp(14), dp(20), dp(14));
                row.setFocusable(false);
                row.setClickable(false);

                String[] item = getItem(position);
                ImageView icon = new ImageView(getContext());
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                icon.setFocusable(false);
                int resId = getDrawableResId(item[2]);
                if (resId != 0) {
                    icon.setImageResource(resId);
                }
                row.addView(icon, sized(28, 28));

                TextView label = text(item[0], 16, Color.WHITE, false);
                label.setPadding(dp(16), 0, 0, 0);
                label.setFocusable(false);
                row.addView(label, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                return row;
            }
        };

        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("Hình nền");
        builder.setAdapter(adapter, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                android.util.Log.d("WALLPAPER", "Wallpaper menu clicked which=" + which);
                if (which == 0) {
                    getSafePrefs().edit()
                            .putString("wallpaper", "wallpaper_balloons").apply();
                    toast("Đã chuyển về hình nền mặc định: Khinh khí cầu");
                    recreate();
                } else if (which == 1) {
                    showImagePickerDialog(null);
                }
            }
        });
        android.app.AlertDialog dialog = builder.create();
        showTracked(dialog);
        if (dialog.getListView() != null) {
            dialog.getListView().requestFocus();
        }
    }

    /** Mở danh sách chọn hình ảnh từ bộ nhớ (/sdcard/Pictures). */
    private void showImagePickerDialog(File currentDir) {
        if (currentDir == null) {
            File picDir = new File("/sdcard/Pictures");
            if (!picDir.exists() || !picDir.isDirectory()) {
                picDir = new File("/storage/emulated/0/Pictures");
            }
            if (!picDir.exists() || !picDir.isDirectory()) {
                picDir = new File("/sdcard/pictures");
            }
            if (!picDir.exists() || !picDir.isDirectory()) {
                picDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
            }
            if (picDir != null && picDir.exists() && picDir.isDirectory()) {
                currentDir = picDir;
            } else {
                currentDir = Environment.getExternalStorageDirectory();
            }
        }

        final File dir = currentDir;
        File[] allFiles = dir.listFiles();
        final List<File> items = new ArrayList<>();

        final File parent = dir.getParentFile();
        final boolean canGoUp = parent != null && dir.getAbsolutePath().length() > "/sdcard".length();

        List<File> subDirs = new ArrayList<>();
        List<File> imgFiles = new ArrayList<>();

        if (allFiles != null) {
            for (File f : allFiles) {
                if (f.getName().startsWith("."))
                    continue;
                if (f.isDirectory()) {
                    subDirs.add(f);
                } else {
                    String name = f.getName().toLowerCase(Locale.US);
                    if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
                            || name.endsWith(".webp") || name.endsWith(".bmp")) {
                        imgFiles.add(f);
                    }
                }
            }
        }

        // Fallback: Nếu không đọc được file trực tiếp (scoped storage), dùng MediaStore
        if (imgFiles.isEmpty()) {
            try {
                android.database.Cursor cursor = getContentResolver().query(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        new String[] { android.provider.MediaStore.Images.Media.DATA },
                        null, null,
                        android.provider.MediaStore.Images.Media.DATE_MODIFIED + " DESC");
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        String path = cursor.getString(0);
                        if (path != null) {
                            File f = new File(path);
                            if (f.exists() && f.isFile()) {
                                if (currentDir == null || f.getAbsolutePath().startsWith(dir.getAbsolutePath()) || dir.getAbsolutePath().contains("Pictures") || dir.getAbsolutePath().contains("pictures")) {
                                    if (!imgFiles.contains(f)) {
                                        imgFiles.add(f);
                                    }
                                }
                            }
                        }
                    }
                    cursor.close();
                }
            } catch (Throwable ignored) {
            }
        }

        Collections.sort(subDirs, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        Collections.sort(imgFiles, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        items.addAll(subDirs);
        items.addAll(imgFiles);

        android.util.Log.d("WALLPAPER", "showImagePickerDialog: dir=" + dir.getAbsolutePath()
                + " subDirs=" + subDirs.size() + " imgFiles=" + imgFiles.size() + " canGoUp=" + canGoUp);

        if (items.isEmpty() && !canGoUp) {
            toast("Không có hình ảnh nào trong " + dir.getAbsolutePath());
            return;
        }

        final List<Object> displayList = new ArrayList<>();
        if (canGoUp) {
            displayList.add("📁 .. (Thư mục cha)");
        }
        displayList.addAll(items);

        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("Chọn hình ảnh: " + dir.getName());

        ArrayAdapter<Object> adapter = new ArrayAdapter<Object>(this, 0, displayList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parentView) {
                LinearLayout row = new LinearLayout(getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(20), dp(10), dp(20), dp(10));
                row.setFocusable(false);
                row.setClickable(false);

                Object obj = getItem(position);
                final ImageView icon = new ImageView(getContext());
                icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
                icon.setFocusable(false);

                if (obj instanceof String) {
                    TextView label = text((String) obj, 16, Color.WHITE, false);
                    label.setPadding(dp(8), 0, 0, 0);
                    label.setFocusable(false);
                    row.addView(label, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                } else if (obj instanceof File) {
                    File f = (File) obj;
                    if (f.isDirectory()) {
                        TextView label = text("📁 " + f.getName(), 16, Color.WHITE, false);
                        label.setPadding(dp(8), 0, 0, 0);
                        label.setFocusable(false);
                        row.addView(label, new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    } else {
                        final String absPath = f.getAbsolutePath();
                        icon.setTag(absPath);
                        Bitmap cachedThumb = thumbCache.get(absPath);
                        if (cachedThumb != null && !cachedThumb.isRecycled()) {
                            icon.setImageBitmap(cachedThumb);
                        } else {
                            int res = getDrawableResId("ic_wallpaper");
                            if (res != 0) {
                                icon.setImageResource(res);
                            }
                            final int tw = dp(48);
                            final int th = dp(32);
                            if (bgHandler != null) {
                                bgHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        final Bitmap decoded = loadThumbnail(absPath, tw, th);
                                        if (decoded != null) {
                                            thumbCache.put(absPath, decoded);
                                            handler.post(new Runnable() {
                                                @Override
                                                public void run() {
                                                    if (absPath.equals(icon.getTag())) {
                                                        icon.setImageBitmap(decoded);
                                                    }
                                                }
                                            });
                                        }
                                    }
                                });
                            }
                        }
                        icon.setBackground(rounded(Color.parseColor("#33FFFFFF"), 4, 0));
                        icon.setClipToOutline(true);
                        row.addView(icon, sized(48, 32));

                        LinearLayout textCol = new LinearLayout(getContext());
                        textCol.setOrientation(LinearLayout.VERTICAL);
                        textCol.setPadding(dp(16), 0, 0, 0);
                        textCol.setFocusable(false);

                        TextView name = text(f.getName(), 15, Color.WHITE, false);
                        name.setFocusable(false);
                        String sizeStr = formatFileSize(f.length());
                        TextView size = text(sizeStr, 11, Color.parseColor("#80FFFFFF"), false);
                        size.setFocusable(false);

                        textCol.addView(name);
                        textCol.addView(size);
                        row.addView(textCol, new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    }
                }
                return row;
            }
        };

        builder.setAdapter(adapter, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                Object obj = displayList.get(which);
                if (obj instanceof String) {
                    showImagePickerDialog(dir.getParentFile());
                } else if (obj instanceof File) {
                    File selected = (File) obj;
                    if (selected.isDirectory()) {
                        showImagePickerDialog(selected);
                    } else {
                        getSafePrefs().edit()
                                .putString("wallpaper", selected.getAbsolutePath()).apply();
                        toast("Đã áp dụng hình nền: " + selected.getName());
                        recreate();
                    }
                }
            }
        });

        android.app.AlertDialog dialog = builder.create();
        showTracked(dialog);
        if (dialog.getListView() != null) {
            dialog.getListView().requestFocus();
        }
    }

    private Bitmap loadWallpaperBitmap(String path) {
        try {
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, opt);
            int sample = 1;
            while ((opt.outWidth / sample) > 1920 || (opt.outHeight / sample) > 1080) {
                sample *= 2;
            }
            opt.inJustDecodeBounds = false;
            opt.inSampleSize = sample;
            return BitmapFactory.decodeFile(path, opt);
        } catch (Throwable t) {
            return null;
        }
    }

    private Bitmap loadThumbnail(String path, int targetW, int targetH) {
        try {
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, opt);
            int sample = 1;
            while ((opt.outWidth / sample) > targetW * 2 || (opt.outHeight / sample) > targetH * 2) {
                sample *= 2;
            }
            opt.inJustDecodeBounds = false;
            opt.inSampleSize = sample;
            return BitmapFactory.decodeFile(path, opt);
        } catch (Throwable t) {
            return null;
        }
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024)
            return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format(Locale.US, "%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    /** Mở menu cài đặt gốc đầy đủ của Xiaomi (toàn bộ 8 mục). */
    private void openSystemSettings() {
        try {
            Intent setIntent = new Intent();
            setIntent.setComponent(new ComponentName("com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.entry.MainActivity"));
            setIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(setIntent);
            return;
        } catch (Throwable ignored) {
        }
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return;
        } catch (Throwable ignored) {
        }
        try {
            sendBroadcast(new Intent("com.xiaomi.intent.action.show.globalsetting"));
        } catch (Exception e) {
            toast("Không mở được Cài đặt TV");
        }
    }

    /** Mở trực tiếp màn hình cài đặt Wi-Fi của Xiaomi. */
    private void openWifiSettings() {
        try {
            Intent wifi = new Intent();
            wifi.setComponent(new android.content.ComponentName(
                    "com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.wifi.MitvWifiActivity"));
            wifi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(wifi);
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS);
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(fallback);
            } catch (Exception ex) {
                toast("Không mở được Cài đặt Wi-Fi");
            }
        }
    }

    /** Mở trực tiếp màn hình cài đặt Bluetooth của Xiaomi. */
    private void openBluetoothSettings() {
        Intent bt = new Intent();
        bt.setComponent(new android.content.ComponentName(
                "com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.bluetooth.MitvBluetoothActivity"));
        try {
            startActivity(bt);
        } catch (Exception e) {
            toast("Không mở được Cài đặt Bluetooth");
        }
    }

    private void launchPackage(String pkg) {
        if ("com.xiaomi.mitv.settings".equals(pkg)) {
            try {
                Intent setIntent = new Intent();
                setIntent.setComponent(new ComponentName("com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.entry.MainActivity"));
                setIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(setIntent);
                return;
            } catch (Exception e) {
                openSystemSettings();
                return;
            }
        }
        Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) {
            launch = getPackageManager().getLaunchIntentForPackage(pkg);
        }
        if (launch != null) {
            startActivity(launch);
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    // ----- Dữ liệu -----

    private void updateDateViews() {
        Calendar now = Calendar.getInstance();
        if (clockTime != null) {
            clockTime.setText(clockTimeFormat.format(now.getTime()));
        }
        if (clockDate != null) {
            clockDate.setText(clockDateFormat.format(now.getTime()));
        }

        int curDayOfYear = now.get(Calendar.DAY_OF_YEAR);
        int curYear = now.get(Calendar.YEAR);
        if (curDayOfYear != lastCalculatedDayOfYear || curYear != lastCalculatedYear) {
            int[] lunar = Lunar.fromSolar(now.get(Calendar.DAY_OF_MONTH), now.get(Calendar.MONTH) + 1, curYear);
            cachedLunarText = lunar[0] + "/" + lunar[1] + " âm lịch";
            cachedEventText = getUpcomingEventText(now);
            lastCalculatedDayOfYear = curDayOfYear;
            lastCalculatedYear = curYear;
        }

        if (cardLunar != null) {
            cardLunar.setText(cachedLunarText);
        }
        if (cardEvent != null) {
            cardEvent.setText(cachedEventText);
        }
    }

    /**
     * Tự động tìm kiếm sự kiện / ngày lễ gần nhất tiếp theo trong 365 ngày tới
     * (bao gồm cả Dương lịch & Âm lịch truyền thống).
     */
    private String getUpcomingEventText(Calendar now) {
        Calendar cur = (Calendar) now.clone();
        for (int offset = 0; offset <= 365; offset++) {
            int d = cur.get(Calendar.DAY_OF_MONTH);
            int m = cur.get(Calendar.MONTH) + 1;
            int y = cur.get(Calendar.YEAR);

            String event = getSolarEvent(d, m);

            if (event == null) {
                int[] lunar = Lunar.fromSolar(d, m, y);
                int ld = lunar[0];
                int lm = lunar[1];
                event = getLunarEvent(ld, lm);

                // Kiểm tra Đêm Giao Thừa (ngày cuối cùng của năm Âm lịch)
                if (event == null && lm == 12 && (ld == 29 || ld == 30)) {
                    Calendar nextDay = (Calendar) cur.clone();
                    nextDay.add(Calendar.DAY_OF_YEAR, 1);
                    int[] nextLunar = Lunar.fromSolar(
                            nextDay.get(Calendar.DAY_OF_MONTH),
                            nextDay.get(Calendar.MONTH) + 1,
                            nextDay.get(Calendar.YEAR));
                    if (nextLunar[1] == 1 && nextLunar[0] == 1) {
                        event = "Đêm Giao Thừa";
                    }
                }
            }

            if (event != null) {
                if (offset == 0) {
                    return "Hôm nay: " + event;
                } else if (offset == 1) {
                    return event + " · Ngày mai";
                } else {
                    return event + " · còn " + offset + " ngày";
                }
            }
            cur.add(Calendar.DAY_OF_YEAR, 1);
        }
        return "";
    }

    private String getSolarEvent(int day, int month) {
        int key = month * 100 + day;
        switch (key) {
            case 101: return "Tết Dương Lịch";
            case 214: return "Lễ Tình Nhân (Valentine)";
            case 227: return "Thầy thuốc Việt Nam";
            case 308: return "Quốc tế Phụ nữ";
            case 430: return "Giải phóng miền Nam";
            case 501: return "Quốc tế Lao động";
            case 519: return "Sinh nhật Bác Hồ";
            case 601: return "Quốc tế Thiếu nhi";
            case 628: return "Gia đình Việt Nam";
            case 727: return "Thương binh Liệt sĩ";
            case 819: return "Cách mạng Tháng Tám";
            case 902: return "Quốc khánh Việt Nam";
            case 1010: return "Giải phóng Thủ đô";
            case 1020: return "Phụ nữ Việt Nam";
            case 1120: return "Nhà giáo Việt Nam";
            case 1222: return "Quân đội Nhân dân";
            case 1224: return "Đêm Giáng sinh (Noel)";
            case 1225: return "Lễ Giáng sinh (Noel)";
            case 1231: return "Giao thừa Dương lịch";
            default: return null;
        }
    }

    private String getLunarEvent(int lunarDay, int lunarMonth) {
        int key = lunarMonth * 100 + lunarDay;
        switch (key) {
            case 101: return "Mùng 1 Tết Nguyên Đán";
            case 102: return "Mùng 2 Tết Nguyên Đán";
            case 103: return "Mùng 3 Tết Nguyên Đán";
            case 115: return "Rằm Tháng Giêng";
            case 303: return "Tết Hàn Thực";
            case 310: return "Giỗ Tổ Hùng Vương";
            case 415: return "Đại lễ Phật Đản";
            case 505: return "Tết Đoan Ngọ";
            case 715: return "Lễ Vu Lan (Báo hiếu)";
            case 815: return "Tết Trung Thu";
            case 1223: return "Ông Táo chầu trời";
            default: return null;
        }
    }

    private void loadWeather() {
        WeatherService.load(new WeatherService.Callback() {
            @Override
            public void onWeather(WeatherService.Weather w) {
                applyWeather(w);
            }
        });
    }

    private void loadMarket() {
        MarketService.load(new MarketService.Callback() {
            @Override
            public void onMarket(MarketService.Market market) {
                applyMarket(market);
            }
        });
    }

    private void applyMarket(MarketService.Market m) {
        if (m == null) return;
        if (fuelE5 != null) fuelE5.setText(m.e5);
        if (fuelE10 != null) fuelE10.setText(m.e10);
        if (fuelDiesel != null) fuelDiesel.setText(m.diesel);
        if (goldSjc != null) goldSjc.setText(m.sjcBuy + " - " + m.sjcSell);
        if (goldNhan != null) goldNhan.setText(m.nhanBuy + " - " + m.nhanSell);
        if (goldWorld != null) goldWorld.setText(m.worldGold);
        if (marketFooter != null && m.date != null && !m.date.isEmpty()) {
            marketFooter.setText("Cập nhật " + m.date + " · Nguồn: " + m.source + (m.sample ? " (mẫu)" : ""));
        }
    }

    private void applyWeather(WeatherService.Weather w) {
        if (weatherIcon != null) {
            weatherIcon.setText(getWeatherSymbol(w.condition));
        }
        weatherTemp.setText(w.temp + "°C");
        weatherCond.setText(w.condition + " · " + w.tempMin + "° / " + w.tempMax + "°");
        weatherCity.setText(w.city + (w.sample ? " (dữ liệu mẫu)" : ""));
        if (weatherFooter != null) {
            String updateDate = new SimpleDateFormat("dd/MM", Locale.ENGLISH).format(Calendar.getInstance().getTime());
            weatherFooter.setText("Cập nhật " + updateDate + " · Nguồn: Open-Meteo" + (w.sample ? " (mẫu)" : ""));
        }
        weatherDays.removeAllViews();
        if (w.dayLabels != null) {
            for (int i = 0; i < w.dayLabels.length; i++) {
                LinearLayout col = new LinearLayout(this);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setGravity(Gravity.CENTER_HORIZONTAL);
                String dayName = w.dayLabels[i];
                if (dayName != null) {
                    if (dayName.startsWith("Th ")) {
                        dayName = "Thứ " + dayName.substring(3);
                    } else if ("CN".equalsIgnoreCase(dayName)) {
                        dayName = "Chủ Nhật";
                    }
                }
                col.addView(text(dayName, 11, Color.WHITE, false));
                if (w.dayConditions != null && i < w.dayConditions.length) {
                    col.addView(text(getWeatherSymbol(w.dayConditions[i]), 13, Color.WHITE, false));
                    col.addView(text(w.dayConditions[i], 10, Color.parseColor("#E2E8F0"), false));
                }
                if (w.dayMax != null && i < w.dayMax.length) {
                    col.addView(text(w.dayMax[i] + "°", 12, Color.WHITE, true));
                }
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) p.leftMargin = dp(12);
                weatherDays.addView(col, p);
            }
        }
    }

    private String getWeatherSymbol(String cond) {
        if (cond == null) return "⛅";
        String c = cond.toLowerCase(Locale.ROOT);
        if (c.contains("quang") || c.contains("nắng")) return "☀️";
        if (c.contains("ít mây")) return "🌤️";
        if (c.contains("rải rác") || c.contains("nhiều mây") || c.contains("mây")) return "☁️";
        if (c.contains("dông")) return "⛈️";
        if (c.contains("mưa phùn")) return "🌦️";
        if (c.contains("mưa")) return "🌧️";
        if (c.contains("sương mù")) return "🌫️";
        if (c.contains("tuyết")) return "❄️";
        return "⛅";
    }

    private static final Set<String> EXCLUDED_PACKAGES = new HashSet<>(Arrays.asList(
            "com.google.android.katniss",
            "com.liskovsoft.leankeyboard",
            "com.ts.launcher",
            "vn.herotran.launcher"
    ));

    private List<ResolveInfo> loadLeanbackApps() {
        allApps = queryAllLeanbackApps();
        return filterVisibleApps(allApps);
    }

    private List<ResolveInfo> queryAllLeanbackApps() {
        final PackageManager pm = getPackageManager();
        String self = getPackageName();

        // 1. Quét Leanback launcher apps (chuẩn Android TV)
        Intent lbIntent = new Intent(Intent.ACTION_MAIN);
        lbIntent.addCategory("android.intent.category.LEANBACK_LAUNCHER");
        List<ResolveInfo> lbList = pm.queryIntentActivities(lbIntent, 0);

        // 2. Quét Launcher apps thông thường (sideload / mobile apps)
        Intent normalIntent = new Intent(Intent.ACTION_MAIN);
        normalIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> normalList = pm.queryIntentActivities(normalIntent, 0);

        // 3. Quét danh sách launcher (HOME) để luôn loại bỏ
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> homeList = pm.queryIntentActivities(homeIntent, 0);
        Set<String> homePackages = new HashSet<>();
        if (homeList != null) {
            for (ResolveInfo r : homeList) {
                if (r.activityInfo != null && r.activityInfo.packageName != null) {
                    homePackages.add(r.activityInfo.packageName);
                }
            }
        }

        Map<String, ResolveInfo> appMap = new LinkedHashMap<>();

        // Ưu tiên bản Leanback launcher trước
        if (lbList != null) {
            for (ResolveInfo info : lbList) {
                if (info.activityInfo == null) continue;
                String pkg = info.activityInfo.packageName;
                if (pkg == null || pkg.equals(self) || EXCLUDED_PACKAGES.contains(pkg) || homePackages.contains(pkg)) {
                    continue;
                }
                appMap.put(pkg, info);
            }
        }

        // Bổ sung các app đã cài (sideload / mobile) chưa có trong appMap
        if (normalList != null) {
            for (ResolveInfo info : normalList) {
                if (info.activityInfo == null) continue;
                String pkg = info.activityInfo.packageName;
                if (pkg == null || appMap.containsKey(pkg)) {
                    continue;
                }
                if (pkg.equals(self) || EXCLUDED_PACKAGES.contains(pkg) || homePackages.contains(pkg)) {
                    continue;
                }
                // Chỉ lấy app đã cài đặt (người dùng cài vào /data/app hoặc bản cập nhật)
                boolean isInstalled = (info.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0
                        || (info.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
                if (isInstalled) {
                    appMap.put(pkg, info);
                }
            }
        }

        // Luôn bổ sung ứng dụng Cài đặt gốc Xiaomi (com.xiaomi.mitv.settings) nếu có trên TV
        if (!appMap.containsKey("com.xiaomi.mitv.settings")) {
            Intent setIntent = new Intent(Intent.ACTION_MAIN);
            setIntent.setComponent(new ComponentName("com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.entry.MainActivity"));
            ResolveInfo setInfo = pm.resolveActivity(setIntent, 0);
            if (setInfo == null) {
                Intent setIntent2 = new Intent("com.xiaomi.mitv.settings.SETTINGS");
                setInfo = pm.resolveActivity(setIntent2, 0);
            }
            if (setInfo != null && setInfo.activityInfo != null) {
                appMap.put("com.xiaomi.mitv.settings", setInfo);
            }
        }

        List<ResolveInfo> list = new ArrayList<>(appMap.values());
        appLabelCache.clear();
        for (ResolveInfo info : list) {
            String pkg = info.activityInfo.packageName;
            if ("com.xiaomi.mitv.settings".equals(pkg)) {
                appLabelCache.put(pkg, "Cài đặt TV");
            } else {
                CharSequence cs = info.loadLabel(pm);
                appLabelCache.put(pkg, cs != null ? cs.toString() : pkg);
            }
        }

        Collections.sort(list, new Comparator<ResolveInfo>() {
            @Override
            public int compare(ResolveInfo a, ResolveInfo b) {
                String pkgA = a.activityInfo.packageName;
                String pkgB = b.activityInfo.packageName;
                int idxA = PINNED_PACKAGES.indexOf(pkgA);
                int idxB = PINNED_PACKAGES.indexOf(pkgB);
                if (idxA != -1 && idxB != -1) {
                    return Integer.compare(idxA, idxB);
                }
                if (idxA != -1) return -1;
                if (idxB != -1) return 1;
                String labelA = appLabelCache.get(pkgA);
                String labelB = appLabelCache.get(pkgB);
                if (labelA == null) labelA = pkgA;
                if (labelB == null) labelB = pkgB;
                return labelA.compareToIgnoreCase(labelB);
            }
        });
        applySavedOrder(list);
        return list;
    }

    // ----- Tiện ích giao diện -----

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        if (bold)
            tv.setTypeface(null, Typeface.BOLD);
        tv.setShadowLayer(dp(2), 0, dp(1), Color.parseColor("#B0000000"));
        return tv;
    }

    private static final int FOCUS_BORDER_COLOR = Color.WHITE;

    private void attachFocusScale(final View view) {
        attachFocusScale(view, 0, FOCUS_BORDER_COLOR);
    }

    private void attachFocusScale(final View view, final int normalStrokeColor) {
        attachFocusScale(view, normalStrokeColor, FOCUS_BORDER_COLOR);
    }

    private void attachFocusScale(final View view, final int normalStrokeColor, final int focusStrokeColor) {
        view.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                float s = hasFocus ? 1.08f : 1f;
                v.animate().scaleX(s).scaleY(s).setDuration(150)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .start();
                v.setElevation(hasFocus ? dp(8) : 0);

                Drawable bg = v.getBackground();
                if (bg instanceof GradientDrawable) {
                    GradientDrawable gd = (GradientDrawable) bg;
                    if (hasFocus) {
                        gd.setStroke(dp(1), focusStrokeColor);
                    } else {
                        if (normalStrokeColor != 0) {
                            gd.setStroke(dp(1), normalStrokeColor);
                        } else {
                            gd.setStroke(0, Color.TRANSPARENT);
                        }
                    }
                }
            }
        });
    }

    private GradientDrawable circle(int color, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        if (strokeColor != 0) {
            g.setStroke(dp(1), strokeColor);
        }
        return g;
    }

    private GradientDrawable rounded(int color, int radiusDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) {
            g.setStroke(dp(1), strokeColor);
        }
        return g;
    }

    private LinearLayout.LayoutParams sized(int widthDp, int heightDp) {
        return new LinearLayout.LayoutParams(dp(widthDp), dp(heightDp));
    }

    private LinearLayout.LayoutParams withLeft(LinearLayout.LayoutParams p, int dpValue) {
        p.leftMargin = dp(dpValue);
        return p;
    }

    private LinearLayout.LayoutParams withRight(LinearLayout.LayoutParams p, int dpValue) {
        p.rightMargin = dp(dpValue);
        return p;
    }

    private LinearLayout.LayoutParams withTop(LinearLayout.LayoutParams p, int dpValue) {
        p.topMargin = dp(dpValue);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private Map<Long, Long> lastTimeInState = new HashMap<>();

    private int getCpuUsagePercent() {
        java.io.BufferedReader reader = null;
        try {
            File f = new File("/sys/devices/system/cpu/cpu0/cpufreq/stats/time_in_state");
            if (f.exists() && f.canRead()) {
                reader = new java.io.BufferedReader(new java.io.FileReader(f));
                String line;
                Map<Long, Long> current = new HashMap<>();
                long minFreq = -1;
                long maxFreq = -1;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) {
                        try {
                            long freq = Long.parseLong(parts[0]);
                            long ticks = Long.parseLong(parts[1]);
                            current.put(freq, ticks);
                            if (ticks > 0) {
                                if (minFreq == -1 || freq < minFreq) minFreq = freq;
                                if (maxFreq == -1 || freq > maxFreq) maxFreq = freq;
                            }
                        } catch (Exception ignored) {}
                    }
                }
                if (!lastTimeInState.isEmpty() && maxFreq > minFreq) {
                    double totalWeighted = 0;
                    long totalDeltaTicks = 0;
                    for (Map.Entry<Long, Long> entry : current.entrySet()) {
                        long freq = entry.getKey();
                        long ticks = entry.getValue();
                        Long prev = lastTimeInState.get(freq);
                        if (prev != null && ticks >= prev) {
                            long delta = ticks - prev;
                            totalDeltaTicks += delta;
                            double weight = Math.max(0.0, (double) (freq - minFreq) / (double) (maxFreq - minFreq));
                            totalWeighted += weight * delta;
                        }
                    }
                    lastTimeInState = current;
                    if (totalDeltaTicks > 0) {
                        int pct = (int) Math.round((totalWeighted / (double) totalDeltaTicks) * 100.0);
                        return Math.max(0, Math.min(100, pct));
                    }
                } else {
                    lastTimeInState = current;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) {}
            }
        }

        // Fallback: scaling_cur_freq
        try {
            File f = new File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq");
            if (f.exists() && f.canRead()) {
                reader = new java.io.BufferedReader(new java.io.FileReader(f));
                String l = reader.readLine();
                if (l != null) {
                    long curFreq = Long.parseLong(l.trim());
                    long minFreq = 1000000;
                    long maxFreq = 1908000;
                    int pct = (int) Math.round(((double) (curFreq - minFreq) / (double) (maxFreq - minFreq)) * 100.0);
                    return Math.max(0, Math.min(100, pct));
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) {}
            }
        }
        return 0;
    }

    private String getRamUsageText() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                double totalGb = (double) mi.totalMem / (1024.0 * 1024.0 * 1024.0);
                double usedGb = (double) (mi.totalMem - mi.availMem) / (1024.0 * 1024.0 * 1024.0);
                return String.format(Locale.US, "%.1f/%.1f Gb", usedGb, totalGb);
            }
        } catch (Exception ignored) {}
        return "";
    }

    private String getRomUsageText() {
        try {
            File path = Environment.getDataDirectory();
            android.os.StatFs stat = new android.os.StatFs(path.getPath());
            long blockSize = stat.getBlockSizeLong();
            long totalBlocks = stat.getBlockCountLong();
            long availableBlocks = stat.getAvailableBlocksLong();

            long totalRom = totalBlocks * blockSize;
            long usedRom = totalRom - (availableBlocks * blockSize);

            double totalGb = (double) totalRom / (1024.0 * 1024.0 * 1024.0);
            double usedGb = (double) usedRom / (1024.0 * 1024.0 * 1024.0);
            return String.format(Locale.US, "%.1f/%.1f Gb", usedGb, totalGb);
        } catch (Exception ignored) {}
        return "";
    }
}

