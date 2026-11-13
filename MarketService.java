package vn.herotran.launcher;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dịch vụ hợp nhất lấy cả Giá xăng dầu và Giá vàng hôm nay trong một lần quét RSS
 * VietNamNet (chuyên mục Kinh doanh). Có bộ nhớ đệm TTL và fallback dữ liệu mẫu độc lập cho từng cột.
 */
public final class MarketService {

    private static final String RSS_URL = "https://vietnamnet.vn/rss/kinh-doanh.rss";
    private static final Pattern ITEM = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile("<title>(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</title>", Pattern.DOTALL);
    private static final Pattern LINK = Pattern.compile("<link>(.*?)</link>", Pattern.DOTALL);
    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{1,2}/\\d{1,2})");
    private static final Pattern SCRIPT_STYLE_PATTERN = Pattern.compile("(?s)<script.*?</script>|<style.*?</style>");
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    // Regex Giá xăng dầu (chặn không vượt qua dấu chấm câu để tránh match chéo sang loại xăng dầu khác)
    private static final Pattern E5 = Pattern.compile(
            "(?i)E5\\s?RON\\s?92[^.]{0,120}?(?:không cao hơn|tối đa|mức)\\s*(\\d{2}\\.\\d{3})");
    private static final Pattern E10 = Pattern.compile(
            "(?i)(?:E10\\s?)?RON\\s?95(?:-III)?[^.]{0,120}?(?:không cao hơn|tối đa|mức)\\s*(\\d{2}\\.\\d{3})");
    private static final Pattern DIESEL = Pattern.compile(
            "(?i)diesel\\s*0?\\.05S[^.]{0,120}?(?:không cao hơn|tối đa|mức)\\s*(\\d{2}\\.\\d{3})");


    // --- FIX 2026-11-13: Pattern moi cho format truc tiep ---
    // Trong String Java, regex phai viet 2 gach: double-backslash + d / s / cham.
    // Viet 1 gach -> javac bao illegal escape character.
    // Khong dung text-block khi build voi release 8.
    private static final Pattern FUEL_DATE = Pattern.compile(
            "Giá điều chỉnh lúc[^0-9]*[\\d:]+ ngày\\s*(\\d{1,2}/\\d{1,2}/\\d{4})");
    private static final Pattern E5_DIRECT = Pattern.compile(
            "Xăng E5 RON 92-II\\s*(\\d{2}\\.\\d{3})");
    private static final Pattern E10_DIRECT = Pattern.compile(
            "Xăng E10 RON 95-III\\s*(\\d{2}\\.\\d{3})");
    private static final Pattern DIESEL_DIRECT = Pattern.compile(
            "Dầu DO 0[,.]05S-II\\s*(\\d{2}\\.\\d{3})");
    private static final Pattern GOLD_TRADE = Pattern.compile(
            "(?i)đưa giá giao dịch về mức\\s*([\\d,]+)\\s*triệu.{0,120}?mua vào.{0,120}?([\\d,]+)\\s*triệu.{0,120}?bán ra", Pattern.DOTALL);
    private static final Pattern SJC_DIRECT = Pattern.compile(
            "(?i)vàng miếng SJC.{0,500}?([\\d,]+)\\s*[–-]\\s*([\\d,]+)\\s*triệu", Pattern.DOTALL);
    private static final Pattern SJC_SHORT = Pattern.compile(
            "(?i)SJC.{0,300}?([\\d,]+)\\s*[–-]\\s*([\\d,]+)\\s*triệu", Pattern.DOTALL);
    private static final Pattern SJC_NHAN_9999 = Pattern.compile(
            "(?i)SJC nhẫn 9999.{0,300}?([\\d,]+)\\s*[–-]\\s*([\\d,]+)\\s*triệu", Pattern.DOTALL);
    private static final Pattern NHAN_SJC = Pattern.compile(
            "(?i)nhẫn SJC.{0,300}?([\\d,]+)\\s*[–-]\\s*([\\d,]+)\\s*triệu", Pattern.DOTALL);
    private static final Pattern NHAN_VANG = Pattern.compile(
            "(?i)vàng nhẫn.{0,400}?([\\d,]+)\\s*[–-]\\s*([\\d,]+)\\s*triệu", Pattern.DOTALL);
    private static final Pattern WORLD_GOLD_DIRECT = Pattern.compile(
            "(?i)(?:XAU/USD|giá vàng thế giới|thị trường vàng thế giới).{0,300}?([\\d.,]+)\\s*USD/(?:ounce|oz)", Pattern.DOTALL);
    private static final Pattern WORLD_GOLD_SHORT = Pattern.compile(
            "([\\d.,]+)\\s*USD/(?:ounce|oz)");

    // Regex Giá vàng:
    // 1. Vàng miếng SJC
    private static final Pattern SJC = Pattern.compile(
            "(?i)(?:vàng miếng SJC|vàng miếng của Công ty Vàng bạc đá quý Sài Gòn \\(SJC\\)).{0,220}?(?:mức|lên|xuống|còn)\\s*([\\d,.]+)\\s*-\\s*([\\d,.]+)\\s*triệu",
            Pattern.DOTALL);
    // 2. Vàng nhẫn trơn Bảo Tín Minh Châu (BTMC) + fallback nhẫn 9999 Doji/SJC nếu bài viết thiếu BTMC
    private static final Pattern NHAN_BTMC = Pattern.compile(
            "(?i)(?:vàng nhẫn trơn (?:của|tại) Bảo Tín Minh Châu|Bảo Tín Minh Châu niêm yết vàng nhẫn).{0,220}?(?:mức|lên|xuống|còn)\\s*([\\d,.]+)\\s*-\\s*([\\d,.]+)\\s*triệu",
            Pattern.DOTALL);
    private static final Pattern NHAN_FALLBACK = Pattern.compile(
            "(?i)(?:vàng nhẫn tròn ép vỉ 9999|vàng nhẫn 9999 (?:của|tại) (?:Tập đoàn )?Doji|vàng nhẫn (?:SJC )?loại 1-5 chỉ).{0,220}?(?:mức|lên|xuống|còn)\\s*([\\d,.]+)\\s*-\\s*([\\d,.]+)\\s*triệu",
            Pattern.DOTALL);
    // 3. Vàng thế giới (Kitco giao ngay USD/ounce)
    private static final Pattern WORLD_GOLD = Pattern.compile(
            "(?i)(?:giá vàng (?:thế giới )?giao ngay|Kitco, giá vàng thế giới).{0,180}?(?:mức|vùng|đạt|lên|xuống)\\s*([\\d.,]+)\\s*USD/ounce",
            Pattern.DOTALL);

    private static final long DATA_TTL_MS = 2 * 60 * 60 * 1000L; // 2 giờ cho dữ liệu thật
    private static final long SAMPLE_RETRY_MS = 2 * 60 * 1000L;  // 2 phút nếu dữ liệu mẫu

    private static volatile Market cachedMarket;
    private static volatile long lastFetchTime = 0;
    private static final AtomicBoolean isFetching = new AtomicBoolean(false);

    public interface Callback {
        void onMarket(Market market);
    }

    public static final class Market {
        // Cột Giá xăng dầu (đồng/lít)
        public String e5;
        public String e10;
        public String diesel;

        // Cột Giá vàng (triệu đồng/lượng & USD/oz)
        public String sjcBuy;
        public String sjcSell;
        public String nhanBuy;
        public String nhanSell;
        public String worldGold;

        // Thông tin chân thẻ chung
        public String date;      // ví dụ "28/10"
        public String source;    // "VietNamNet" hoặc "dữ liệu mẫu"
        public boolean sample;   // true nếu cả 2 cột đều đang là dữ liệu mẫu
    }

    private MarketService() {
    }

    /** Gọi trên luồng nền (có kiểm tra cache TTL), trả về kết quả hợp nhất trên luồng chính. */
    public static void load(final Callback callback) {
        final Handler main = new Handler(Looper.getMainLooper());
        final Market currentCache = cachedMarket;
        final long now = SystemClock.elapsedRealtime();

        if (currentCache != null) {
            long ttl = currentCache.sample ? SAMPLE_RETRY_MS : DATA_TTL_MS;
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
                    result = (cachedMarket != null && !cachedMarket.sample) ? cachedMarket : sample();
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
        String rss = get(RSS_URL);
        Matcher items = ITEM.matcher(rss);

        String e5 = null, e10 = null, diesel = null, fuelDate = "";
        String sjcBuy = null, sjcSell = null, nhanBuy = null, nhanSell = null, worldGold = null, goldDate = "";

        while (items.find() && (e5 == null || sjcBuy == null)) {
            String item = items.group(1);
            String title = firstGroup(TITLE, item);
            if (title == null) continue;

            if (e5 == null && title.contains("Giá xăng dầu hôm nay")) {
                String link = firstGroup(LINK, item);
                if (link != null) {
                    try {
                        String text = stripHtml(get(link.trim()));
                        String pE5 = firstGroup(E5, text);
                        String pE10 = firstGroup(E10, text);
                        String pDiesel = firstGroup(DIESEL, text);
                        // Fallback format truc tiep: Xang E5 RON 92-II 19.123
                        if (pE5 == null) pE5 = firstGroup(E5_DIRECT, text);
                        if (pE10 == null) pE10 = firstGroup(E10_DIRECT, text);
                        if (pDiesel == null) pDiesel = firstGroup(DIESEL_DIRECT, text);
                        if (pE5 != null && pE10 != null && pDiesel != null) {
                            e5 = pE5;
                            e10 = pE10;
                            diesel = pDiesel;
                            fuelDate = dateFromTitle(title);
                        }
                    } catch (Exception ignored) {
                    }
                }
            } else if (sjcBuy == null && title.contains("Giá vàng hôm nay")) {
                String link = firstGroup(LINK, item);
                if (link != null) {
                    try {
                        String text = stripHtml(get(link.trim()));
                        Matcher mSjc = SJC.matcher(text);
                        Matcher mNhan = NHAN_BTMC.matcher(text);
                        if (!mNhan.find()) {
                            mNhan = NHAN_FALLBACK.matcher(text);
                        } else {
                            mNhan.reset();
                        }
                        Matcher mWorld = WORLD_GOLD.matcher(text);
                        // Fallback format truc tiep SJC / nhan
                        if (!mSjc.find()) {
                            mSjc = SJC_DIRECT.matcher(text);
                            if (!mSjc.find()) mSjc = SJC_SHORT.matcher(text);
                            if (!mSjc.find()) mSjc = GOLD_TRADE.matcher(text);
                        } else {
                            mSjc.reset();
                        }
                        if (!mNhan.find()) {
                            mNhan = SJC_NHAN_9999.matcher(text);
                            if (!mNhan.find()) mNhan = NHAN_SJC.matcher(text);
                            if (!mNhan.find()) mNhan = NHAN_VANG.matcher(text);
                        } else {
                            mNhan.reset();
                        }
                        if (!mWorld.find()) {
                            mWorld = WORLD_GOLD_DIRECT.matcher(text);
                            if (!mWorld.find()) mWorld = WORLD_GOLD_SHORT.matcher(text);
                        } else {
                            mWorld.reset();
                        }
                        if (mSjc.find() && mNhan.find()) {
                            sjcBuy = mSjc.group(1);
                            sjcSell = mSjc.group(2);
                            nhanBuy = mNhan.group(1);
                            nhanSell = mNhan.group(2);
                            if (mWorld.find()) {
                                worldGold = mWorld.group(1);
                            }
                            goldDate = dateFromTitle(title);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        if (e5 == null && sjcBuy == null) {
            throw new IllegalStateException("Không tìm thấy bài giá xăng hoặc giá vàng trong RSS");
        }

        Market def = (cachedMarket != null) ? cachedMarket : sample();
        Market m = new Market();
        m.e5 = (e5 != null) ? e5 : def.e5;
        m.e10 = (e10 != null) ? e10 : def.e10;
        m.diesel = (diesel != null) ? diesel : def.diesel;
        m.sjcBuy = (sjcBuy != null) ? sjcBuy : def.sjcBuy;
        m.sjcSell = (sjcSell != null) ? sjcSell : def.sjcSell;
        m.nhanBuy = (nhanBuy != null) ? nhanBuy : def.nhanBuy;
        m.nhanSell = (nhanSell != null) ? nhanSell : def.nhanSell;
        m.worldGold = (worldGold != null) ? worldGold : def.worldGold;

        m.date = !goldDate.isEmpty() ? goldDate : (!fuelDate.isEmpty() ? fuelDate : def.date);
        m.source = "VietNamNet";
        m.sample = false;
        return m;
    }

    /** Dữ liệu mẫu khi hoàn toàn mất mạng. */
    public static Market sample() {
        Market m = new Market();
        m.sample = true;
        m.date = "--/--";
        m.source = "dữ liệu mẫu";
        m.e5 = "26.027";
        m.e10 = "25.734";
        m.diesel = "30.246";
        m.sjcBuy = "139,7";
        m.sjcSell = "142,7";
        m.nhanBuy = "137,4";
        m.nhanSell = "141,4";
        m.worldGold = "4.058,9";
        return m;
    }

    private static String dateFromTitle(String title) {
        Matcher m = DATE_PATTERN.matcher(title);
        return m.find() ? m.group(1) : "";
    }

    private static String stripHtml(String html) {
        String s = SCRIPT_STYLE_PATTERN.matcher(html).replaceAll(" ");
        s = HTML_TAG_PATTERN.matcher(s).replaceAll(" ").replace("&nbsp;", " ");
        return WHITESPACE_PATTERN.matcher(s).replaceAll(" ");
    }

    private static String firstGroup(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String get(String address) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(address).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
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

