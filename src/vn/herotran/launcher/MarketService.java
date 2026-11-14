package vn.herotran.launcher;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * NGUON DUY NHAT: webgia.com — theo yeu cau nguoi dung.
 *   Xang:  https://webgia.com/gia-xang-dau/petrolimex/
 *          + trang lich su theo ngay https://webgia.com/gia-xang-dau/petrolimex/YYYY-MM-DD.html
 *   Vang:  https://webgia.com/gia-vang/sjc/ + https://webgia.com/gia-vang-the-gioi/
 *
 * CAU HINH RIENG CUA BAN (QUAN TRONG, DUNG DOI):
 *   - Khu vuc: VUNG 1 (lay cot gia dau tien, khong lay cot Vung 2)
 *   - Xang ban dung: E10 RON 95 MUC 3 = E10 RON 95-III
 *     Hien tai (12/11/2026 15:00, Vung 1): E10 RON 95-III = 19.840
 *     KHONG PHAI Muc 5: E10 RON 95-V = 21.840 (Vung 1) / 22.270 (Vung 2)
 *   - Loi ban truoc: code co fallback "E10 RON 95" chung chung, neu khong khop III
 *     thi no se trung dong V dau tien trong bang va tra 21.840 (Muc 5).
 *     Ban nay DA BO HOAN TOAN fallback Muc 5, chi khop III / Muc 3.
 *
 * Vang webgia: don vi DONG/CHI, vd "Vang SJC 1L 13.500.000 13.800.000"
 *   => 13.500.000 dong/chi = 135,0 trieu/luong. Phai chia 100.000.
 * Xang webgia: bang Petrolimex co 2 cot Vung 1 / Vung 2, lay cot dau (Vung 1).
 *
 * Khi khong lay duoc thi tat ca cac cot deu la "Updating...".
 */
public final class MarketService {

    public static final String NO_DATA = "Updating...";
    public static final String WAITING = "Updating...";

    // ---- webgia.com — Xang ----
    private static final String WEBGIA_FUEL_PETROLIMEX = "https://webgia.com/gia-xang-dau/petrolimex/";
    private static final String WEBGIA_FUEL = "https://webgia.com/gia-xang-dau/";
    // CHI KHOP MUC 3 (III), KHONG KHOP MUC 5 (V). Ho tro cac cach viet:
    // "95-III", "95 - III", "95 III", "95 Muc 3", "95 MỨC 3", "95–III"
    private static final Pattern WEBGIA_E10_MUC3 = Pattern.compile("(?i)E10\\s*RON\\s*95\\s*[-\\u2013\\u2014]?\\s*III\\b.{0,160}?(\\d{2}[.,]\\d{3})", Pattern.DOTALL);
    private static final Pattern WEBGIA_E10_MUC3_V2 = Pattern.compile("(?i)E10\\s*RON\\s*95\\s*(?:M[\\u1ee9u]c|MUC)\\s*3\\b.{0,160}?(\\d{2}[.,]\\d{3})", Pattern.DOTALL);
    private static final Pattern WEBGIA_E10_MUC3_V3 = Pattern.compile("(?i)E10.{0,60}?95-III.{0,160}?(\\d{2}[.,]\\d{3})", Pattern.DOTALL);
    // Cam lay Muc 5: pattern nay chi de kiem tra / loai bo, khong dung de lay gia E10
    // E5 va Dau da la II nen khong bi nham V
    private static final Pattern WEBGIA_E5 = Pattern.compile("(?i)E5\\s*RON\\s*92.{0,160}?(\\d{2}[.,]\\d{3})", Pattern.DOTALL);
    private static final Pattern WEBGIA_DIESEL = Pattern.compile("(?i)DO\\s*0[.,]05S.{0,160}?(\\d{2}[.,]\\d{3})", Pattern.DOTALL);
    private static final Pattern WEBGIA_DIESEL2 = Pattern.compile("(?i)(?:Dầu|Dau)\\s*(?:DO|điêzen|diezen)?\\s*0[.,]05S.{0,160}?(\\d{2}[.,]\\d{3})", Pattern.DOTALL);
    private static final Pattern WEBGIA_DATE = Pattern.compile("(\\d{1,2}/\\d{1,2}/\\d{4})");

