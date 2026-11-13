package vn.herotran.launcher;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Xang PVOIL chuan + Vang Giavang.net chuan.
 *
 * Xang: PVOIL chinh thuc — https://www.pvoil.com.vn/tin-gia-xang-dau
 *   Bang PVOIL: TT | Mat hang | Gia dieu chinh luc HH:MM ngay DD/MM/YYYY (Dong/lit thuc te)
 *   Vi du 31/10/2026: E10 RON 95-III 24.150, E5 RON 92-II 24.220,
 *   Dau DO 0,05S-II 30.240, Dau DO 0,001S-V 31.640.
 *   Gia xang KHONG bi coi la cu neu ngay cap nhat != hom nay, vi xang chi doi
 *   theo ky dieu hanh.
 *
 * Vang: Giavang.net — https://giavang.net/
 *   Trang chu giavang.net load bang JS nen so trong HTML tinh bi rong.
 *   Khong lay so tu trang chu, lay tu RSS WordPress: https://giavang.net/feed/
 *   RSS co <pubDate> + <content:encoded> chua full bai viet, lay duoc so ngay
 *   ma khong can mo them link bai.
 *   Vi du bai sang 13/11: "Gia vang sang 13/11: The gioi hoi lai moc $3900 - vang noi
 *   sut 1 trieu dong" — SJC mieng: "dua gia giao dich ve muc 135,0 trieu
 *   (mua vao) va 138,0 trieu (ban ra)", Nhan SJC 9999: "133,8 - 137,8 trieu",
 *   The gioi: "tren nguong 3.900 USD/oz".
 *
 * Luat gia cu (chi ap cho VANG, khong ap cho xang):
 *   1. Neu giavang.net khong co bai gia vang ngay hom nay -> coi la gia cu.
 *   2. Khi do tra ve trang thai cho: tat ca cac o vang = "Updating...",
 *      waitingGold = true, khong lay VTC / CafeF lam fallback nua.
 *   3. Khi waitingGold, load() chi cache 5 phut, sau 5 phut tu fetch lai
 *      cho den khi co gia ngay moi.
 *
 * Mat mang / khong co du lieu: khong tra ve so mau tu che, tra "Updating...".
 */
public final class MarketService {

    // ---------- URL nguon ----------
    private static final String PVOIL_FUEL_URL = "https://www.pvoil.com.vn/tin-gia-xang-dau";
    private static final String GIAVANG_RSS = "https://giavang.net/feed/";

