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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * XANG: PVOIL chinh thuc — https://www.pvoil.com.vn/tin-gia-xang-dau
 *   Bang dung theo anh nguoi dung gui (12/11/2026 15:00):
 *     1 Xang E10 RON 95-III  19.840 d  -1310
 *     2 Xang E5 RON 92-II    19.390 d  -1500
 *     3 Dau DO 0,05S-II      26.820 d  -1870
 *     4 Dau DO 0,001S-V      28.620 d  -1470
 *   Luu y: dung tin cache trinh duyet cu (31/10: 24.150 / 24.220 / 30.240).
 *   Do la bang cu, khong phai bang hien tai.
 *
 * VANG: giavang.net chinh thuc — https://giavang.net/
 *   Lay tu bai viet moi nhat tren giavang.net + bang gia tren trang chu.
 *   Fallback VnExpress RSS chi khi giavang.net khong doc duoc, de khong hien Updating...
 *
 * Khi khong lay duoc thi tat ca cac cot deu la "Updating..." (duy nhat, khong "Chua co du lieu").
 */
public final class MarketService {

    public static final String NO_DATA = "Updating...";
    public static final String WAITING = "Updating...";

    private static final String RSS_URL = "https://vnexpress.net/rss/kinh-doanh.rss";

    // ---- Xang PVOIL ----
    private static final String PVOIL_FUEL_URL = "https://www.pvoil.com.vn/tin-gia-xang-dau";
    private static final String PVOIL_FUEL_URL_NOWWW = "https://pvoil.com.vn/tin-gia-xang-dau";
    private static final Pattern PVOIL_DATE = Pattern.compile("ngày\\s*(\\d{1,2}/\\d{1,2}/\\d{4})");
    // Cho phep bat ky ky tu giua ten va gia (ke ca NBSP da normalize), chap nhan 19.840 / 19,840
    private static final Pattern PVOIL_E10 = Pattern.compile("(?i)E10\\s*RON\\s*95[^0-9]{0,40}?(\\d{2}[.,]\\d{3})");
    private static final Pattern PVOIL_E5 = Pattern.compile("(?i)E5\\s*RON\\s*92[^0-9]{0,40}?(\\d{2}[.,]\\d{3})");
    private static final Pattern PVOIL_DIESEL = Pattern.compile("(?i)DO\\s*0[.,]05S[^0-9]{0,40}?(\\d{2}[.,]\\d{3})");
    private static final Pattern PVOIL_PRICE_LIST = Pattern.compile("(\\d{2}[.,]\\d{3})\\s*(?:đ|d)?\\b");

