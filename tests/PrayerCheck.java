package com.gathin.tvhome;

import java.time.LocalDate;

/**
 * Checks PrayerTimes against Aladhan (method 20 = Kemenag, no ihtiyat) for Sragen on 2026-10-30.
 * Run: tests/prayer-check.sh
 */
public class PrayerCheck {
    public static void main(String[] a) {
        int[] ref = {3*60+39, 3*60+49, 5*60+9, 11*60+20, 14*60+34, 17*60+30, 18*60+42};
        int[] t = PrayerTimes.compute(LocalDate.of(2026, 10, 30), -7.4262, 111.0222, 7, 0);
        for (int i = 0; i < t.length; i++) {
            System.out.printf("%-8s %02d:%02d  ref %02d:%02d%n", PrayerTimes.NAMES[i], t[i]/60, t[i]%60, ref[i]/60, ref[i]%60);
            if (Math.abs(t[i] - ref[i]) > 1) throw new AssertionError(PrayerTimes.NAMES[i] + " off by more than 1 minute");
        }
        if (PrayerTimes.next(t, 12 * 60) != 4) throw new AssertionError("next prayer after 12:00 should be Ashar");
        System.out.println("OK");
    }
}
