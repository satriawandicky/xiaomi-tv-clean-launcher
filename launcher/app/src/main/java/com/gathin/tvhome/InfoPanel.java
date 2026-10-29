package com.gathin.tvhome;

import android.app.Activity;
import android.content.res.Resources;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The glanceable extras on Home: prayer times under the date, a 7-day weather strip, the league table,
 * a rotating news highlight and a wallpaper that changes every few minutes. Driven by MainActivity's resume and
 * minute tick only, so nothing runs while another app is in front.
 */
final class InfoPanel {
    private static final long INFO_CHECK_MS = 15 * 60_000L;

    private final InfoFeed feed;
    private final InfoFeed.Sink<File> wallpaperSink;
    private final Activity act;
    private final TextView prayer, teamTitle, teamNext, teamLast, newsChip, newsCount, newsHeadline;
    private final View teamCard, newsCard, weatherCard, newsProgress;
    private final LinearLayout weatherDays, leagueTable;
    private final String teamId;
    private final long newsMs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable newsTick = new Runnable() {
        @Override
        public void run() {
            newsIndex++;
            showHeadline(true);
        }
    };
    private int newsIndex;
    private final int quoteMinutes;
    private final View quoteCard, quoteBody;
    private final TextView quoteChip, quoteText, quoteSource, quoteNext, newsUpdate;
    private final String leagueName;
    private String[] quoteShown;
    private boolean resumed;
    private final boolean weatherOn;
    private final String techUrl, localUrl;
    private String[] techNews = {}, localNews = {};
    private final double lat, lng, tz;
    private final int ihtiyat;
    private final boolean prayerOn;
    private final long rotateMs;
    private long lastWall, lastInfo;
    private LocalDate prayerDay;
    private int[] prayerTimes;