    // ---- Vang giavang.net ----
    private static final String GIAVANG_URL = "https://giavang.net/";
    private static final Pattern GIAVANG_ARTICLE_LINK = Pattern.compile("href\\s*=\\s*\"([^\"]*gia-vang[^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern GIAVANG_SJC_TABLE = Pattern.compile("(?i)SJC\\s*1\\s*L[^0-9]{0,80}?(\\d{2,3}[.,]\\d{3})[^0-9]{0,80}?(\\d{2,3}[.,]\\d{3})");
    private static final Pattern GIAVANG_NHAN_TABLE = Pattern.compile("(?i)NHAN\\s*SJC[^0-9]{0,80}?(\\d{2,3}[.,]\\d{3})[^0-9]{0,80}?(\\d{2,3}[.,]\\d{3})");
    private static final Pattern GIAVANG_WORLD_TABLE = Pattern.compile("(?i)XAUUSD[^0-9]{0,80}?(\\d[.,]\\d{3})");

    // ---- Vang (dung chung cho bai giavang.net + fallback VnExpress) ----
    // SJC mieng: "SJC ... 135,0 - 138,0 trieu" / "SJC ... 135 - 138 trieu"
    private static final Pattern SJC = Pattern.compile("(?i)vàng miếng.{0,200}?(?:tại\\s*)?(\\d[\\d.,]+)\\s*-\\s*(\\d[\\d.,]+)\\s*triệu", Pattern.DOTALL);
    private static final Pattern SJC_SIMPLE = Pattern.compile("(?i)SJC.{0,200}?(\\d{2,3}[.,]\\d)\\s*-\\s*(\\d{2,3}[.,]\\d)\\s*triệu", Pattern.DOTALL);
    private static final Pattern SJC_SIMPLE2 = Pattern.compile("(?i)SJC.{0,200}?(\\d{3})\\s*-\\s*(\\d{3})\\s*triệu", Pattern.DOTALL);
    private static final Pattern NHAN_SJC = Pattern.compile("(?i)(?:SJC.{0,250}?nhẫn tròn|nhẫn tròn.{0,250}?SJC).{0,250}?(?:tại|quanh|còn|mức|về)\\s*(\\d[\\d.,]+)\\s*-\\s*(\\d[\\d.,]+)\\s*triệu", Pattern.DOTALL);
    private static final Pattern NHAN_GENERIC = Pattern.compile("(?i)nhẫn tròn.{0,300}?(?:tại|quanh|còn|mức|về)\\s*(\\d[\\d.,]+)\\s*-\\s*(\\d[\\d.,]+)\\s*triệu", Pattern.DOTALL);
    private static final Pattern NHAN_SJC_SIMPLE = Pattern.compile("(?i)nhẫn SJC.{0,200}?(\\d{2,3}[.,]\\d)\\s*-\\s*(\\d{2,3}[.,]\\d)", Pattern.DOTALL);
    private static final Pattern WORLD_GOLD = Pattern.compile("(?i)(?:vàng thế giới giao ngay|giá vàng giao ngay|vàng thế giới).{0,220}?(?:còn|về|lên|xuống|đạt|dưới vùng)\\s*(?:còn|sát|gần)?\\s*(\\d[\\d.,]+)\\s*USD", Pattern.DOTALL);
    private static final Pattern WORLD_GOLD_SIMPLE = Pattern.compile("(?i)(\\d[.,]\\d{3})\\s*USD", Pattern.DOTALL);

    // ---- Xang fallback VnExpress (chi khi PVOIL loi) ----
    private static final Pattern ITEM = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile("<title>(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</title>", Pattern.DOTALL);
    private static final Pattern LINK = Pattern.compile("<link>(.*?)</link>", Pattern.DOTALL);
    private static final Pattern PUBDATE = Pattern.compile("<pubDate>(.*?)</pubDate>", Pattern.DOTALL);
    private static final Pattern DATE_FROM_URL = Pattern.compile("-(\\d{1,2})-(\\d{1,2})-\\d+\\.html");
    private static final Pattern DATE_FROM_PUBDATE = Pattern.compile("(\\d{1,2})\\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\s+\\d{4}");
    private static final Pattern SCRIPT_STYLE_PATTERN = Pattern.compile("(?s)<script.*?</script>|<style.*?</style>");
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern FUEL_TABLE = Pattern.compile("(?i)E10\\s?RON\\s?95-III\\s*,\\s*([\\d.]+)\\s*đ/lít.*?E5\\s?RON\\s?92\\s*,\\s*([\\d.]+)\\s*đ/lít.*?Diesel\\s*,\\s*([\\d.]+)\\s*đ/lít", Pattern.DOTALL);
    private static final Pattern FUEL_E10_BODY = Pattern.compile("(?i)E10\\s?RON\\s?95-III.{0,180}?(?:xuống|lên|ở mức|còn)\\s*([\\d.]+)\\s*đồng", Pattern.DOTALL);
    private static final Pattern FUEL_E5_BODY = Pattern.compile("(?i)E5\\s?RON\\s?92.{0,180}?(?:còn|ở mức|xuống|lên)\\s*([\\d.]+)\\s*đồng", Pattern.DOTALL);
    private static final Pattern FUEL_DIESEL_BODY = Pattern.compile("(?i)diesel.{0,180}?(?:giá mới|có giá mới|ở mức)\\s*([\\d.]+)\\s*đồng", Pattern.DOTALL);

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
        // 1. XANG: PVOIL truoc
        Market pvoil = null;
        try { pvoil = fetchPvoilFuel(); } catch (Exception ignored) {}
        // 2. VANG: giavang.net truoc
        Market gv = null;
        try { gv = fetchGiavangGold(); } catch (Exception ignored) {}

        String e5 = (pvoil != null) ? pvoil.e5 : null;
        String e10 = (pvoil != null) ? pvoil.e10 : null;
        String diesel = (pvoil != null) ? pvoil.diesel : null;
        String fuelDate = (pvoil != null) ? pvoil.date : "";

        String sjcBuy = (gv != null) ? gv.sjcBuy : null;
        String sjcSell = (gv != null) ? gv.sjcSell : null;
        String nhanBuy = (gv != null) ? gv.nhanBuy : null;
        String nhanSell = (gv != null) ? gv.nhanSell : null;
        String worldGold = (gv != null) ? gv.worldGold : null;
        String goldDate = (gv != null) ? gv.date : "";

        // 3. Fallback VnExpress chi cho phan nao con thieu, de khong ve Updating...
        if (e5 == null || sjcBuy == null) {
            try {
                Market vx = fetchVnExpressFallback(e5 == null, sjcBuy == null);
                if (e5 == null && vx.e5 != null) {
                    e5 = vx.e5; e10 = vx.e10; diesel = vx.diesel;
                    if (fuelDate.isEmpty()) fuelDate = vx.date;
                }
                if (sjcBuy == null && vx.sjcBuy != null) {
                    sjcBuy = vx.sjcBuy; sjcSell = vx.sjcSell;
                    nhanBuy = vx.nhanBuy; nhanSell = vx.nhanSell;
                    worldGold = vx.worldGold;
                    if (goldDate.isEmpty()) goldDate = vx.date;
                }
            } catch (Exception ignored) {}
        }

        if (e5 == null && sjcBuy == null) {
            throw new IllegalStateException("Khong lay duoc PVOIL + giavang.net + VnExpress");
        }

        Market base = (cachedMarket != null && !cachedMarket.empty) ? cachedMarket : null;
        Market m = new Market();
        m.e5 = (e5 != null) ? e5 : (base != null ? base.e5 : NO_DATA);
        m.e10 = (e10 != null) ? e10 : (base != null ? base.e10 : NO_DATA);
        m.diesel = (diesel != null) ? diesel : (base != null ? base.diesel : NO_DATA);
        m.sjcBuy = (sjcBuy != null) ? sjcBuy : (base != null ? base.sjcBuy : NO_DATA);
        m.sjcSell = (sjcSell != null) ? sjcSell : (base != null ? base.sjcSell : NO_DATA);
        m.nhanBuy = (nhanBuy != null) ? nhanBuy : (base != null ? base.nhanBuy : NO_DATA);
        m.nhanSell = (nhanSell != null) ? nhanSell : (base != null ? base.nhanSell : NO_DATA);
        m.worldGold = (worldGold != null) ? worldGold : (base != null ? base.worldGold : NO_DATA);
        m.date = !goldDate.isEmpty() ? goldDate : (!fuelDate.isEmpty() ? fuelDate : (base != null ? base.date : "--/--"));
        // Ghi ro nguon de ban kiem tra: xang tu PVOIL, vang tu giavang.net
        if (pvoil != null && gv != null) m.source = "PVOIL / GiaVang.Net";
        else if (pvoil != null) m.source = "PVOIL / VnExpress";
        else if (gv != null) m.source = "VnExpress / GiaVang.Net";
        else m.source = "VnExpress";
        m.sample = false;
        m.empty = false;
        return m;
    }

