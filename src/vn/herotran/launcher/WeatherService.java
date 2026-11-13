package vn.herotran.launcher;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tự động định vị vị trí theo IP qua các dịch vụ miễn phí (ipwho.is, ipinfo.io, ip-api.com)
 * sau đó lấy dự báo thời tiết thời gian thực từ Open-Meteo (kèm bộ nhớ đệm TTL).
 */
public final class WeatherService {

    private static final String FORECAST_URL =
            "https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s"
                    + "&current=temperature_2m,weather_code"
                    + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                    + "&timezone=auto&forecast_days=4";

    private static final String[] DAY_LABELS = {"Chủ Nhật", "Thứ 2", "Thứ 3", "Thứ 4", "Thứ 5", "Thứ 6", "Thứ 7"};

    private static final long WEATHER_TTL_MS = 30 * 60 * 1000L;     // 30 phút cho dữ liệu thật
    private static final long SAMPLE_RETRY_MS = 60 * 1000L;         // 1 phút nếu đang là dữ liệu mẫu
    private static final long LOCATION_TTL_MS = 6 * 60 * 60 * 1000L; // 6 giờ cho tọa độ IP

    private static volatile Weather cachedWeather;
    private static volatile long lastWeatherFetchTime = 0;
    private static volatile GeoLocation cachedLocation;
    private static volatile long lastLocationTime = 0;
    private static final AtomicBoolean isFetching = new AtomicBoolean(false);

    public interface Callback {
        void onWeather(Weather weather);
    }

    public static final class Weather {
        public String city;
        public String condition;
        public int temp;
        public int tempMin;
        public int tempMax;
        public String[] dayLabels;
        public String[] dayConditions;
        public int[] dayMax;
        public int[] dayMin;
        public boolean sample;
    }

    private static class GeoLocation {
        final String lat;
        final String lon;
        final String city;
        final boolean isFallback;

        GeoLocation(String lat, String lon, String city, boolean isFallback) {
            this.lat = lat;
            this.lon = lon;
            this.city = city;
            this.isFallback = isFallback;
        }
    }

    private WeatherService() {
    }

