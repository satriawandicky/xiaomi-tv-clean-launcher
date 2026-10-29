package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The few things Home fetches from the internet, all keyless and cached in the app's files folder:
 * rotating wallpapers (Bing's daily photos), a football team's table/fixtures (ESPN), a 7-day weather
 * forecast (Open-Meteo) and news headlines (Google News RSS). One background thread; the activity asks only while Home is on screen, and each
 * source is fetched at most once per its interval, so the TV is never polling.
 */
final class InfoFeed {
    interface Sink<T> { void accept(T value); }

    static final long INFO_TTL_MS = 3 * 60 * 60_000L;        // football + weather
    static final long NEWS_TTL_MS = 60 * 60_000L;
    static final long WALL_LIST_TTL_MS = 12 * 60 * 60_000L;  // Bing list of photos
    private static final int MAX_WALLS = 24;

    private final File dir;
    private final ExecutorService bg = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "infofeed");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private final String teamId, teamLeague, leagueName;
    private int wallIndex = -1;

    InfoFeed(Context ctx, String teamId, String teamLeague, String leagueName) {
        dir = new File(Config.dir(ctx), "feed");
        dir.mkdirs();
        this.teamId = teamId;
        this.teamLeague = teamLeague;
        this.leagueName = leagueName;
    }

    /** League table and the team's next/last game; cached copy first, a fresh one if the cache is stale. */
    static final class Football {
        /** {rank, team, played, goal difference, points, team id} for every team, in table order. */
        final List<String[]> table = new ArrayList<>();
        String next = "", last = "";
    }

    void football(Sink<Football> sink) {
        if (teamId.isEmpty()) return;
        cached("football3.json", INFO_TTL_MS, this::fetchFootball, json -> {
            JSONObject o = new JSONObject(json);
            Football f = new Football();
            JSONArray rows = o.getJSONArray("table");
            for (int i = 0; i < rows.length(); i++) {
                JSONArray r = rows.getJSONArray(i);
                String[] row = new String[r.length()];
                for (int k = 0; k < row.length; k++) row[k] = r.getString(k);
                f.table.add(row);
            }
            f.next = o.optString("next");
            f.last = o.optString("last");
            return f;
        }, sink);
    }

    /** Seven days of {date, WMO code, max °C, min °C, rain %}, today first. */
    void weather(double lat, double lng, Sink<String[][]> sink) {
        cached("weather.json", INFO_TTL_MS, () -> get("https://api.open-meteo.com/v1/forecast?latitude=" + lat
                + "&longitude=" + lng + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                + "&timezone=auto&forecast_days=7"), json -> {
            JSONObject d = new JSONObject(json).getJSONObject("daily");
            String[] keys = {"time", "weather_code", "temperature_2m_max", "temperature_2m_min", "precipitation_probability_max"};
            String[][] days = new String[d.getJSONArray("time").length()][keys.length];
            for (int k = 0; k < keys.length; k++) {
                JSONArray col = d.getJSONArray(keys[k]);
                for (int i = 0; i < days.length; i++) days[i][k] = col.isNull(i) ? "" : col.get(i).toString();
            }
            return days;
        }, sink);
    }

    /** Headlines of a Google News RSS feed, without the " - Source" suffix. */
    void news(String name, String url, Sink<String[]> sink) {
        if (url.isEmpty()) return;
        cached("news_" + name + ".json", NEWS_TTL_MS, () -> fetchNews(url), json -> {
            JSONArray a = new JSONArray(json);
            String[] out = new String[a.length()];
            for (int i = 0; i < out.length; i++) out[i] = a.getString(i);
            return out;
        }, sink);
    }

    /** The next wallpaper file, downloaded if needed. Nothing is delivered when offline with no cache. */
    void nextWallpaper(Sink<File> sink) {
        bg.execute(() -> {
            try {
                File list = new File(dir, "bing2.json");
                if (stale(list, WALL_LIST_TTL_MS)) {
                    try { write(list, fetchBingList()); } catch (Exception ignored) { }
                }
                List<String> urls = new ArrayList<>(), captions = new ArrayList<>();
                if (list.exists()) {
                    JSONArray a = new JSONArray(read(list));
                    for (int i = 0; i < a.length(); i++) {
                        urls.add(a.getJSONArray(i).getString(0));
                        captions.add(a.getJSONArray(i).optString(1));
                    }
                }
                if (wallIndex < 0) {
                    try { wallIndex = Integer.parseInt(read(new File(dir, "wall_index")).trim()); } catch (Exception ignored) { }
                }
                File pick = null;
                for (int tries = 0; tries < urls.size() && pick == null; tries++) {
                    wallIndex = (wallIndex + 1) % urls.size();
                    String url = urls.get(wallIndex);
                    File f = new File(dir, "wall_" + Integer.toHexString(url.hashCode()) + ".jpg");
                    if (!f.exists()) {
                        try { download(url, f); } catch (Exception e) { f.delete(); continue; }
                        prune();
                    }
                    try { write(new File(f.getPath().replace(".jpg", ".txt")), captions.get(wallIndex)); } catch (Exception ignored) { }
                    pick = f;
                }
                if (pick == null) pick = anyCachedWall();   // offline: keep rotating what is on disk
                // Remember the position, so a reboot carries on with the next photo instead of the first.
                try { write(new File(dir, "wall_index"), String.valueOf(wallIndex)); } catch (Exception ignored) { }
                if (pick != null) { File p = pick; main.post(() -> sink.accept(p)); }
                // Then fetch the rest of the list in the background, so the screensaver has every photo.
                for (int i = 0; i < urls.size(); i++) {
                    File f = new File(dir, "wall_" + Integer.toHexString(urls.get(i).hashCode()) + ".jpg");
                    try {
                        if (!f.exists()) download(urls.get(i), f);
                        write(new File(f.getPath().replace(".jpg", ".txt")), captions.get(i));
                    } catch (Exception e) { f.delete(); }
                }
                prune();
            } catch (Exception ignored) { }
        });
    }

    // ---- sources -------------------------------------------------------------------------------

    /**
     * Landscape and travel photos only: Bing also runs animal close-ups, which are skipped by name.
     * ponytail: keyword list, extend it if an animal slips through.
     */
    private static final java.util.regex.Pattern ANIMAL = java.util.regex.Pattern.compile(
            "(?i)\\b(wol(f|ves)|fox(es)?|bears?|sloths?|birds?|owls?|deer|whales?|dolphins?|penguins?|seals?|cats?|dogs?|"
            + "monkeys?|apes?|lions?|tigers?|leopards?|elephants?|giraffes?|turtles?|fish|frogs?|butterfl(y|ies)|bees?|eagles?|"
            + "horses?|zebras?|koalas?|kangaroos?|pandas?|otters?|beavers?|squirrels?|bats?|puffins?|herons?|cranes?|ducks?|"
            + "swans?|goats?|sheep|lemurs?|orangutans?|gorillas?|hippos?|rhinos?|bison|moose|elk|caribou|reindeer|hares?|"
            + "rabbits?|hedgehogs?|lizards?|snakes?|crocodiles?|alligators?|flamingos?|pelicans?|hummingbirds?|cubs?|pups?|"
            + "chicks?|calf|calves|jellyfish|octopus|sharks?|rays?|crabs?|insects?|dragonfl(y|ies)|salmon|seabirds?|gulls?)\\b");

    /** [[url, "Place, Region, Country"], ...] from several English Bing markets, animals left out. */
    private String fetchBingList() throws Exception {
        JSONArray out = new JSONArray();
        List<String> seen = new ArrayList<>();
        for (String mkt : new String[]{"en-US", "en-GB", "en-CA", "en-AU"}) {
            for (int idx : new int[]{0, 7}) {   // the archive keeps ~16 days: two pages of 8
                JSONArray imgs;
                try {
                    imgs = new JSONObject(get("https://www.bing.com/HPImageArchive.aspx?format=js&n=8&mkt=" + mkt + "&idx=" + idx))
                            .getJSONArray("images");
                } catch (Exception e) { continue; }
                for (int i = 0; i < imgs.length(); i++) {
                    JSONObject img = imgs.getJSONObject(i);
                    String base = img.getString("urlbase");
                    String caption = img.optString("copyright").replaceAll("\\s*\\(©.*$", "").trim();
                    String id = base.replaceAll("_[^_]*$", "");   // same photo in another market (_EN-US…, _ROW…)
                    // No comma means no "place, country" (e.g. a bird's name): not a travel photo.
                    if (seen.contains(id) || !caption.contains(",") || ANIMAL.matcher(caption).find()) continue;
                    seen.add(id);
                    out.put(new JSONArray(Arrays.asList("https://www.bing.com" + base + "_1920x1080.jpg", caption)));
                }
            }
        }
        if (out.length() == 0) throw new IllegalStateException("no photos");
        return out.toString();
    }

    /** When the cached copy `name` goes stale and is fetched again (next time Home is on screen), or 0 if never fetched. */
    long nextUpdate(String name, long ttl) {
        File f = new File(dir, name);
        return f.exists() ? f.lastModified() + ttl : 0;
    }

    /** Every downloaded wallpaper, for the screensaver. */
    static List<File> walls(Context ctx) {
        File[] walls = new File(Config.dir(ctx), "feed").listFiles((x, n) -> n.startsWith("wall_") && n.endsWith(".jpg"));
        return walls == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(walls));
    }

    private static final String US_STATES = ",Alabama,Alaska,Arizona,Arkansas,California,Colorado,Connecticut,Delaware,Florida,Georgia,"
            + "Hawaii,Idaho,Illinois,Indiana,Iowa,Kansas,Kentucky,Louisiana,Maine,Maryland,Massachusetts,Michigan,Minnesota,"
            + "Mississippi,Missouri,Montana,Nebraska,Nevada,New Hampshire,New Jersey,New Mexico,New York,North Carolina,"
            + "North Dakota,Ohio,Oklahoma,Oregon,Pennsylvania,Rhode Island,South Carolina,South Dakota,Tennessee,Texas,Utah,"
            + "Vermont,Virginia,Washington,West Virginia,Wisconsin,Wyoming,";
    private static final String CA_PROVINCES = ",Alberta,British Columbia,Manitoba,New Brunswick,Newfoundland and Labrador,"
            + "Nova Scotia,Ontario,Prince Edward Island,Quebec,Saskatchewan,Yukon,Northwest Territories,Nunavut,";

    /**
     * {place, country} from a caption like "Grand Canal in Venice, Italy". Bing ends US and Canadian
     * captions with the state or province ("…, Colorado"), so those get their country added.
     */
    static String[] placeCountry(String caption) {
        int comma = caption.lastIndexOf(", ");
        if (comma < 0) return new String[]{caption, ""};
        String place = caption.substring(0, comma), last = caption.substring(comma + 2);
        if (US_STATES.contains("," + last + ",")) return new String[]{place + ", " + last, "USA"};
        if (CA_PROVINCES.contains("," + last + ",")) return new String[]{place + ", " + last, "Canada"};
        return new String[]{place, last};
    }

    /** "Place, Region, Country" of a downloaded wallpaper, or "" if unknown. */
    static String caption(File wall) {
        try { return read(new File(wall.getPath().replace(".jpg", ".txt"))).trim(); } catch (Exception e) { return ""; }
    }

    private String fetchFootball() throws Exception {
        String team = "https://site.api.espn.com/apis/site/v2/sports/soccer/all/teams/" + teamId + "/schedule";
        DateTimeFormatter when = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.getDefault())
                .withZone(ZoneId.systemDefault());
        Instant now = Instant.now();

        JSONArray table = new JSONArray();
        JSONArray entries = new JSONObject(get("https://site.api.espn.com/apis/v2/sports/soccer/" + teamLeague + "/standings"))
                .getJSONArray("children").getJSONObject(0).getJSONObject("standings").getJSONArray("entries");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.getJSONObject(i);
            JSONObject tm = e.getJSONObject("team");
            JSONArray stats = e.getJSONArray("stats");
            String rank = "", pts = "", played = "", gd = "";
            for (int s = 0; s < stats.length(); s++) {
                JSONObject st = stats.getJSONObject(s);
                switch (st.getString("name")) {
                    case "rank": rank = st.getString("displayValue"); break;
                    case "points": pts = st.getString("displayValue"); break;
                    case "gamesPlayed": played = st.getString("displayValue"); break;
                    case "pointDifferential": gd = st.getString("displayValue"); break;
                    default: break;
                }
            }
            table.put(new JSONArray(Arrays.asList(rank, tm.optString("shortDisplayName", tm.optString("displayName")), played, gd, pts, tm.getString("id"))));
        }

        List<JSONObject> games = new ArrayList<>();
        for (String url : new String[]{team, team + "?fixture=true"}) {
            JSONArray ev = new JSONObject(get(url)).getJSONArray("events");
            for (int i = 0; i < ev.length(); i++) games.add(ev.getJSONObject(i));
        }
        games.sort(Comparator.comparing(g -> g.optString("date")));
        String next = "", last = "";
        for (JSONObject g : games) {
            JSONObject c = g.getJSONArray("competitions").getJSONObject(0);
            boolean done = c.getJSONObject("status").getJSONObject("type").optBoolean("completed");
            Instant t = java.time.OffsetDateTime.parse(g.getString("date")).toInstant();
            String comp = g.optJSONObject("league") != null ? g.getJSONObject("league").optString("abbreviation") : "";
            // The card title already names the league; only cup games get a label.
            comp = comp.isEmpty() || comp.equals(leagueName) ? "" : "  ·  " + comp;
            JSONArray cs = c.getJSONArray("competitors");
            JSONObject home = cs.getJSONObject(0), away = cs.getJSONObject(1);
            if (!"home".equals(home.optString("homeAway"))) { JSONObject x = home; home = away; away = x; }
            String h = home.getJSONObject("team").getString("shortDisplayName");
            String a = away.getJSONObject("team").getString("shortDisplayName");
            if (done) {
                last = h + " " + score(home) + "–" + score(away) + " " + a + comp;
            } else if (next.isEmpty() && t.isAfter(now)) {
                // From the team's side, short enough for a narrow card: "vs Man Utd (H) · Sat 31 Oct 19:30".
                boolean atHome = teamId.equals(home.getJSONObject("team").optString("id"));
                next = "vs " + (atHome ? a + " (H)" : h + " (A)") + "  ·  " + when.format(t) + comp;
            }
        }
        // ESPN lists the table in rank order already, but sort to be sure.
        List<JSONArray> sorted = new ArrayList<>();
        for (int i = 0; i < table.length(); i++) sorted.add(table.getJSONArray(i));
        sorted.sort(Comparator.comparingInt(r -> { try { return Integer.parseInt(r.getString(0)); } catch (Exception x) { return 99; } }));
        return new JSONObject().put("table", new JSONArray(sorted)).put("next", next).put("last", last).toString();
    }

    private static String score(JSONObject competitor) {
        Object s = competitor.opt("score");
        if (s instanceof JSONObject) return ((JSONObject) s).optString("displayValue", "?");
        return s == null ? "?" : String.valueOf(s);
    }

    private String fetchNews(String url) throws Exception {
        org.xmlpull.v1.XmlPullParser x = android.util.Xml.newPullParser();
        x.setInput(new java.io.StringReader(get(url)));
        JSONArray out = new JSONArray();
        boolean inItem = false;
        for (int ev = x.getEventType(); ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT && out.length() < 12; ev = x.next()) {
            if (ev == org.xmlpull.v1.XmlPullParser.START_TAG && "item".equals(x.getName())) inItem = true;
            else if (ev == org.xmlpull.v1.XmlPullParser.START_TAG && inItem && "title".equals(x.getName())) {
                String t = x.nextText().trim();
                int dash = t.lastIndexOf(" - ");
                out.put(dash > 20 ? t.substring(0, dash) : t);
                inItem = false;
            }
        }
        return out.toString();
    }

    // ---- plumbing ------------------------------------------------------------------------------

    private interface Fetch { String run() throws Exception; }
    private interface Parse<T> { T run(String json) throws Exception; }

    /** Deliver the cache at once, then refetch on the background thread if it is older than ttl. */
    private <T> void cached(String name, long ttl, Fetch fetch, Parse<T> parse, Sink<T> sink) {
        bg.execute(() -> {
            File f = new File(dir, name);
            try { if (f.exists()) { T v = parse.run(read(f)); main.post(() -> sink.accept(v)); } } catch (Exception ignored) { }
            if (!stale(f, ttl)) return;
            try {
                String json = fetch.run();
                T v = parse.run(json);
                write(f, json);
                main.post(() -> sink.accept(v));
            } catch (Exception ignored) {
                // offline or the source changed shape: the cached copy (if any) stays on screen
            }
        });
    }

    private static boolean stale(File f, long ttl) {
        return !f.exists() || System.currentTimeMillis() - f.lastModified() > ttl;
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) TvHome");
        if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
        return c;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static void download(String url, File dest) throws Exception {
        File tmp = new File(dest.getPath() + ".part");
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[32768];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        } finally {
            c.disconnect();
        }
        if (!tmp.renameTo(dest)) throw new IllegalStateException("rename");
    }

    /** Keep the newest MAX_WALLS photos (about 6 MB). */
    private void prune() {
        File[] walls = dir.listFiles((d, n) -> n.startsWith("wall_") && n.endsWith(".jpg"));
        if (walls == null || walls.length <= MAX_WALLS) return;
        Arrays.sort(walls, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < walls.length - MAX_WALLS; i++) {
            walls[i].delete();
            new File(walls[i].getPath().replace(".jpg", ".txt")).delete();
        }
    }

    private File anyCachedWall() {
        File[] walls = dir.listFiles((d, n) -> n.startsWith("wall_") && n.endsWith(".jpg"));
        if (walls == null || walls.length == 0) return null;
        Arrays.sort(walls);
        wallIndex = (wallIndex + 1) % walls.length;
        return walls[wallIndex];
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static void write(File f, String s) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }
}