    InfoPanel(Activity act, InfoFeed.Sink<File> wallpaperSink) {
        this.act = act;
        TextView wallCaption = act.findViewById(R.id.wallCaption);
        this.wallpaperSink = file -> {
            String place = InfoFeed.caption(file);
            wallCaption.setText(place.isEmpty() ? "" : "\uD83D\uDCCD  " + place);
            wallpaperSink.accept(file);
        };
        Resources r = act.getResources();
        teamId = r.getString(R.string.football_team_id);
        newsMs = Math.max(4, r.getInteger(R.integer.news_seconds)) * 1000L;
        quoteMinutes = r.getInteger(R.integer.quote_minutes);
        quoteCard = act.findViewById(R.id.quoteCard);
        quoteBody = act.findViewById(R.id.quoteBody);
        quoteChip = act.findViewById(R.id.quoteChip);
        quoteText = act.findViewById(R.id.quoteText);
        quoteSource = act.findViewById(R.id.quoteSource);
        quoteNext = act.findViewById(R.id.quoteNext);
        newsUpdate = act.findViewById(R.id.newsUpdate);
        leagueName = r.getString(R.string.football_league_name);
        feed = new InfoFeed(act, teamId, r.getString(R.string.football_league), r.getString(R.string.football_league_name));
        techUrl = r.getString(R.string.news_tech_url);
        localUrl = r.getString(R.string.news_local_url);
        rotateMs = r.getInteger(R.integer.wallpaper_rotate_minutes) * 60_000L;
        lat = parse(r.getString(R.string.prayer_lat));
        lng = parse(r.getString(R.string.prayer_lng));
        tz = parse(r.getString(R.string.prayer_tz));
        ihtiyat = r.getInteger(R.integer.prayer_ihtiyat);
        prayerOn = !Double.isNaN(lat) && !Double.isNaN(lng) && !Double.isNaN(tz);
        weatherOn = r.getBoolean(R.bool.weather) && !Double.isNaN(lat) && !Double.isNaN(lng);

        prayer = act.findViewById(R.id.prayer);
        teamCard = act.findViewById(R.id.teamCard);
        teamTitle = act.findViewById(R.id.teamTitle);
        leagueTable = act.findViewById(R.id.leagueTable);
        teamNext = act.findViewById(R.id.teamNext);
        teamLast = act.findViewById(R.id.teamLast);
        newsCard = act.findViewById(R.id.newsCard);
        newsChip = act.findViewById(R.id.newsChip);
        newsCount = act.findViewById(R.id.newsCount);
        newsHeadline = act.findViewById(R.id.newsHeadline);
        newsProgress = act.findViewById(R.id.newsProgress);
        weatherCard = act.findViewById(R.id.weatherCard);
        weatherDays = act.findViewById(R.id.weatherDays);
        ((TextView) act.findViewById(R.id.weatherTitle)).setText(r.getString(R.string.weather_place));
        teamTitle.setText(leagueName);
        if (teamId.isEmpty()) teamCard.setVisibility(View.GONE);

        // The cards float over the area the grid scrolls into: fade them out once the dock leaves its place.
        View cards = act.findViewById(R.id.infoCards);
        ((RecyclerView) act.findViewById(R.id.grid)).addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                float target = rv.computeVerticalScrollOffset() > 0 ? 0f : 1f;
                if (!Float.valueOf(target).equals(cards.getTag())) {   // animate once per change
                    cards.setTag(target);
                    cards.animate().alpha(target).setDuration(200).start();
                }
            }
        });
    }

    void resume() {
        resumed = true;
        renderNews();
        showHeadline(false);
        renderQuote(false);
        renderPrayer();
        if (rotateMs > 0 && System.currentTimeMillis() - lastWall >= rotateMs) nextWallpaper();
        refreshInfo(true);
    }

    /** Home left the screen: no headline timer, no animation running behind another app. */
    void pause() {
        resumed = false;
        handler.removeCallbacks(newsTick);
        newsHeadline.animate().cancel();
        newsProgress.animate().cancel();
    }

    void minute() {
        updateLabels();
        renderPrayer();
        renderQuote(true);
        if (rotateMs > 0 && System.currentTimeMillis() - lastWall >= rotateMs) nextWallpaper();
        refreshInfo(false);
    }

    private void nextWallpaper() {
        lastWall = System.currentTimeMillis();
        feed.nextWallpaper(wallpaperSink);
    }

    private void refreshInfo(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastInfo < INFO_CHECK_MS) return;
        lastInfo = now;
        feed.football(this::renderTable);
        if (weatherOn) feed.weather(lat, lng, this::renderWeather);
        feed.news("tech", techUrl, h -> { techNews = h; renderNews(); });
        feed.news("local", localUrl, h -> { localNews = h; renderNews(); });
    }

    private void renderNews() {
        boolean first = newsCard.getVisibility() != View.VISIBLE;
        newsCard.setVisibility(View.VISIBLE);   // facts are bundled, so there is always something to show
        updateLabels();
        if (first) showHeadline(false);
    }

    /** Shows the quote for this time slot; when it changes, the old one fades out and the new one in. */
    private void renderQuote(boolean animate) {
        if (quoteMinutes <= 0) { quoteCard.setVisibility(View.GONE); return; }
        String[] q = Quotes.at(System.currentTimeMillis(), quoteMinutes);
        if (q == quoteShown) return;
        quoteShown = q;
        Runnable swap = () -> {
            quoteChip.setText(q[0]);
            ((GradientDrawable) quoteChip.getBackground().mutate()).setColor(Quotes.color(q[0]));
            quoteText.setText("\u201C" + q[1] + "\u201D");
            quoteSource.setText("\u2014 " + q[2]);
        };
        quoteCard.setVisibility(View.VISIBLE);
        quoteBody.animate().cancel();
        if (!animate) { swap.run(); quoteBody.setAlpha(1f); return; }
        quoteBody.animate().alpha(0f).setDuration(400).withEndAction(() -> {
            swap.run();
            quoteBody.animate().alpha(1f).setDuration(700).start();
        }).start();
    }

    /**
     * Tech, nasional and a fun fact take turns: tech 1, nasional 1, fact, tech 2, ... The facts start at a
     * different one each day. Offline with no news cached, the card shows facts alone.
     */
    private List<String[]> headlines() {
        List<String[]> all = new ArrayList<>();
        int day = (int) LocalDate.now().toEpochDay();
        int rounds = Math.max(Math.max(techNews.length, localNews.length), 8);
        for (int i = 0; i < rounds; i++) {
            if (i < techNews.length) all.add(new String[]{"tech", techNews[i]});
            if (i < localNews.length) all.add(new String[]{"local", localNews[i]});
            all.add(new String[]{"fact", Facts.ALL[Math.floorMod(day * 7 + i, Facts.ALL.length)]});
        }
        return all;
    }

    private static final java.time.format.DateTimeFormatter HM = java.time.format.DateTimeFormatter.ofPattern("HH:mm");

    private static String hm(long epochMillis) {
        return java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneId.systemDefault()).format(HM);
    }

    /** "~01:47": when a cached feed is due again; a time already past means "at the next visit to Home". */
    private String nextFetch(String... names) {
        long next = Long.MAX_VALUE;
        for (String n : names) {
            long t = feed.nextUpdate(n, n.startsWith("news_") ? InfoFeed.NEWS_TTL_MS : InfoFeed.INFO_TTL_MS);
            if (t > 0) next = Math.min(next, t);
        }
        if (next == Long.MAX_VALUE) return "";
        return "~" + hm(Math.max(next, System.currentTimeMillis()));
    }

    /** The "next update" labels on the news, quote and table cards; refreshed every minute. */
    private void updateLabels() {
        String news = nextFetch("news_tech.json", "news_local.json");
        newsUpdate.setText(news.isEmpty() ? "" : "news update " + news);
        if (quoteMinutes > 0) {
            long slot = quoteMinutes * 60_000L;
            quoteNext.setText("next " + hm((System.currentTimeMillis() / slot + 1) * slot));
        }
        String table = nextFetch("football3.json");
        teamTitle.setText(leagueName + (table.isEmpty() ? "" : "  ·  " + table));
    }

    /**
     * Shows headline newsIndex. Animated: the old one slides up and fades, the new one comes up from
     * below. Then the progress bar fills over news_seconds and the next one follows.
     */
    private void showHeadline(boolean animate) {
        handler.removeCallbacks(newsTick);
        List<String[]> all = headlines();
        if (all.isEmpty() || !resumed) return;
        String[] h = all.get(Math.floorMod(newsIndex, all.size()));
        int kind = h[0].equals("tech") ? 0 : h[0].equals("local") ? 1 : 2;
        Runnable swap = () -> {
            newsChip.setText(act.getString(new int[]{R.string.news_tech_label, R.string.news_local_label, R.string.news_fact_label}[kind]));
            ((GradientDrawable) newsChip.getBackground().mutate()).setColor(new int[]{0xFF3D7BFF, 0xFFD9363E, 0xFF8E44AD}[kind]);
            newsCount.setText((Math.floorMod(newsIndex, all.size()) + 1) + " / " + all.size());
            newsHeadline.setText(h[1]);
        };
        float lift = 14 * act.getResources().getDisplayMetrics().density;
        newsHeadline.animate().cancel();
        if (animate) {
            newsHeadline.animate().translationY(-lift).alpha(0f).setDuration(260).withEndAction(() -> {
                swap.run();
                newsHeadline.setTranslationY(lift);
                newsHeadline.animate().translationY(0f).alpha(1f).setDuration(380)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            }).start();
        } else {
            swap.run();
            newsHeadline.setTranslationY(0f);
            newsHeadline.setAlpha(1f);
        }
        newsProgress.animate().cancel();
        newsProgress.setScaleX(0f);
        newsProgress.animate().scaleX(1f).setDuration(newsMs).setInterpolator(new android.view.animation.LinearInterpolator()).start();
        handler.postDelayed(newsTick, newsMs);
    }

    /**
     * Six rows of the table: the top five and the followed team (or the sixth team when it is in the top five),
     * the followed team in full white on a light band. Rows are built once, then only re-texted.
     */
    private void renderTable(InfoFeed.Football f) {
        if (f.table.isEmpty()) return;
        List<String[]> rows = new ArrayList<>(f.table.subList(0, Math.min(6, f.table.size())));
        boolean shown = false;
        for (String[] row : rows) shown |= row[5].equals(teamId);
        if (!shown) {
            for (String[] row : f.table) if (row[5].equals(teamId)) rows.set(rows.size() - 1, row);
        }
        float dp = act.getResources().getDisplayMetrics().density;
        if (leagueTable.getChildCount() != rows.size()) {
            leagueTable.removeAllViews();
            for (int i = 0; i < rows.size(); i++) {
                LinearLayout line = new LinearLayout(act);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setPadding((int) (6 * dp), (int) (1 * dp), (int) (6 * dp), (int) (1 * dp));
                int[] widths = {22, 0, 34, 40, 36};   // #, team (fills), P, GD, Pts
                for (int k = 0; k < widths.length; k++) {
                    TextView t = new TextView(act, null, 0, R.style.InfoCardLine);
                    t.setSingleLine(true);
                    t.setEllipsize(TextUtils.TruncateAt.END);
                    t.setGravity(k == 1 ? Gravity.START : Gravity.END);
                    LinearLayout.LayoutParams lp = widths[k] == 0
                            ? new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            : new LinearLayout.LayoutParams((int) (widths[k] * dp), LinearLayout.LayoutParams.WRAP_CONTENT);
                    if (k == 1) lp.setMarginStart((int) (8 * dp));
                    lp.topMargin = 0;
                    line.addView(t, lp);
                }
                leagueTable.addView(line);
            }
        }
        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            boolean mine = row[5].equals(teamId);
            LinearLayout line = (LinearLayout) leagueTable.getChildAt(i);
            line.setBackgroundResource(mine ? R.drawable.table_mine : 0);
            String[] cells = {row[0], row[1], row[2] + "P", row[3], row[4]};
            for (int k = 0; k < cells.length; k++) {
                TextView t = (TextView) line.getChildAt(k);
                t.setText(cells[k]);
                t.setTextColor(mine ? 0xFFFFFFFF : (k == 4 ? 0xE6FFFFFF : 0xA6FFFFFF));
                t.setTypeface(t.getTypeface(), mine || k == 4 ? Typeface.BOLD : Typeface.NORMAL);
            }
        }
        set(teamNext, f.next.isEmpty() ? "" : "Next  " + f.next);
        set(teamLast, f.last.isEmpty() ? "" : "Last  " + f.last);
        teamCard.setVisibility(View.VISIBLE);
    }

    /** Seven compact columns: the day, then symbol and max/min. Built once, then only re-texted. */
    private void renderWeather(String[][] days) {
        if (weatherDays.getChildCount() != days.length) {
            weatherDays.removeAllViews();
            for (int i = 0; i < days.length; i++) {
                LinearLayout col = new LinearLayout(act);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setGravity(Gravity.CENTER_HORIZONTAL);
                for (int style : new int[]{R.style.InfoCardTitle, R.style.InfoCardLine}) {
                    TextView t = new TextView(act, null, 0, style);
                    t.setGravity(Gravity.CENTER_HORIZONTAL);
                    col.addView(t, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                }
                weatherDays.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            }
        }
        for (int i = 0; i < days.length; i++) {
            String[] d = days[i];
            LinearLayout col = (LinearLayout) weatherDays.getChildAt(i);
            TextView day = (TextView) col.getChildAt(0), temp = (TextView) col.getChildAt(1);
            day.setText(i == 0 ? "Today" : LocalDate.parse(d[0]).getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.getDefault()));
            temp.setText(symbol(d[1]) + "  " + round(d[2]) + "° / " + round(d[3]) + "°");
            temp.setTextColor(i == 0 ? 0xFFFFFFFF : 0xCCFFFFFF);
        }
        weatherCard.setVisibility(View.VISIBLE);
    }

    /** WMO weather code to a symbol (drawn by the system emoji font). */
    private static String symbol(String code) {
        int c;
        try { c = (int) Double.parseDouble(code); } catch (Exception e) { return "·"; }
        if (c == 0) return "\u2600\uFE0F";
        if (c <= 2) return "\uD83C\uDF24\uFE0F";
        if (c == 3) return "\u2601\uFE0F";
        if (c == 45 || c == 48) return "\uD83C\uDF2B\uFE0F";
        if (c >= 95) return "\u26C8\uFE0F";
        if ((c >= 71 && c <= 77) || c == 85 || c == 86) return "\uD83C\uDF28\uFE0F";
        if (c >= 51 && c <= 57) return "\uD83C\uDF26\uFE0F";
        return "\uD83C\uDF27\uFE0F";
    }

    private static String round(String v) {
        try { return String.valueOf(Math.round(Double.parseDouble(v))); } catch (Exception e) { return "–"; }
    }

    /** "SUBUH 03:51   DZUHUR 11:22 …" with the next prayer in full white and a countdown after it. */
    private void renderPrayer() {
        if (!prayerOn) { prayer.setVisibility(View.GONE); return; }
        LocalDate today = LocalDate.now();
        if (!today.equals(prayerDay)) {
            prayerDay = today;
            prayerTimes = PrayerTimes.compute(today, lat, lng, tz, ihtiyat);
        }
        LocalTime now = LocalTime.now();
        int m = now.getHour() * 60 + now.getMinute();
        int next = PrayerTimes.next(prayerTimes, m);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = 1; i < prayerTimes.length; i++) {
            if (i == 2) continue;   // sunrise is not a prayer
            if (sb.length() > 0) sb.append("    ");
            int start = sb.length();
            sb.append(PrayerTimes.NAMES[i]).append(' ').append(hhmm(prayerTimes[i]));
            if (i == next) {
                int left = (prayerTimes[i] - m + 24 * 60) % (24 * 60);
                sb.append(left >= 60 ? String.format(" · in %dh %dm", left / 60, left % 60) : String.format(" · in %dm", left));
                sb.setSpan(new ForegroundColorSpan(0xFFFFFFFF), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        prayer.setText(sb);
        prayer.setVisibility(View.VISIBLE);
    }

    private static void set(TextView v, String text) {
        v.setText(text);
        v.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private static String hhmm(int minutes) {
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }

    private static double parse(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return Double.NaN; }
    }
}
