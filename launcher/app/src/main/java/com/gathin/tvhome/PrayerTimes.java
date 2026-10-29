package com.gathin.tvhome;

import java.time.LocalDate;

/**
 * Daily prayer times computed on the TV, no network: the PrayTimes.org formulas with Kemenag RI
 * parameters (Fajr 20°, Isha 18°, Asr shadow 1) plus Kemenag's ihtiyat safety margin.
 * Returns minutes after local midnight: imsak, subuh, terbit, dzuhur, ashar, maghrib, isya.
 */
final class PrayerTimes {
    private PrayerTimes() { }

    static final String[] NAMES = {"Imsak", "Subuh", "Terbit", "Dzuhur", "Ashar", "Maghrib", "Isya"};

    static int[] compute(LocalDate day, double lat, double lng, double tz, int ihtiyat) {
        double jd = julian(day.getYear(), day.getMonthValue(), day.getDayOfMonth()) - lng / 360.0;
        double fajr = angleTime(jd, lat, 20, 5 / 24.0, -1);
        double rise = angleTime(jd, lat, 0.833, 6 / 24.0, -1);
        double dhuhr = mid(jd + 12 / 24.0);
        double asr = asrTime(jd, lat, 13 / 24.0);
        double sunset = angleTime(jd, lat, 0.833, 18 / 24.0, 1);
        double isha = angleTime(jd, lat, 18, 18 / 24.0, 1);
        double shift = tz - lng / 15.0;
        int[] t = {
                mins(fajr + shift) - 10 + ihtiyat,
                mins(fajr + shift) + ihtiyat,
                mins(rise + shift) - ihtiyat,
                mins(dhuhr + shift) + ihtiyat,
                mins(asr + shift) + ihtiyat,
                mins(sunset + shift) + ihtiyat,
                mins(isha + shift) + ihtiyat,
        };
        return t;
    }

    /** Index of the next prayer (skipping Imsak and Terbit) after minute m, or 1 (Subuh tomorrow). */
    static int next(int[] t, int m) {
        for (int i = 1; i < t.length; i++) if (i != 2 && t[i] > m) return i;
        return 1;
    }

    private static int mins(double hours) {
        return (int) Math.round(hours * 60);
    }

    private static double julian(int y, int mo, int d) {
        if (mo <= 2) { y--; mo += 12; }
        double a = Math.floor(y / 100.0);
        double b = 2 - a + Math.floor(a / 4);
        return Math.floor(365.25 * (y + 4716)) + Math.floor(30.6001 * (mo + 1)) + d + b - 1524.5;
    }

    /** {declination, equation of time in hours} for julian date jd. */
    private static double[] sun(double jd) {
        double d = jd - 2451545.0;
        double g = fix(357.529 + 0.98560028 * d);
        double q = fix(280.459 + 0.98564736 * d);
        double l = fix(q + 1.915 * sin(g) + 0.020 * sin(2 * g));
        double e = 23.439 - 0.00000036 * d;
        double ra = Math.toDegrees(Math.atan2(cos(e) * sin(l), cos(l))) / 15.0;
        double eqt = q / 15.0 - fixHour(ra);
        double decl = Math.toDegrees(Math.asin(sin(e) * sin(l)));
        return new double[]{decl, eqt};
    }

    /** Solar noon in hours (UTC at longitude 0) for the day fraction t. */
    private static double mid(double jdt) {
        return fixHour(12 - sun(jdt)[1]);
    }

    private static double angleTime(double jd, double lat, double angle, double t, int dir) {
        double decl = sun(jd + t)[0];
        double noon = mid(jd + t);
        double h = Math.toDegrees(Math.acos((-sin(angle) - sin(decl) * sin(lat)) / (cos(decl) * cos(lat)))) / 15.0;
        return noon + dir * h;
    }

    private static double asrTime(double jd, double lat, double t) {
        double decl = sun(jd + t)[0];
        double angle = -Math.toDegrees(Math.atan(1 / (1 + Math.tan(Math.toRadians(Math.abs(lat - decl))))));
        return angleTime(jd, lat, angle, t, 1);
    }

    private static double sin(double deg) { return Math.sin(Math.toRadians(deg)); }
    private static double cos(double deg) { return Math.cos(Math.toRadians(deg)); }
    private static double fix(double a) { a = a - 360 * Math.floor(a / 360); return a < 0 ? a + 360 : a; }
    private static double fixHour(double a) { a = a - 24 * Math.floor(a / 24); return a < 0 ? a + 24 : a; }
}