    // ---- webgia.com — Vang ----
    private static final String WEBGIA_GOLD_SJC = "https://webgia.com/gia-vang/sjc/";
    private static final String WEBGIA_GOLD = "https://webgia.com/gia-vang/";
    private static final String WEBGIA_GOLD_WORLD = "https://webgia.com/gia-vang-the-gioi/";
    private static final String WEBGIA_HOME = "https://webgia.com/";
    private static final Pattern WEBGIA_SJC = Pattern.compile("(?i)Vàng SJC 1L.{0,160}?(\\d{2}[.,]\\d{3}[.,]\\d{3})\\s+(\\d{2}[.,]\\d{3}[.,]\\d{3})", Pattern.DOTALL);
    private static final Pattern WEBGIA_SJC_HOME = Pattern.compile("(?i)SJC.{0,80}?(\\d{2}[.,]\\d{3}[.,]\\d{3})\\s+(\\d{2}[.,]\\d{3}[.,]\\d{3})", Pattern.DOTALL);
    private static final Pattern WEBGIA_NHAN = Pattern.compile("(?i)Vàng nhẫn SJC.{0,200}?(\\d{2}[.,]\\d{3}[.,]\\d{3})\\s+(\\d{2}[.,]\\d{3}[.,]\\d{3})", Pattern.DOTALL);
    private static final Pattern WEBGIA_WORLD = Pattern.compile("(?i)(\\d[.,]\\d{3}[.,]\\d{2}|\\d[.,]\\d{3})\\s*USD", Pattern.DOTALL);
    private static final Pattern WEBGIA_WORLD2 = Pattern.compile("(?i)XAU.{0,200}?(\\d[.,]\\d{3}[.,]\\d{2})", Pattern.DOTALL);

    private static final Pattern SCRIPT_STYLE_PATTERN = Pattern.compile("(?s)<script.*?</script>|<style.*?</style>");
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    private static final long DATA_TTL_MS = 2 * 60 * 60 * 1000L;
    private static final long EMPTY_RETRY_MS = 2 * 60 * 1000L;

    private static volatile Market cachedMarket;
    private static volatile long lastFetchTime = 0;
    private static final AtomicBoolean isFetching = new AtomicBoolean(false);
    private static final List<Callback> pendingCallbacks = new ArrayList<>();

    public interface Callback {
        void onMarket(Market market);
    }

    public static final class Market {
        public String e5;
        public String e10;
        public String diesel;
        public String sjcBuy;
        public String sjcSell;
        public String nhanBuy;
        public String nhanSell;
        public String worldGold;
        public String date;
        public String source;
        public boolean sample;
        public boolean empty;
    }

    private MarketService() {
    }

    public static void load(final Callback callback) {
        final Handler main = new Handler(Looper.getMainLooper());
        final long now = SystemClock.elapsedRealtime();
        synchronized (MarketService.class) {
            final Market currentCache = cachedMarket;
            if (currentCache != null) {
                long ttl = currentCache.empty ? EMPTY_RETRY_MS : DATA_TTL_MS;
                if ((now - lastFetchTime) < ttl) {
                    main.post(new Runnable() {
                        @Override public void run() { callback.onMarket(currentCache); }
                    });
                    return;
                }
            }
            pendingCallbacks.add(callback);
            if (!isFetching.compareAndSet(false, true)) {
                return;
            }
        }
        new Thread(new Runnable() {
            @Override public void run() {
                Market result;
                try {
                    result = fetch();
                } catch (Exception e) {
                    result = (cachedMarket != null && !cachedMarket.empty) ? cachedMarket : empty();
                } finally {
                    isFetching.set(false);
                }
                cachedMarket = result;
                lastFetchTime = SystemClock.elapsedRealtime();
                final List<Callback> toNotify;
                synchronized (MarketService.class) {
                    toNotify = new ArrayList<>(pendingCallbacks);
                    pendingCallbacks.clear();
                }
                final Market out = result;
                for (final Callback cb : toNotify) {
                    main.post(new Runnable() {
                        @Override public void run() { cb.onMarket(out); }
                    });
                }
            }
        }).start();
    }

