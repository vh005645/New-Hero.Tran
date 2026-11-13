package vn.herotran.launcher;

/**
 * Đổi dương lịch sang âm lịch Việt Nam (thuật toán Hồ Ngọc Đức), không cần mạng.
 * Trả về {ngày, tháng, năm, nhuận(0/1)}.
 */
public final class Lunar {

    private static final double PI = Math.PI;
    private static final int TIME_ZONE = 7;

    private Lunar() {
    }

    public static int[] fromSolar(int dd, int mm, int yy) {
        int dayNumber = jdFromDate(dd, mm, yy);
        int k = (int) Math.floor((dayNumber - 2415021.076998695) / 29.530588853);
        int monthStart = getNewMoonDay(k + 1);
        if (monthStart > dayNumber) {
            monthStart = getNewMoonDay(k);
        }
        int a11 = getLunarMonth11(yy);
        int b11 = a11;
        int lunarYear;
        if (a11 >= monthStart) {
            lunarYear = yy;
            a11 = getLunarMonth11(yy - 1);
        } else {
            lunarYear = yy + 1;
            b11 = getLunarMonth11(yy + 1);
        }
        int lunarDay = dayNumber - monthStart + 1;
        int diff = (int) Math.floor((monthStart - a11) / 29.0);
        int lunarLeap = 0;
        int lunarMonth = diff + 11;
        if (b11 - a11 > 365) {
            int leapMonthDiff = getLeapMonthOffset(a11);
            if (diff >= leapMonthDiff) {
                lunarMonth = diff + 10;
                if (diff == leapMonthDiff) {
                    lunarLeap = 1;
                }
            }
        }
        if (lunarMonth > 12) {
            lunarMonth -= 12;
        }
        if (lunarMonth >= 11 && diff < 4) {
            lunarYear -= 1;
        }
        return new int[] {lunarDay, lunarMonth, lunarYear, lunarLeap};
    }

    private static int jdFromDate(int dd, int mm, int yy) {
        int a = (14 - mm) / 12;
        int y = yy + 4800 - a;
        int m = mm + 12 * a - 3;
        return dd + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045;
    }

    private static int getNewMoonDay(int k) {
        double t = k / 1236.85;
        double t2 = t * t;
        double t3 = t2 * t;
        double dr = PI / 180;
        double jd1 = 2415020.75933 + 29.53058868 * k + 0.0001178 * t2 - 0.000000155 * t3;
        jd1 = jd1 + 0.00033 * Math.sin((166.56 + 132.87 * t - 0.009173 * t2) * dr);
        double m = 359.2242 + 29.10535608 * k - 0.0000333 * t2 - 0.00000347 * t3;
        double mpr = 306.0253 + 385.81691806 * k + 0.0107306 * t2 + 0.00001236 * t3;
        double f = 21.2964 + 390.67050646 * k - 0.0016528 * t2 - 0.00000239 * t3;
        double c1 = (0.1734 - 0.000393 * t) * Math.sin(m * dr) + 0.0021 * Math.sin(2 * dr * m);
        c1 = c1 - 0.4068 * Math.sin(mpr * dr) + 0.0161 * Math.sin(dr * 2 * mpr);
        c1 = c1 - 0.0004 * Math.sin(dr * 3 * mpr);
        c1 = c1 + 0.0104 * Math.sin(dr * 2 * f) - 0.0051 * Math.sin(dr * (m + mpr));
        c1 = c1 - 0.0074 * Math.sin(dr * (m - mpr)) + 0.0004 * Math.sin(dr * (2 * f + m));
        c1 = c1 - 0.0004 * Math.sin(dr * (2 * f - m)) - 0.0006 * Math.sin(dr * (2 * f + mpr));
        c1 = c1 + 0.0010 * Math.sin(dr * (2 * f - mpr)) + 0.0005 * Math.sin(dr * (2 * mpr + m));
        double deltat;
        if (t < -11) {
            deltat = 0.001 + 0.000839 * t + 0.0002261 * t2 - 0.00000845 * t3 - 0.000000081 * t * t3;
        } else {
            deltat = -0.000278 + 0.000265 * t + 0.000262 * t2;
        }
        double jdNew = jd1 + c1 - deltat;
        return (int) Math.floor(jdNew + 0.5 + TIME_ZONE / 24.0);
    }

    private static int getSunLongitude(int jdn) {
        double t = (jdn - 2451545.5 - TIME_ZONE / 24.0) / 36525;
        double t2 = t * t;
        double dr = PI / 180;
        double m = 357.52910 + 35999.05030 * t - 0.0001559 * t2 - 0.00000048 * t * t2;
        double l0 = 280.46645 + 36000.76983 * t + 0.0003032 * t2;
        double dl = (1.914600 - 0.004817 * t - 0.000014 * t2) * Math.sin(dr * m);
        dl = dl + (0.019993 - 0.000101 * t) * Math.sin(dr * 2 * m) + 0.000290 * Math.sin(dr * 3 * m);
        double l = (l0 + dl) * dr;
        l = l - PI * 2 * Math.floor(l / (PI * 2));
        return (int) Math.floor(l / PI * 6);
    }

    private static int getLunarMonth11(int yy) {
        int off = jdFromDate(31, 12, yy) - 2415021;
        int k = (int) Math.floor(off / 29.530588853);
        int nm = getNewMoonDay(k);
        if (getSunLongitude(nm) >= 9) {
            nm = getNewMoonDay(k - 1);
        }
        return nm;
    }

    private static int getLeapMonthOffset(int a11) {
        int k = (int) Math.floor((a11 - 2415021.076998695) / 29.530588853 + 0.5);
        int last = 0;
        int i = 1;
        int arc = getSunLongitude(getNewMoonDay(k + i));
        do {
            last = arc;
            i++;
            arc = getSunLongitude(getNewMoonDay(k + i));
        } while (arc != last && i < 14);
        return i - 1;
    }
}