    // ---------------- XANG PVOIL ----------------
    private static Market fetchPvoilFuel() {
        String html = null;
        String[] urls = new String[]{PVOIL_FUEL_URL, PVOIL_FUEL_URL_NOWWW, PVOIL_FUEL_URL + "/"};
        for (String u : urls) {
            try {
                html = get(u);
                if (html != null && html.length() > 1000) break;
            } catch (Exception ignored) {}
        }
        if (html == null) return null;
        String text = stripHtml(html);
        // Cach 1: match theo ten — dung cho bang 12/11: "Xang E10 RON 95-III 19.840 d -1310"
        String e10 = firstGroup(PVOIL_E10, text);
        String e5 = firstGroup(PVOIL_E5, text);
        String diesel = firstGroup(PVOIL_DIESEL, text);
        if (isValidFuel(e10) && isValidFuel(e5) && isValidFuel(diesel)) {
            Market m = new Market();
            m.e10 = e10.replace(',', '.');
            m.e5 = e5.replace(',', '.');
            m.diesel = diesel.replace(',', '.');
            m.date = pvoilDate(text);
            return m;
        }
        // Cach 2: theo thu tu bang PVOIL: 1 E10, 2 E5, 3 DO 0,05S, 4 DO 0,001S
        // Lay 4 gia dau tien dang 19.840, bo qua chenh lech -1310 (khong co dau cham)
        try {
            Matcher pm = PVOIL_PRICE_LIST.matcher(text);
            ArrayList<String> prices = new ArrayList<>();
            while (pm.find() && prices.size() < 4) {
                String v = pm.group(1).replace(',', '.');
                if (isValidFuel(v) && !prices.contains(v)) prices.add(v);
            }
            if (prices.size() >= 3) {
                Market m = new Market();
                m.e10 = prices.get(0);
                m.e5 = prices.get(1);
                m.diesel = prices.get(2);
                m.date = pvoilDate(text);
                return m;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static boolean isValidFuel(String v) {
        if (v == null) return false;
        try {
            double d = Double.parseDouble(v.replace(',', '.'));
            return d >= 10.0 && d <= 60.0;
        } catch (Exception e) { return false; }
    }

    private static String pvoilDate(String text) {
        String d = firstGroup(PVOIL_DATE, text);
        if (d == null) return "--/--";
        String[] pp = d.split("/");
        try { return Integer.parseInt(pp[0]) + "/" + Integer.parseInt(pp[1]); } catch (Exception e) { return d; }
    }

    // ---------------- VANG GIAVANG.NET ----------------
    private static Market fetchGiavangGold() {
        String home = null;
        try { home = get(GIAVANG_URL); } catch (Exception ignored) {}
        if (home == null) return null;

        // A. Thu bai viet moi nhat tren giavang.net (vd: "Gia vang sang 13/11: SJC 135,0 - 138,0 trieu")
        try {
            Matcher lm = GIAVANG_ARTICLE_LINK.matcher(home);
            ArrayList<String> links = new ArrayList<>();
            while (lm.find() && links.size() < 5) {
                String href = lm.group(1);
                if (href.startsWith("/")) href = "https://giavang.net" + href;
                else if (!href.startsWith("http")) href = "https://giavang.net/" + href;
                if (!links.contains(href)) links.add(href);
            }
            for (String link : links) {
                try {
                    String article = stripHtml(get(link));
                    Market m = parseGoldText(article);
                    if (m != null) {
                        // Ngay tu bai viet: tim "13/11" trong text, neu khong co thi de "--/--"
                        Matcher dm = Pattern.compile("(\\d{1,2}/\\d{1,2})").matcher(article);
                        if (dm.find()) m.date = dm.group(1);
                        return m;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        // B. Thu bang gia tren trang chu giavang.net
        try {
            String text = stripHtml(home);
            Market m = parseGoldTable(text);
            if (m != null) return m;
            // C. Thu trong HTML tho (JSON trong script) — so dang 135000000
            Market m2 = parseGoldRawNumbers(home);
            if (m2 != null) return m2;
        } catch (Exception ignored) {}
        return null;
    }

    // Parse kieu "135,0 - 138,0 trieu" trong bai viet
    private static Market parseGoldText(String text) {
        if (text == null) return null;
        String sjcBuy = null, sjcSell = null;
        Matcher ms = SJC.matcher(text);
        if (ms.find()) { sjcBuy = ms.group(1); sjcSell = ms.group(2); }
        else {
            Matcher ms2 = SJC_SIMPLE.matcher(text);
            if (ms2.find()) { sjcBuy = ms2.group(1); sjcSell = ms2.group(2); }
            else {
                Matcher ms3 = SJC_SIMPLE2.matcher(text);
                if (ms3.find()) { sjcBuy = ms3.group(1); sjcSell = ms3.group(2); }
            }
        }
        if (sjcBuy == null) return null;
        String nhanBuy = null, nhanSell = null;
        Matcher mn = NHAN_SJC.matcher(text);
        if (mn.find()) { nhanBuy = mn.group(1); nhanSell = mn.group(2); }
        else {
            Matcher mn2 = NHAN_GENERIC.matcher(text);
            if (mn2.find()) { nhanBuy = mn2.group(1); nhanSell = mn2.group(2); }
            else {
                Matcher mn3 = NHAN_SJC_SIMPLE.matcher(text);
                if (mn3.find()) { nhanBuy = mn3.group(1); nhanSell = mn3.group(2); }
            }
        }
        String world = null;
        Matcher mw = WORLD_GOLD.matcher(text);
        if (mw.find()) world = mw.group(1);
        else {
            Matcher mw2 = WORLD_GOLD_SIMPLE.matcher(text);
            if (mw2.find()) world = mw2.group(1);
        }
        Market m = new Market();
        m.sjcBuy = sjcBuy; m.sjcSell = sjcSell;
        m.nhanBuy = (nhanBuy != null) ? nhanBuy : NO_DATA;
        m.nhanSell = (nhanSell != null) ? nhanSell : NO_DATA;
        m.worldGold = (world != null) ? world : NO_DATA;
        m.date = "--/--";
        return m;
    }

    // Parse bang: "SJC 1 LUONG 135,000 138,000" -> 135,0 / 138,0
    private static Market parseGoldTable(String text) {
        Matcher ms = GIAVANG_SJC_TABLE.matcher(text);
        if (!ms.find()) return null;
        Market m = new Market();
        m.sjcBuy = toTrieu(ms.group(1));
        m.sjcSell = toTrieu(ms.group(2));
        Matcher mn = GIAVANG_NHAN_TABLE.matcher(text);
        if (mn.find()) { m.nhanBuy = toTrieu(mn.group(1)); m.nhanSell = toTrieu(mn.group(2)); }
        else { m.nhanBuy = NO_DATA; m.nhanSell = NO_DATA; }
        Matcher mw = GIAVANG_WORLD_TABLE.matcher(text);
        m.worldGold = mw.find() ? mw.group(1) : NO_DATA;
        m.date = "--/--";
        return m;
    }

    private static Market parseGoldRawNumbers(String raw) {
        // Tim so dang 135000000 (VND/luong) trong JSON cua trang
        try {
            Matcher m = Pattern.compile("135\\d{6}").matcher(raw);
            if (m.find()) {
                Market mk = new Market();
                mk.sjcBuy = "135,0";
                mk.sjcSell = "138,0";
                mk.nhanBuy = "133,8";
                mk.nhanSell = "137,8";
                mk.worldGold = NO_DATA;
                mk.date = "--/--";
                return null; // khong doan mo, tra null de fallback VnExpress lo
            }
        } catch (Exception ignored) {}
        return null;
    }

    // "135,000" (nghin/luong) -> "135,0" (trieu/luong). "135,0" giu nguyen.
    private static String toTrieu(String v) {
        if (v == null) return NO_DATA;
        v = v.trim().replace(',', '.');
        try {
            double d = Double.parseDouble(v);
            if (d > 1000) return String.format("%.1f", d / 1000.0).replace('.', ',');
            return v.replace('.', ',');
        } catch (Exception e) { return v; }
    }

    // ---------------- FALLBACK VNEXPRESS (chi khi PVOIL / giavang.net loi) ----------------
    private static Market fetchVnExpressFallback(boolean needFuel, boolean needGold) throws Exception {
        String rss = get(RSS_URL);
        Matcher items = ITEM.matcher(rss);
        String e5 = null, e10 = null, diesel = null, fuelDate = "";
        String sjcBuy = null, sjcSell = null, nhanBuy = null, nhanSell = null, worldGold = null, goldDate = "";
        while (items.find() && ((needFuel && e5 == null) || (needGold && sjcBuy == null))) {
            String item = items.group(1);
            String title = firstGroup(TITLE, item);
            String link = firstGroup(LINK, item);
            if (link == null) continue;
            String linkLower = link.toLowerCase();
            boolean isFuel = linkLower.contains("gia-xang");
            boolean isGold = linkLower.contains("gia-vang");
            if (!isFuel && !isGold) {
                if (title != null && title.contains("Giá xăng")) isFuel = true;
                else if (title != null && (title.contains("Giá vàng") || title.contains("Vàng miếng"))) isGold = true;
                else continue;
            }
            String dateForThis = dateFromLink(link);
            if (dateForThis.isEmpty()) dateForThis = dateFromPubDate(firstGroup(PUBDATE, item));
            if (isFuel && needFuel && e5 == null) {
                try {
                    String text = stripHtml(get(link.trim()));
                    Matcher table = FUEL_TABLE.matcher(text);
                    if (table.find()) {
                        e10 = table.group(1); e5 = table.group(2); diesel = table.group(3); fuelDate = dateForThis;
                    } else {
                        String pE10 = firstGroup(FUEL_E10_BODY, text);
                        String pE5 = firstGroup(FUEL_E5_BODY, text);
                        String pDiesel = firstGroup(FUEL_DIESEL_BODY, text);
                        if (pE10 != null && pE5 != null && pDiesel != null) {
                            e10 = pE10; e5 = pE5; diesel = pDiesel; fuelDate = dateForThis;
                        }
                    }
                } catch (Exception ignored) {}
            } else if (isGold && needGold && sjcBuy == null) {
                try {
                    String text = stripHtml(get(link.trim()));
                    Market gm = parseGoldText(text);
                    if (gm != null) {
                        sjcBuy = gm.sjcBuy; sjcSell = gm.sjcSell;
                        nhanBuy = gm.nhanBuy; nhanSell = gm.nhanSell;
                        worldGold = gm.worldGold; goldDate = dateForThis;
                    }
                } catch (Exception ignored) {}
            }
        }
        Market m = new Market();
        m.e5 = e5; m.e10 = e10; m.diesel = diesel;
        m.sjcBuy = sjcBuy; m.sjcSell = sjcSell; m.nhanBuy = nhanBuy; m.nhanSell = nhanSell; m.worldGold = worldGold;
        m.date = !goldDate.isEmpty() ? goldDate : fuelDate;
        return m;
    }

    public static Market empty() {
        Market m = new Market();
        m.sample = false;
        m.empty = true;
        m.date = "--/--";
        m.source = "PVOIL / GiaVang.Net";
        m.e5 = NO_DATA; m.e10 = NO_DATA; m.diesel = NO_DATA;
        m.sjcBuy = NO_DATA; m.sjcSell = NO_DATA;
        m.nhanBuy = NO_DATA; m.nhanSell = NO_DATA; m.worldGold = NO_DATA;
        return m;
    }

    @Deprecated
    public static Market sample() {
        return empty();
    }

    private static String dateFromLink(String link) {
        Matcher m = DATE_FROM_URL.matcher(link);
        return m.find() ? m.group(1) + "/" + m.group(2) : "";
    }

    private static String dateFromPubDate(String pubDate) {
        if (pubDate == null) return "";
        Matcher m = DATE_FROM_PUBDATE.matcher(pubDate);
        if (!m.find()) return "";
        int month = monthToNumber(m.group(2));
        return month == 0 ? "" : m.group(1) + "/" + month;
    }

    private static int monthToNumber(String mon) {
        if (mon == null) return 0;
        switch (mon) {
            case "Jan": return 1;
            case "Feb": return 2;
            case "Mar": return 3;
            case "Apr": return 4;
            case "May": return 5;
            case "Jun": return 6;
            case "Jul": return 7;
            case "Aug": return 8;
            case "Sep": return 9;
            case "Oct": return 10;
            case "Nov": return 11;
            case "Dec": return 12;
            default: return 0;
        }
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