    private static Market fetch() throws Exception {
        Market fuel = null;
        try { fuel = fetchWebgiaFuel(); } catch (Exception ignored) {}
        Market gold = null;
        try { gold = fetchWebgiaGold(); } catch (Exception ignored) {}

        if (fuel == null && gold == null) {
            throw new IllegalStateException("Khong lay duoc webgia.com xang + vang");
        }

        Market base = (cachedMarket != null && !cachedMarket.empty) ? cachedMarket : null;
        Market m = new Market();
        m.e5 = (fuel != null && fuel.e5 != null) ? fuel.e5 : (base != null ? base.e5 : NO_DATA);
        m.e10 = (fuel != null && fuel.e10 != null) ? fuel.e10 : (base != null ? base.e10 : NO_DATA);
        m.diesel = (fuel != null && fuel.diesel != null) ? fuel.diesel : (base != null ? base.diesel : NO_DATA);
        m.sjcBuy = (gold != null && gold.sjcBuy != null) ? gold.sjcBuy : (base != null ? base.sjcBuy : NO_DATA);
        m.sjcSell = (gold != null && gold.sjcSell != null) ? gold.sjcSell : (base != null ? base.sjcSell : NO_DATA);
        m.nhanBuy = (gold != null && gold.nhanBuy != null) ? gold.nhanBuy : (base != null ? base.nhanBuy : NO_DATA);
        m.nhanSell = (gold != null && gold.nhanSell != null) ? gold.nhanSell : (base != null ? base.nhanSell : NO_DATA);
        m.worldGold = (gold != null && gold.worldGold != null) ? gold.worldGold : (base != null ? base.worldGold : NO_DATA);
        String d = "";
        if (gold != null && gold.date != null && !gold.date.isEmpty() && !gold.date.equals("--/--")) d = gold.date;
        else if (fuel != null && fuel.date != null && !fuel.date.isEmpty()) d = fuel.date;
        else if (base != null) d = base.date;
        else d = "--/--";
        m.date = d;
        m.source = "WebGia.com";
        m.sample = false;
        m.empty = false;
        return m;
    }