    // ---------- Pattern chung ----------
    private static final Pattern ITEM = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile("<title>(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</title>", Pattern.DOTALL);
    private static final Pattern LINK = Pattern.compile("<link>(.*?)</link>", Pattern.DOTALL);
    private static final Pattern PUBDATE = Pattern.compile("<pubDate>(.*?)</pubDate>", Pattern.DOTALL);
    private static final Pattern CONTENT_ENCODED = Pattern.compile("<content:encoded><!\\[CDATA\\[(.*?)\\]\\]></content:encoded>", Pattern.DOTALL);
    private static final Pattern DATE_FROM_PUBDATE = Pattern.compile("(\\d{1,2})\\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\s+(\\d{4})\\s+(\\d{1,2}):(\\d{2})");
    private static final Pattern HREF = Pattern.compile("href=[\"']([^\"']*gia-vang[^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_DDMMYYYY = Pattern.compile("(\\d{1,2}/\\d{1,2}/\\d{4})");
    private static final Pattern DATE_DDMM = Pattern.compile("(\\d{1,2}/\\d{1,2})");
    private static final Pattern SCRIPT_STYLE_PATTERN = Pattern.compile("(?s)<script.*?</script>|<style.*?</style>");
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    // ---------- Xang — PVOIL chinh thuc, bang "Tin gia xang dau" ----------
    // Sau stripHtml: "1 Xang E10 RON 95-III 24.150 đ -1580 2 Xang E5 RON 92-II 24.220 đ -1800
    //  3 Dau DO 0,05S-II 30.240 đ 0 4 Dau DO 0,001S-V 31.640 đ 0"
    private static final Pattern FUEL_UPDATE = Pattern.compile(
            "Giá điều chỉnh lúc[^0-9]*[\d:]+ ngày\s*(\d{1,2}/\d{1,2}/\d{4})");
    private static final Pattern FUEL_E5 = Pattern.compile(
            "Xăng E5 RON 92-II\s*(\d{2}\.\d{3})");
    private static final Pattern FUEL_E10 = Pattern.compile(
            "Xăng E10 RON 95-III\s*(\d{2}\.\d{3})");
    private static final Pattern FUEL_DIESEL = Pattern.compile(
            "Dầu DO 0[,.]05S-II\s*(\d{2}\.\d{3})");

    // ---------- Vang — Giavang.net (qua RSS feed, lay trong <content:encoded>) ----------
    private static final Pattern GIAVANG_SJC_BUY_SELL = Pattern.compile(
            "(?i)đưa giá giao dịch về mức\s*([\d,]+)\s*triệu.{0,120}?mua vào.{0,120}?([\d,]+)\s*triệu.{0,120}?bán ra", Pattern.DOTALL);
    private static final Pattern GIAVANG_SJC_RANGE = Pattern.compile(
            "(?i)vàng miếng SJC.{0,500}?([\d,]+)\s*[–-]\s*([\d,]+)\s*triệu", Pattern.DOTALL);
    private static final Pattern GIAVANG_SJC_RANGE2 = Pattern.compile(
            "(?i)SJC.{0,300}?([\d,]+)\s*[–-]\s*([\d,]+)\s*triệu", Pattern.DOTALL);
    private static final Pattern GIAVANG_NHAN = Pattern.compile(
            "(?i)SJC nhẫn 9999.{0,300}?([\d,]+)\s*[–-]\s*([\d,]+)\s*triệu", Pattern.DOTALL);
    private static final Pattern GIAVANG_NHAN2 = Pattern.compile(
            "(?i)nhẫn SJC.{0,300}?([\d,]+)\s*[–-]\s*([\d,]+)\s*triệu", Pattern.DOTALL);
    private static final Pattern GIAVANG_NHAN3 = Pattern.compile(
            "(?i)vàng nhẫn.{0,400}?([\d,]+)\s*[–-]\s*([\d,]+)\s*triệu", Pattern.DOTALL);
    private static final Pattern WORLD_GOLD = Pattern.compile(
            "(?i)(?:XAU/USD|giá vàng thế giới|thị trường vàng thế giới).{0,300}?([\d.,]+)\s*USD/(?:ounce|oz)", Pattern.DOTALL);
    private static final Pattern WORLD_GOLD_FALLBACK = Pattern.compile(
            "([\d.,]+)\s*USD/(?:ounce|oz)");

    // ---------- TTL ----------
    private static final long DATA_TTL_MS = 2 * 60 * 60 * 1000L;      // 2 gio neu du lieu day du
    private static final long WAITING_RETRY_MS = 5 * 60 * 1000L;      // 5 phut neu dang "Cho gia moi nhat"
    private static final long NO_DATA_RETRY_MS = 5 * 60 * 1000L;      // 5 phut neu mat mang

    public static final String NO_DATA = "Updating...";
    public static final String WAITING = "Updating...";

    private static volatile Market cachedMarket;
    private static volatile long lastFetchTime = 0;
    private static final AtomicBoolean isFetching = new AtomicBoolean(false);

    public interface Callback {
        void onMarket(Market market);
    }

    public static final class Market {
        // Cot Gia xang dau (dong/lit thuc te, PVOIL)
        public String e5;
        public String e10;
        public String diesel;

        // Cot Gia vang (trieu dong/luong & USD/oz)
        public String sjcBuy;
        public String sjcSell;
        public String nhanBuy;
        public String nhanSell;
        public String worldGold;

        // Chan the
        public String date;       // ngay vang neu co, neu khong thi ngay xang
        public String fuelDate;   // ngay dieu hanh xang, vi du "12/11/2026"
        public String goldDate;   // ngay bai vang, vi du "13/11/2026"
        public String source;     // "PVOIL / SJC (Giavang.net)" | ...
        public boolean sample;    // luon false, giu de tuong thich code cu — khong con du lieu mau
        public boolean waitingGold; // true = vang dang "Cho gia moi nhat", can fetch lai sau 5 phut
    }

    private MarketService() {
    }

    /** Goi tren luong nen (co kiem tra cache TTL), tra ket qua tren luong chinh. */
    public static void load(final Callback callback) {
        final Handler main = new Handler(Looper.getMainLooper());
        final Market currentCache = cachedMarket;
        final long now = SystemClock.elapsedRealtime();

        if (currentCache != null) {
            long ttl;
            if (currentCache.waitingGold) {
                ttl = WAITING_RETRY_MS;
            } else if (isNoData(currentCache)) {
                ttl = NO_DATA_RETRY_MS;
            } else {
                ttl = DATA_TTL_MS;
            }
            if ((now - lastFetchTime) < ttl) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onMarket(currentCache);
                    }
                });
                return;
            }
        }

        if (!isFetching.compareAndSet(false, true)) {
            if (currentCache != null) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onMarket(currentCache);
                    }
                });
            }
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                Market result;
                try {
                    result = fetch();
                } catch (Exception e) {
                    // Mat mang: giu cache cu neu co, neu khong co thi "Chua co du lieu", khong che so mau.
                    if (cachedMarket != null) {
                        result = cachedMarket;
                    } else {
                        result = noData();
                    }
                } finally {
                    isFetching.set(false);
                }
                cachedMarket = result;
                lastFetchTime = SystemClock.elapsedRealtime();
                final Market out = result;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onMarket(out);
                    }
                });
            }
        }).start();
    }

    private static Market fetch() throws Exception {
        String today = todayDdMmYyyy(); // vi du "13/11/2026"

        // ===== 1. XANG — PVOIL (luon lay, khong xet gia cu) =====
        String e5 = null, e10 = null, diesel = null, fuelDate = "";
        try {
            String fuelHtml = get(PVOIL_FUEL_URL);
            String fuelText = stripHtml(fuelHtml);
            e5 = firstGroup(FUEL_E5, fuelText);
            e10 = firstGroup(FUEL_E10, fuelText);
            diesel = firstGroup(FUEL_DIESEL, fuelText);
            fuelDate = firstGroup(FUEL_UPDATE, fuelText);
            if (fuelDate == null) fuelDate = "";
        } catch (Exception ignored) {
        }

        // ===== 2. VANG — Giavang.net (nguon duy nhat, khong fallback VTC / CafeF) =====
        GoldResult gold = fetchGiavangGold(today);
        String goldSource = null;
        if (gold != null && gold.fresh && gold.complete()) {
            goldSource = "SJC (Giavang.net)";
        } else {
            gold = null;
        }

        // ===== 4. Ghep ket qua =====
        Market m = new Market();
        m.sample = false;
        m.fuelDate = fuelDate;

        // Xang: co thi lay, khong co thi "Chua co du lieu" (khong che so)
        m.e5 = (e5 != null) ? e5 : NO_DATA;
        m.e10 = (e10 != null) ? e10 : NO_DATA;
        m.diesel = (diesel != null) ? diesel : NO_DATA;

        if (gold != null) {
            m.sjcBuy = gold.sjcBuy;
            m.sjcSell = gold.sjcSell;
            m.nhanBuy = gold.nhanBuy;
            m.nhanSell = gold.nhanSell;
            m.worldGold = (gold.worldGold != null) ? gold.worldGold : NO_DATA;
            m.goldDate = gold.date;
            m.date = gold.date;
            m.waitingGold = false;
            m.source = "PVOIL / " + goldSource;
        } else {
            // Giavang.net khong co bai gia vang ngay hom nay -> cho gia moi, fetch lai sau 5 phut
            m.sjcBuy = WAITING;
            m.sjcSell = WAITING;
            m.nhanBuy = WAITING;
            m.nhanSell = WAITING;
            m.worldGold = WAITING;
            m.goldDate = "";
            m.date = today;
            m.waitingGold = true;
            m.source = "PVOIL / Updating...";
        }

        // Neu ca xang va vang deu khong co gi (mat mang hoan toan) -> "Chua co du lieu"
        if (e5 == null && e10 == null && diesel == null && gold == null
                && cachedMarket == null) {
            // Van giu xang la "Chua co du lieu", vang la "Cho gia moi nhat" thi
            // UI se hieu la dang cho vang, trong khi thuc ra la mat mang.
            // Nen truong hop nay uu tien "Chua co du lieu" cho vang de dung nghia hon,
            // chi khi da co xang moi de vang la "Cho gia moi nhat".
            // O day ta giu nguyen logic tren vi da co phan biet waitingGold,
            // UI co the dua vao waitingGold de hien thi dung.
        }
        return m;
    }

    // ---------- Vang Giavang.net ----------
    private static GoldResult fetchGiavangGold(String today) {
        String rss;
        try {
            rss = get(GIAVANG_RSS);
        } catch (Exception e) {
            return null;
        }
        Matcher items = ITEM.matcher(rss);
        while (items.find()) {
            String item = items.group(1);
            String title = firstGroup(TITLE, item);
            String link = firstGroup(LINK, item);
            String pubDate = firstGroup(PUBDATE, item);
            if (title == null) continue;
            if (!title.contains("Giá vàng")) continue;
            String itemDate = pubDateToVietnamDate(pubDate);
            boolean isToday = today.equals(itemDate)
                    || (title.contains(todayDayMonthSlash(today)) && title.contains("Giá vàng"))
                    || (link != null && link.contains(todayDayMonthForLink(today)));
            if (!isToday) continue;
            String content = firstGroup(CONTENT_ENCODED, item);
            String text = null;
            if (content != null && !content.trim().isEmpty()) {
                text = stripHtml(content);
            }
            GoldResult r = parseGiavangText(text, today);
            if (r != null) return r;
            if (link != null && !link.trim().isEmpty()) {
                try {
                    String article = stripHtml(get(link.trim()));
                    GoldResult r2 = parseGiavangText(article, today);
                    if (r2 != null) return r2;
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private static GoldResult parseGiavangText(String text, String today) {
        if (text == null || text.isEmpty()) return null;
        String sjcBuy = null, sjcSell = null;
        Matcher m1 = GIAVANG_SJC_BUY_SELL.matcher(text);
        if (m1.find()) {
            sjcBuy = normalizeTrieu(m1.group(1));
            sjcSell = normalizeTrieu(m1.group(2));
        } else {
            Matcher m2 = GIAVANG_SJC_RANGE.matcher(text);
            if (m2.find()) {
                sjcBuy = normalizeTrieu(m2.group(1));
                sjcSell = normalizeTrieu(m2.group(2));
            } else {
                Matcher m3 = GIAVANG_SJC_RANGE2.matcher(text);
                if (m3.find()) {
                    sjcBuy = normalizeTrieu(m3.group(1));
                    sjcSell = normalizeTrieu(m3.group(2));
                }
            }
        }
        if (sjcBuy == null) return null;
        String nhanBuy = null, nhanSell = null;
        Matcher n1 = GIAVANG_NHAN.matcher(text);
        if (n1.find()) {
            nhanBuy = normalizeTrieu(n1.group(1));
            nhanSell = normalizeTrieu(n1.group(2));
        } else {
            Matcher n2 = GIAVANG_NHAN2.matcher(text);
            if (n2.find()) {
                nhanBuy = normalizeTrieu(n2.group(1));
                nhanSell = normalizeTrieu(n2.group(2));
            } else {
                Matcher n3 = GIAVANG_NHAN3.matcher(text);
                if (n3.find()) {
                    nhanBuy = normalizeTrieu(n3.group(1));
                    nhanSell = normalizeTrieu(n3.group(2));
                }
            }
        }
        if (nhanBuy == null) return null;
        GoldResult r = new GoldResult();
        r.sjcBuy = sjcBuy;
        r.sjcSell = sjcSell;
        r.nhanBuy = nhanBuy;
        r.nhanSell = nhanSell;
        r.date = today;
        r.fresh = true;
        Matcher mWorld = WORLD_GOLD.matcher(text);
        if (mWorld.find()) {
            r.worldGold = mWorld.group(1).trim();
        } else {
            Matcher mW2 = WORLD_GOLD_FALLBACK.matcher(text);
            if (mW2.find()) r.worldGold = mW2.group(1).trim();
        }
        return r;
    }

    // "Fri, 13 Nov 2026 05:09:59 +0000" (GMT) -> "13/11/2026" (gio VN = GMT+7).
    private static String pubDateToVietnamDate(String pubDate) {
        if (pubDate == null) return "";
        Matcher m = DATE_FROM_PUBDATE.matcher(pubDate);
        if (!m.find()) return "";
        int day = Integer.parseInt(m.group(1));
        int month = monthToNumber(m.group(2));
        int year = Integer.parseInt(m.group(3));
        int hour = Integer.parseInt(m.group(4));
        if (month == 0) return "";
        int vnHour = hour + 7;
        int vnDay = day;
        int vnMonth = month;
        int vnYear = year;
        if (vnHour >= 24) {
            vnHour -= 24;
            vnDay += 1;
            int daysInMonth = daysInMonth(vnMonth, vnYear);
            if (vnDay > daysInMonth) {
                vnDay = 1;
                vnMonth += 1;
                if (vnMonth > 12) {
                    vnMonth = 1;
                    vnYear += 1;
                }
            }
        }
        return vnDay + "/" + vnMonth + "/" + vnYear;
    }

    private static int monthToNumber(String mon) {
        if (mon == null) return 0;
        if (mon.equals("Jan")) return 1;
        if (mon.equals("Feb")) return 2;
        if (mon.equals("Mar")) return 3;
        if (mon.equals("Apr")) return 4;
        if (mon.equals("May")) return 5;
        if (mon.equals("Jun")) return 6;
        if (mon.equals("Jul")) return 7;
        if (mon.equals("Aug")) return 8;
        if (mon.equals("Sep")) return 9;
        if (mon.equals("Oct")) return 10;
        if (mon.equals("Nov")) return 11;
        if (mon.equals("Dec")) return 12;
        return 0;
    }

    private static int daysInMonth(int month, int year) {
        switch (month) {
            case 1: case 3: case 5: case 7: case 8: case 10: case 12: return 31;
            case 4: case 6: case 9: case 11: return 30;
            case 2: return ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) ? 29 : 28;
            default: return 30;
        }
    }

    // "13/11/2026" -> "13/11" de so voi tieu de dang "Gia vang sang 13/11 ..."
    private static String todayDayMonthSlash(String today) {
        try {
            String[] p = today.split("/");
            return Integer.parseInt(p[0]) + "/" + Integer.parseInt(p[1]);
        } catch (Exception e) {
            return "";
        }
    }

    // ---------- Tien ich ----------

    private static Market noData() {
        Market m = new Market();
        m.sample = false;
        m.waitingGold = false;
        m.e5 = NO_DATA;
        m.e10 = NO_DATA;
        m.diesel = NO_DATA;
        m.sjcBuy = NO_DATA;
        m.sjcSell = NO_DATA;
        m.nhanBuy = NO_DATA;
        m.nhanSell = NO_DATA;
        m.worldGold = NO_DATA;
        m.date = "--/--";
        m.fuelDate = "";
        m.goldDate = "";
        m.source = NO_DATA;
        return m;
    }

    /** Du lieu mau cu — giu ten ham de code cu khong loi, nhung khong con tra so tu che. */
    public static Market sample() {
        return noData();
    }

    private static boolean isNoData(Market m) {
        return m != null && NO_DATA.equals(m.e5) && NO_DATA.equals(m.sjcBuy);
    }

    private static String todayDdMmYyyy() {
        SimpleDateFormat f = new SimpleDateFormat("d/M/yyyy", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
        return f.format(Calendar.getInstance(TimeZone.getTimeZone("Asia/Ho_Chi_Minh")).getTime());
    }

    // Chuan hoa "13/11" -> "13/11/2026" de so sanh voi today "13/11/2026"
    private static String normalizeDate(String d) {
        if (d == null || d.isEmpty()) return "";
        d = d.trim();
        if (d.matches("\\d{1,2}/\\d{1,2}")) {
            int year = Calendar.getInstance(TimeZone.getTimeZone("Asia/Ho_Chi_Minh")).get(Calendar.YEAR);
            return d + "/" + year;
        }
        // "13/11/2026" co the co so 0 thua, chuan hoa ve dang khong co so 0 dau
        try {
            String[] p = d.split("/");
            int dd = Integer.parseInt(p[0]);
            int mm = Integer.parseInt(p[1]);
            int yy = Integer.parseInt(p[2]);
            return dd + "/" + mm + "/" + yy;
        } catch (Exception e) {
            return d;
        }
    }

    // "13/11/2026" -> "13-11" de tim trong link Giavang.net dang "gia-vang-sang-13-11-..."
    private static String todayDayMonthForLink(String today) {
        try {
            String[] p = today.split("/");
            return Integer.parseInt(p[0]) + "-" + Integer.parseInt(p[1]);
        } catch (Exception e) {
            return "";
        }
    }

    // "136.000.000" dong/luong -> "136,0" trieu
    private static String dongPerLuongToTrieu(String s) {
        try {
            long v = Long.parseLong(s.replace(".", "").replace(",", "").trim());
            double trieu = v / 1000000.0;
            return formatTrieu(trieu);
        } catch (Exception e) {
            return null;
        }
    }

    // "13.480.000" dong/chi -> "134,8" trieu (1 luong = 10 chi)
    private static String dongPerChiToTrieu(String s) {
        try {
            long v = Long.parseLong(s.replace(".", "").replace(",", "").trim());
            double trieu = v * 10.0 / 1000000.0;
            return formatTrieu(trieu);
        } catch (Exception e) {
            return null;
        }
    }

    // "135,0" -> "135,0" ; "135.0" -> "135,0"
    private static String normalizeTrieu(String s) {
        if (s == null) return null;
        return s.trim().replace(".", ",");
    }

    private static String formatTrieu(double v) {
        String s = String.format(Locale.US, "%.1f", v);
        return s.replace(".", ",");
    }

    private static String dateFromTitle(String title) {
        Matcher m = DATE_PATTERN.matcher(title);
        return m.find() ? m.group(1) : "";
    }

    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{1,2}/\\d{1,2})");

    private static String stripHtml(String html) {
        String s = SCRIPT_STYLE_PATTERN.matcher(html).replaceAll(" ");
        s = HTML_TAG_PATTERN.matcher(s).replaceAll(" ").replace("&nbsp;", " ");
        return WHITESPACE_PATTERN.matcher(s).replaceAll(" ");
    }

    private static String firstGroup(Pattern p, String s) {
        if (s == null) return null;
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String get(String address) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(address).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        // PVOIL / Giavang.net hay chan neu khong co Accept-Language
        conn.setRequestProperty("Accept-Language", "vi-VN,vi;q=0.9");
        try {
            InputStream in = conn.getInputStream();
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