    /** Gọi trên luồng nền (có kiểm tra cache TTL), kết quả được trả về luồng chính. */
    public static void load(final Callback callback) {
        final Handler main = new Handler(Looper.getMainLooper());
        final Weather currentCache = cachedWeather;
        final long now = SystemClock.elapsedRealtime();

        if (currentCache != null) {
            long ttl = currentCache.sample ? SAMPLE_RETRY_MS : WEATHER_TTL_MS;
            if ((now - lastWeatherFetchTime) < ttl) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onWeather(currentCache);
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
                        callback.onWeather(currentCache);
                    }
                });
            }
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                Weather result;
                try {
                    result = fetch();
                } catch (Exception e) {
                    result = (cachedWeather != null && !cachedWeather.sample) ? cachedWeather : sample();
                } finally {
                    isFetching.set(false);
                }
                cachedWeather = result;
                lastWeatherFetchTime = SystemClock.elapsedRealtime();
                final Weather out = result;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onWeather(out);
                    }
                });
            }
        }).start();
    }

    private static GeoLocation detectLocation() {
        GeoLocation loc = cachedLocation;
        long now = SystemClock.elapsedRealtime();
        if (loc != null && !loc.isFallback && (now - lastLocationTime) < LOCATION_TTL_MS) {
            return loc;
        }

        // 1. Thử ipwho.is
        try {
            String jsonStr = get("https://ipwho.is/?lang=vi");
            JSONObject obj = new JSONObject(jsonStr);
            if (obj.optBoolean("success", false) || obj.has("latitude")) {
                double lat = obj.getDouble("latitude");
                double lon = obj.getDouble("longitude");
                String city = obj.optString("city", "");
                if (city.isEmpty()) {
                    city = obj.optString("region", "Việt Nam");
                }
                GeoLocation detected = new GeoLocation(String.valueOf(lat), String.valueOf(lon), formatCityName(city), false);
                cachedLocation = detected;
                lastLocationTime = SystemClock.elapsedRealtime();
                return detected;
            }
        } catch (Exception ignored) {
        }

        // 2. Thử ipinfo.io
        try {
            String jsonStr = get("https://ipinfo.io/json");
            JSONObject obj = new JSONObject(jsonStr);
            String locStr = obj.optString("loc", "");
            if (locStr.contains(",")) {
                String[] parts = locStr.split(",");
                String city = obj.optString("city", "");
                if (city.isEmpty()) {
                    city = obj.optString("region", "Việt Nam");
                }
                GeoLocation detected = new GeoLocation(parts[0].trim(), parts[1].trim(), formatCityName(city), false);
                cachedLocation = detected;
                lastLocationTime = SystemClock.elapsedRealtime();
                return detected;
            }
        } catch (Exception ignored) {
        }

        // 3. Thử ip-api.com
        try {
            String jsonStr = get("http://ip-api.com/json");
            JSONObject obj = new JSONObject(jsonStr);
            if ("success".equalsIgnoreCase(obj.optString("status", ""))) {
                double lat = obj.getDouble("lat");
                double lon = obj.getDouble("lon");
                String city = obj.optString("city", "");
                if (city.isEmpty()) {
                    city = obj.optString("regionName", "Việt Nam");
                }
                GeoLocation detected = new GeoLocation(String.valueOf(lat), String.valueOf(lon), formatCityName(city), false);
                cachedLocation = detected;
                lastLocationTime = SystemClock.elapsedRealtime();
                return detected;
            }
        } catch (Exception ignored) {
        }

        if (loc != null) {
            return loc;
        }
        // Mặc định nếu không định vị được IP: TP. Hồ Chí Minh
        return new GeoLocation("10.823", "106.629", "TP. Hồ Chí Minh", true);
    }

    private static Weather fetch() throws Exception {
        GeoLocation loc = detectLocation();

        String url = String.format(Locale.US, FORECAST_URL, loc.lat, loc.lon);
        JSONObject data = new JSONObject(get(url));
        JSONObject current = data.getJSONObject("current");
        JSONObject daily = data.getJSONObject("daily");
        JSONArray times = daily.getJSONArray("time");
        JSONArray codes = daily.getJSONArray("weather_code");
        JSONArray maxs = daily.getJSONArray("temperature_2m_max");
        JSONArray mins = daily.getJSONArray("temperature_2m_min");

        Weather w = new Weather();
        w.sample = false;
        w.city = loc.city;
        w.condition = conditionText(current.getInt("weather_code"));
        w.temp = (int) Math.round(current.getDouble("temperature_2m"));
        w.tempMax = (int) Math.round(maxs.getDouble(0));
        w.tempMin = (int) Math.round(mins.getDouble(0));

        int n = Math.min(5, times.length());
        w.dayLabels = new String[n];
        w.dayConditions = new String[n];
        w.dayMax = new int[n];
        w.dayMin = new int[n];
        SimpleDateFormat parse = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        Calendar c = Calendar.getInstance();
        for (int i = 0; i < n; i++) {
            c.setTime(parse.parse(times.getString(i)));
            w.dayLabels[i] = DAY_LABELS[c.get(Calendar.DAY_OF_WEEK) - 1];
            w.dayConditions[i] = conditionText(codes.getInt(i));
            w.dayMax[i] = (int) Math.round(maxs.getDouble(i));
            w.dayMin[i] = (int) Math.round(mins.getDouble(i));
        }
        return w;
    }

    private static final Map<String, String> CITY_MAP = new HashMap<>();
    static {
        // 1. 6 Thành phố trực thuộc Trung ương & tên gọi phổ biến
        CITY_MAP.put("ho chi minh", "TP. Hồ Chí Minh");
        CITY_MAP.put("sai gon", "TP. Hồ Chí Minh");
        CITY_MAP.put("saigon", "TP. Hồ Chí Minh");
        CITY_MAP.put("hcmc", "TP. Hồ Chí Minh");
        CITY_MAP.put("thu duc", "TP. Thủ Đức");
        CITY_MAP.put("ha noi", "Hà Nội");
        CITY_MAP.put("hanoi", "Hà Nội");
        CITY_MAP.put("ha dong", "Hà Nội");
        CITY_MAP.put("son tay", "Sơn Tây");
        CITY_MAP.put("da nang", "Đà Nẵng");
        CITY_MAP.put("danang", "Đà Nẵng");
        CITY_MAP.put("hai phong", "Hải Phòng");
        CITY_MAP.put("haiphong", "Hải Phòng");
        CITY_MAP.put("thuy nguyen", "Hải Phòng");
        CITY_MAP.put("can tho", "Cần Thơ");
        CITY_MAP.put("cantho", "Cần Thơ");
        CITY_MAP.put("hue", "Huế");
        CITY_MAP.put("thua thien hue", "Huế");

        // 2. Miền Bắc (25 tỉnh thành & các thành phố trực thuộc tỉnh)
        CITY_MAP.put("ha giang", "Hà Giang");
        CITY_MAP.put("cao bang", "Cao Bằng");
        CITY_MAP.put("bac kan", "Bắc Kạn");
        CITY_MAP.put("bac can", "Bắc Kạn");
        CITY_MAP.put("tuyen quang", "Tuyên Quang");
        CITY_MAP.put("lao cai", "Lào Cai");
        CITY_MAP.put("sa pa", "Sa Pa");
        CITY_MAP.put("sapa", "Sa Pa");
        CITY_MAP.put("dien bien", "Điện Biên");
        CITY_MAP.put("dien bien phu", "Điện Biên Phủ");
        CITY_MAP.put("lai chau", "Lai Châu");
        CITY_MAP.put("son la", "Sơn La");
        CITY_MAP.put("yen bai", "Yên Bái");
        CITY_MAP.put("nghia lo", "Nghĩa Lộ");
        CITY_MAP.put("hoa binh", "Hòa Bình");
        CITY_MAP.put("thai nguyen", "Thái Nguyên");
        CITY_MAP.put("song cong", "Sông Công");
        CITY_MAP.put("pho yen", "Phổ Yên");
        CITY_MAP.put("lang son", "Lạng Sơn");
        CITY_MAP.put("quang ninh", "Quảng Ninh");
        CITY_MAP.put("ha long", "Hạ Long");
        CITY_MAP.put("halong", "Hạ Long");
        CITY_MAP.put("mong cai", "Móng Cái");
        CITY_MAP.put("cam pha", "Cẩm Phả");
        CITY_MAP.put("uong bi", "Uông Bí");
        CITY_MAP.put("dong trieu", "Đông Triều");
        CITY_MAP.put("bac giang", "Bắc Giang");
        CITY_MAP.put("viet yen", "Việt Yên");
        CITY_MAP.put("phu tho", "Phú Thọ");
        CITY_MAP.put("viet tri", "Việt Trì");
        CITY_MAP.put("vinh phuc", "Vĩnh Phúc");
        CITY_MAP.put("vinh yen", "Vĩnh Yên");
        CITY_MAP.put("phuc yen", "Phúc Yên");
        CITY_MAP.put("bac ninh", "Bắc Ninh");
        CITY_MAP.put("tu son", "Từ Sơn");
        CITY_MAP.put("hai duong", "Hải Dương");
        CITY_MAP.put("chi linh", "Chí Linh");
        CITY_MAP.put("hung yen", "Hưng Yên");
        CITY_MAP.put("my hao", "Mỹ Hào");
        CITY_MAP.put("thai binh", "Thái Bình");
        CITY_MAP.put("ha nam", "Hà Nam");
        CITY_MAP.put("phu ly", "Phủ Lý");
        CITY_MAP.put("duy tien", "Duy Tiên");
        CITY_MAP.put("nam dinh", "Nam Định");
        CITY_MAP.put("ninh binh", "Ninh Bình");
        CITY_MAP.put("tam diep", "Tam Điệp");
        CITY_MAP.put("hoa lu", "Hoa Lư");

        // 3. Miền Trung & Tây Nguyên (19 tỉnh thành & các thành phố trực thuộc tỉnh)
        CITY_MAP.put("thanh hoa", "Thanh Hóa");
        CITY_MAP.put("sam son", "Sầm Sơn");
        CITY_MAP.put("bim son", "Bỉm Sơn");
        CITY_MAP.put("nghi son", "Nghi Sơn");
        CITY_MAP.put("nghe an", "Nghệ An");
        CITY_MAP.put("vinh", "TP. Vinh");
        CITY_MAP.put("cua lo", "Cửa Lò");
        CITY_MAP.put("hoang mai", "Hoàng Mai");
        CITY_MAP.put("thai hoa", "Thái Hòa");
        CITY_MAP.put("ha tinh", "Hà Tĩnh");
        CITY_MAP.put("hong linh", "Hồng Lĩnh");
        CITY_MAP.put("ky anh", "Kỳ Anh");
        CITY_MAP.put("quang binh", "Quảng Bình");
        CITY_MAP.put("dong hoi", "Đồng Hới");
        CITY_MAP.put("ba don", "Ba Đồn");
        CITY_MAP.put("quang tri", "Quảng Trị");
        CITY_MAP.put("dong ha", "Đông Hà");
        CITY_MAP.put("quang nam", "Quảng Nam");
        CITY_MAP.put("tam ky", "Tam Kỳ");
        CITY_MAP.put("hoi an", "Hội An");
        CITY_MAP.put("dien ban", "Điện Bàn");
        CITY_MAP.put("quang ngai", "Quảng Ngãi");
        CITY_MAP.put("duc pho", "Đức Phổ");
        CITY_MAP.put("binh dinh", "Bình Định");
        CITY_MAP.put("quy nhon", "Quy Nhơn");
        CITY_MAP.put("qui nhon", "Quy Nhơn");
        CITY_MAP.put("an nhon", "An Nhơn");
        CITY_MAP.put("hoai nhon", "Hoài Nhơn");
        CITY_MAP.put("phu yen", "Phú Yên");
        CITY_MAP.put("tuy hoa", "Tuy Hòa");
        CITY_MAP.put("song cau", "Sông Cầu");
        CITY_MAP.put("khanh hoa", "Khánh Hòa");
        CITY_MAP.put("nha trang", "Nha Trang");
        CITY_MAP.put("cam ranh", "Cam Ranh");
        CITY_MAP.put("ninh hoa", "Ninh Hòa");
        CITY_MAP.put("ninh thuan", "Ninh Thuận");
        CITY_MAP.put("phan rang", "Phan Rang - Tháp Chàm");
        CITY_MAP.put("phan rang thap cham", "Phan Rang - Tháp Chàm");
        CITY_MAP.put("binh thuan", "Bình Thuận");
        CITY_MAP.put("phan thiet", "Phan Thiết");
        CITY_MAP.put("la gi", "La Gi");
        CITY_MAP.put("kon tum", "Kon Tum");
        CITY_MAP.put("gia lai", "Gia Lai");
        CITY_MAP.put("pleiku", "Pleiku");
        CITY_MAP.put("an khe", "An Khê");
        CITY_MAP.put("ayun pa", "Ayun Pa");
        CITY_MAP.put("dak lak", "Đắk Lắk");
        CITY_MAP.put("dac lak", "Đắk Lắk");
        CITY_MAP.put("buon ma thuot", "Buôn Ma Thuột");
        CITY_MAP.put("buon ho", "Buôn Hồ");
        CITY_MAP.put("dak nong", "Đắk Nông");
        CITY_MAP.put("dac nong", "Đắk Nông");
        CITY_MAP.put("gia nghia", "Gia Nghĩa");
        CITY_MAP.put("lam dong", "Lâm Đồng");
        CITY_MAP.put("da lat", "Đà Lạt");
        CITY_MAP.put("dalat", "Đà Lạt");
        CITY_MAP.put("bao loc", "Bảo Lộc");

        // 4. Đông Nam Bộ & Đồng bằng Sông Cửu Long (19 tỉnh thành & các thành phố trực thuộc tỉnh)
        CITY_MAP.put("binh phuoc", "Bình Phước");
        CITY_MAP.put("dong xoai", "Đồng Xoài");
        CITY_MAP.put("phuoc long", "Phước Long");
        CITY_MAP.put("binh long", "Bình Long");
        CITY_MAP.put("chon thanh", "Chơn Thành");
        CITY_MAP.put("tay ninh", "Tây Ninh");
        CITY_MAP.put("hoa thanh", "Hòa Thành");
        CITY_MAP.put("trang bang", "Trảng Bàng");
        CITY_MAP.put("binh duong", "Bình Dương");
        CITY_MAP.put("thu dau mot", "Thủ Dầu Một");
        CITY_MAP.put("di an", "Dĩ An");
        CITY_MAP.put("thuan an", "Thuận An");
        CITY_MAP.put("tan uyen", "Tân Uyên");
        CITY_MAP.put("ben cat", "Bến Cát");
        CITY_MAP.put("dong nai", "Đồng Nai");
        CITY_MAP.put("bien hoa", "Biên Hòa");
        CITY_MAP.put("long khanh", "Long Khánh");
        CITY_MAP.put("ba ria vung tau", "Bà Rịa - Vũng Tàu");
        CITY_MAP.put("vung tau", "Vũng Tàu");
        CITY_MAP.put("ba ria", "Bà Rịa");
        CITY_MAP.put("phu my", "Phú Mỹ");
        CITY_MAP.put("long an", "Long An");
        CITY_MAP.put("tan an", "Tân An");
        CITY_MAP.put("kien tuong", "Kiến Tường");
        CITY_MAP.put("tien giang", "Tiền Giang");
        CITY_MAP.put("my tho", "Mỹ Tho");
        CITY_MAP.put("go cong", "Gò Công");
        CITY_MAP.put("cai lay", "Cai Lậy");
        CITY_MAP.put("ben tre", "Bến Tre");
        CITY_MAP.put("tra vinh", "Trà Vinh");
        CITY_MAP.put("duyen hai", "Duyên Hải");
        CITY_MAP.put("vinh long", "Vĩnh Long");
        CITY_MAP.put("binh minh", "Bình Minh");
        CITY_MAP.put("dong thap", "Đồng Tháp");
        CITY_MAP.put("cao lanh", "Cao Lãnh");
        CITY_MAP.put("sa dec", "Sa Đéc");
        CITY_MAP.put("hong ngu", "Hồng Ngự");
        CITY_MAP.put("an giang", "An Giang");
        CITY_MAP.put("long xuyen", "Long Xuyên");
        CITY_MAP.put("chau doc", "Châu Đốc");
        CITY_MAP.put("tan chau", "Tân Châu");
        CITY_MAP.put("kien giang", "Kiên Giang");
        CITY_MAP.put("rach gia", "Rạch Giá");
        CITY_MAP.put("ha tien", "Hà Tiên");
        CITY_MAP.put("phu quoc", "Phú Quốc");
        CITY_MAP.put("hau giang", "Hậu Giang");
        CITY_MAP.put("vi thanh", "Vị Thanh");
        CITY_MAP.put("nga bay", "Ngã Bảy");
        CITY_MAP.put("long my", "Long Mỹ");
        CITY_MAP.put("soc trang", "Sóc Trăng");
        CITY_MAP.put("nga nam", "Ngã Năm");
        CITY_MAP.put("vinh chau", "Vĩnh Châu");
        CITY_MAP.put("bac lieu", "Bạc Liêu");
        CITY_MAP.put("gia rai", "Giá Rai");
        CITY_MAP.put("ca mau", "Cà Mau");
        CITY_MAP.put("viet nam", "Việt Nam");
        CITY_MAP.put("vietnam", "Việt Nam");
    }

    private static String formatCityName(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "Việt Nam";
        String s = raw.trim();

        // Chuẩn hóa chuỗi: bỏ dấu tiếng Việt, bỏ tiền tố/hậu tố hành chính (Province, City, Tỉnh, Thành phố...)
        String norm = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replace('đ', 'd')
                .replace('Đ', 'D')
                .toLowerCase(Locale.US);

        norm = norm.replaceAll("[-_.,/]+", " ").replaceAll("\\s+", " ").trim();
        norm = norm.replaceAll("^(tinh|thanh pho|tp|thi xa|tx|quan|huyen)\\s+", "");
        norm = norm.replaceAll("\\s+(province|city|district|town|ward)$", "");
        norm = norm.trim();

        String matched = CITY_MAP.get(norm);
        if (matched != null) {
            return matched;
        }
        return s;
    }

    /** Dữ liệu mẫu khi hoàn toàn mất mạng. */
    public static Weather sample() {
        Weather w = new Weather();
        w.sample = true;
        w.city = "TP. Hồ Chí Minh";
        w.condition = "Nhiều mây";
        w.temp = 31;
        w.tempMax = 32;
        w.tempMin = 25;
        w.dayLabels = new String[] {"Chủ Nhật", "Thứ 2", "Thứ 3", "Thứ 4"};
        w.dayConditions = new String[] {"Mưa", "Nhiều mây", "Nắng", "Nắng"};
        w.dayMax = new int[] {31, 30, 30, 30};
        w.dayMin = new int[] {25, 25, 25, 25};
        return w;
    }

    private static String conditionText(int code) {
        switch (code) {
            case 0: return "Trời quang";
            case 1: return "Ít mây";
            case 2: return "Mây rải rác";
            case 3: return "Nhiều mây";
            case 45:
            case 48: return "Sương mù";
            case 51:
            case 53:
            case 55: return "Mưa phùn";
            case 56:
            case 57: return "Mưa phùn lạnh";
            case 61:
            case 63: return "Mưa";
            case 65: return "Mưa to";
            case 66:
            case 67: return "Mưa tuyết";
            case 71:
            case 73:
            case 75: return "Tuyết";
            case 77: return "Mưa đá nhỏ";
            case 80:
            case 81: return "Mưa rào";
            case 82: return "Mưa rào to";
            case 85:
            case 86: return "Mưa tuyết rào";
            case 95: return "Dông";
            case 96:
            case 99: return "Dông kèm mưa đá";
            default:
                if (code <= 3) return "Nhiều mây";
                if (code >= 51 && code <= 67) return "Mưa";
                if (code >= 80 && code <= 82) return "Mưa rào";
                if (code >= 95) return "Dông";
                return "Mây";
        }
    }

    private static String get(String address) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(address).openConnection();
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(6000);
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new java.io.IOException("HTTP error code: " + code);
            }
            InputStream in = conn.getInputStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
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