    // ---------------- XANG — webgia.com, Vung 1, E10 Muc 3 ----------------
    private static Market fetchWebgiaFuel() {
        String[] urls = new String[]{WEBGIA_FUEL_PETROLIMEX, WEBGIA_FUEL};
        for (String u : urls) {
            try {
                String html = get(u);
                Market m = parseFuelText(html);
                if (m != null) return m;
            } catch (Exception ignored) {}
        }
        try {
            Calendar cal = Calendar.getInstance();
            for (int i = 0; i < 14; i++) {
                int y = cal.get(Calendar.YEAR);
                int mo = cal.get(Calendar.MONTH) + 1;
                int da = cal.get(Calendar.DAY_OF_MONTH);
                String ds = String.format("%04d-%02d-%02d", y, mo, da);
                String url = "https://webgia.com/gia-xang-dau/petrolimex/" + ds + ".html";
                try {
                    String html = get(url);
                    Market m = parseFuelText(html);
                    if (m != null) return m;
                } catch (Exception ignored) {}
                cal.add(Calendar.DAY_OF_MONTH, -1);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static Market parseFuelText(String html) {
        if (html == null) return null;
        Market m = parseFuelFromText(stripHtml(html));
        if (m != null) return m;
        try {
            String raw = HTML_TAG_PATTERN.matcher(html).replaceAll(" ");
            raw = decodeHtmlEntities(raw);
            raw = normalizeText(raw);
            Market m2 = parseFuelFromText(raw);
            if (m2 != null) return m2;
        } catch (Exception ignored) {}
        return null;
    }

    private static Market parseFuelFromText(String text) {
        if (text == null) return null;
        // E10: CHI MUC 3. Thu 3 cach viet, khong co fallback Muc 5.
        String e10 = firstGroup(WEBGIA_E10_MUC3, text);
        if (e10 == null) e10 = firstGroup(WEBGIA_E10_MUC3_V2, text);
        if (e10 == null) e10 = firstGroup(WEBGIA_E10_MUC3_V3, text);
        // E5 RON 92-II, DO 0,05S-II
        String e5 = firstGroup(WEBGIA_E5, text);
        String diesel = firstGroup(WEBGIA_DIESEL, text);
        if (diesel == null) diesel = firstGroup(WEBGIA_DIESEL2, text);
        if (!isValidFuel(e10) || !isValidFuel(e5) || !isValidFuel(diesel)) return null;
        Market m = new Market();
        // Lay so dau tien sau ten = cot Vung 1. Vd "E10 RON 95-III 19.840 20.230" => 19.840
        m.e10 = e10.replace(',', '.');
        m.e5 = e5.replace(',', '.');
        m.diesel = diesel.replace(',', '.');
        m.date = webgiaDate(text);
        return m;
    }

    private static boolean isValidFuel(String v) {
        if (v == null) return false;
        try {
            double d = Double.parseDouble(v.replace(',', '.'));
            return d >= 10.0 && d <= 60.0;
        } catch (Exception e) { return false; }
    }

    private static String webgiaDate(String text) {
        String d = firstGroup(WEBGIA_DATE, text);
        if (d == null) return "--/--";
        String[] pp = d.split("/");
        try { return Integer.parseInt(pp[0]) + "/" + Integer.parseInt(pp[1]); } catch (Exception e) { return d; }
    }

    // ---------------- VANG — webgia.com ----------------
    private static Market fetchWebgiaGold() {
        String sjcHtml = null;
        try { sjcHtml = get(WEBGIA_GOLD_SJC); } catch (Exception ignored) {}
        if (sjcHtml != null) {
            Market m = parseGoldSjcPage(sjcHtml);
            if (m != null) {
                try {
                    String wHtml = get(WEBGIA_GOLD_WORLD);
                    String w = parseWorldGold(wHtml);
                    if (w != null) m.worldGold = w;
                } catch (Exception ignored) {}
                if (m.worldGold == null) {
                    try {
                        String w2 = parseWorldGold(sjcHtml);
                        if (w2 != null) m.worldGold = w2;
                    } catch (Exception ignored) {}
                }
                if (m.worldGold == null) m.worldGold = NO_DATA;
                return m;
            }
        }
        String[] fallbacks = new String[]{WEBGIA_GOLD, WEBGIA_HOME};
        for (String u : fallbacks) {
            try {
                String html = get(u);
                Market m = parseGoldSjcPage(html);
                if (m != null) {
                    if (m.worldGold == null) m.worldGold = NO_DATA;
                    return m;
                }
                Market m2 = parseGoldHomeTable(html);
                if (m2 != null) {
                    if (m2.worldGold == null) m2.worldGold = NO_DATA;
                    return m2;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static Market parseGoldSjcPage(String html) {
        if (html == null) return null;
        String text = stripHtml(html);
        Matcher ms = WEBGIA_SJC.matcher(text);
        String buyRaw = null, sellRaw = null;
        if (ms.find()) { buyRaw = ms.group(1); sellRaw = ms.group(2); }
        else {
            try {
                String raw = normalizeText(decodeHtmlEntities(HTML_TAG_PATTERN.matcher(html).replaceAll(" ")));
                Matcher ms2 = WEBGIA_SJC.matcher(raw);
                if (ms2.find()) { buyRaw = ms2.group(1); sellRaw = ms2.group(2); text = raw; }
            } catch (Exception ignored) {}
        }
        if (buyRaw == null) return null;
        Market m = new Market();
        m.sjcBuy = chiToTrieu(buyRaw);
        m.sjcSell = chiToTrieu(sellRaw);
        Matcher mn = WEBGIA_NHAN.matcher(text);
        if (mn.find()) {
            m.nhanBuy = chiToTrieu(mn.group(1));
            m.nhanSell = chiToTrieu(mn.group(2));
        } else {
            m.nhanBuy = NO_DATA;
            m.nhanSell = NO_DATA;
        }
        m.date = webgiaDate(text);
        m.worldGold = null;
        return m;
    }

    private static Market parseGoldHomeTable(String html) {
        if (html == null) return null;
        String text = stripHtml(html);
        Matcher ms = WEBGIA_SJC_HOME.matcher(text);
        if (!ms.find()) return null;
        Market m = new Market();
        m.sjcBuy = chiToTrieu(ms.group(1));
        m.sjcSell = chiToTrieu(ms.group(2));
        m.nhanBuy = NO_DATA;
        m.nhanSell = NO_DATA;
        m.date = webgiaDate(text);
        m.worldGold = null;
        return m;
    }

    private static String parseWorldGold(String html) {
        if (html == null) return null;
        String text = stripHtml(html);
        Matcher mw = WEBGIA_WORLD.matcher(text);
        if (mw.find()) return mw.group(1).replace(',', '.');
        Matcher mw2 = WEBGIA_WORLD2.matcher(text);
        if (mw2.find()) return mw2.group(1).replace(',', '.');
        return null;
    }

    private static String chiToTrieu(String v) {
        if (v == null) return NO_DATA;
        try {
            String clean = v.trim().replace(".", "").replace(",", "");
            double dongPerChi = Double.parseDouble(clean);
            double trieuPerLuong = dongPerChi / 100000.0;
            String s = String.format("%.1f", trieuPerLuong);
            return s.replace('.', ',');
        } catch (Exception e) {
            return NO_DATA;
        }
    }

    public static Market empty() {
        Market m = new Market();
        m.sample = false;
        m.empty = true;
        m.date = "--/--";
        m.source = "WebGia.com";
        m.e5 = NO_DATA; m.e10 = NO_DATA; m.diesel = NO_DATA;
        m.sjcBuy = NO_DATA; m.sjcSell = NO_DATA;
        m.nhanBuy = NO_DATA; m.nhanSell = NO_DATA; m.worldGold = NO_DATA;
        return m;
    }

    @Deprecated
    public static Market sample() {
        return empty();
    }

    private static String stripHtml(String html) {
        String s = SCRIPT_STYLE_PATTERN.matcher(html).replaceAll(" ");
        s = HTML_TAG_PATTERN.matcher(s).replaceAll(" ");
        s = decodeHtmlEntities(s);
        return normalizeText(s);
    }

    private static String normalizeText(String s) {
        if (s == null) return "";
        s = s.replace('\u00A0', ' ').replace('\u2007', ' ').replace('\u202F', ' ')
             .replace('\u200B', ' ').replace('\uFEFF', ' ');
        return WHITESPACE_PATTERN.matcher(s).replaceAll(" ").trim();
    }

    private static String decodeHtmlEntities(String s) {
        if (s == null) return "";
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
             .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'");
        try {
            Matcher m = Pattern.compile("&#(\\d+);").matcher(s);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                int code = Integer.parseInt(m.group(1));
                m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char) code)));
            }
            m.appendTail(sb);
            s = sb.toString();
            Matcher m2 = Pattern.compile("&#x([0-9a-fA-F]+);").matcher(s);
            StringBuffer sb2 = new StringBuffer();
            while (m2.find()) {
                int code = Integer.parseInt(m2.group(1), 16);
                m2.appendReplacement(sb2, Matcher.quoteReplacement(String.valueOf((char) code)));
            }
            m2.appendTail(sb2);
            s = sb2.toString();
        } catch (Exception ignored) {}
        return s;
    }

    private static String firstGroup(Pattern p, String s) {
        if (s == null) return null;
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String get(String address) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(address).openConnection();
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(12000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
        conn.setRequestProperty("Accept-Language", "vi-VN,vi;q=0.9,en;q=0.8");
        conn.setRequestProperty("Accept", "text/html,application/rss+xml,application/xml,*/*");
        conn.setRequestProperty("Accept-Encoding", "gzip");
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " for " + address);
            }
            InputStream in = conn.getInputStream();
            if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) {
                in = new GZIPInputStream(in);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
            in.close();
            return out.toString("UTF-8");
        } finally {
            conn.disconnect();
        }
    }
}
