package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The YouTube tab: the newest videos of the channels in config.properties "youtube_channels", or else of the categories
 * picked on the TV (Settings > YouTube; the tab's first card asks, the first time), from YouTube's public channel feeds
 * (no key, no account). The channels opened most from here come first and a video opened here doesn't come back. They
 * open in SmartTube when it's on the TV, else in YouTube.
 *
 * YouTube's own recommendations and history stay inside SmartTube (Android shows other apps' home screen rows only to
 * the system's home screen), so SmartTube itself goes on the shelf.
 */
final class YouTube {
    /** SmartTube's builds (stable first), then YouTube itself. */
    private static final String[][] APPS = {
        {"org.smarttube.stable", "smarttube"},
        {"org.smarttube.beta", "smarttube"},
        {"com.teamsmart.videomanager.tv", "smarttube"},
        {"com.liskovsoft.smarttubetv.beta", "smarttube"},
        {"com.google.android.youtube.tv", "youtube"},
    };
    /**
     * The categories to pick from: key, name (pt, en, es, de, ru), then their channels. Ids, not handles: no page
     * lookup. YouTube sometimes answers 404 for a channel's feed for a while: that channel keeps its last videos.
     */
    static final String[][] CATEGORIES = {
        {"tech",
            "UCBJycsmduvYEL83R_U4JriQ",     // Marques Brownlee
            "UCXuqSBlHAE6Xw-yeJA0Tunw",     // Linus Tech Tips
            "UCMiJRAwDNSNzuYeN2uWa0pA",     // Mrwhosetheboss
            "UCsTcErHg8oDvUnTzoqsYeNw"},    // Unbox Therapy
        {"science",
            "UCHnyfMqiRRG1u-2MsSQLbXA",     // Veritasium
            "UCsXVk37bltHxD1rDPwtNM8Q",     // Kurzgesagt
            "UCY1kMZp36IQSyNx_9h4mpCg",     // Mark Rober
            "UC6107grRI4m0o2-emgoDnAA",     // SmarterEveryDay
            "UC6nSFpj9HTCZ5t-N3Rm3-HA"},    // Vsauce
        {"gaming",
            "UC7_YxT-KID8kRbqZo7MyscQ",     // Markiplier
            "UCYzPXprvl5Y-Sf0g4vX-m6g",     // jacksepticeye
            "UCKy1dAqELo0zrOtPkf0eTMw"},    // IGN
        {"cars",
            "UCjOl2AUblVmg2rA_cRgZkFg",     // Top Gear
            "UCsqjHFMB_JYTaEnf_vmTNqg",     // Doug DeMuro
            "UCUhFaUpnq31m6TNX2VKVSVA"},    // carwow
        {"sport",
            "UCB_qr75-ydFVKSF9Dmo6izg",     // Formula 1
            "UCWJ2lWNubArHWmf3FIHbfcQ"},    // NBA
        {"news",
            "UC16niRr50-MSBwiO3YDb3RA",     // BBC News
            "UCNye-wNBqNL5ZzHSJj3l8Bg",     // Al Jazeera English
            "UChqUTb7kYRX8-EiaN3XFrSQ"},    // Reuters
        {"food",
            "UCMyOj6fhvKFMjxUCp3b_3gA",     // Nick DiGiovanni
            "UChBEbMKI1eCcejTtmI32UEw"},    // Joshua Weissman
        {"music",
            "UC2Qw1dzXDBAZPwS7zm37g8g",     // COLORS
            "UC4eYXhJI4-7wSWc8UNRwD4A",     // NPR Music (Tiny Desk)
            "UC3I2GFN_F8WudD_2jUZbojA"},    // KEXP
        {"fun",
            "UCX6OQ3DkcsbYNE6H8uQQuVA",     // MrBeast
            "UC3XTzVzaHQEd30rQbuvCtTQ",     // Last Week Tonight
            "UC8-Th83bH_thdKZDJCrn88g"},    // The Tonight Show
        {"nature",
            "UCpVm7bg6pXKo1Pr6k5kxG9A",     // National Geographic
            "UCW39zufHfsuGgpLviKh297Q",     // DW Documentary
            "UCwmZiChSryoWQCZMIQezgTg"},    // BBC Earth
        {"kids",
            "UCoookXUzPciGrEZEXmh4Jjg",     // Sesame Street
            "UCAOtE1V7Ots4DjM8JLlrYgg",     // Peppa Pig
            "UCbCmjCuTUZos6Inko4u57UQ"},    // Cocomelon
    };
    /**
     * "Hide AI videos" (Settings > YouTube): titles about AI, in the TV's languages ("AI", ChatGPT, OpenAI, "KI",
     * "inteligência artificial"...). By the title only: YouTube's own label for made-with-AI videos isn't public.
     */
    private static final Pattern AI = Pattern.compile("(?iu)\\b(A\\.?I\\.?|AGI|ChatGPT|GPT-?\\d*|OpenAI|LLMs?|Gemini AI|"
            + "Copilot|Midjourney|Sora|DeepSeek|Grok|deepfakes?|chatbots?|artificial intelligence|machine learning|"
            + "inteligência artificial|inteligencia artificial|künstliche intelligenz|KI|kunstmatige intelligentie|"
            + "искусственн\\w* интеллект\\w*|ИИ|нейросет\\w*)\\b");
    /** Where each category's channels start in its row (after the key). */
    private static final int FIRST_CHANNEL = 1;
    /** None picked yet (or none at all): a mix of a few categories. */
    private static final String[] MIX = {"tech", "science", "cars", "sport", "news"};
    private static final long EVERY = 3600_000L, MAX_AGE = 30L * 24 * 3600_000L;
    private static final Pattern CHANNEL_ID = Pattern.compile("^UC[A-Za-z0-9_-]{22}$");
    private static final Pattern PAGE_ID = Pattern.compile("(?:\"externalId\":\"|youtube\\.com/channel/)(UC[A-Za-z0-9_-]{22})");

    private static final class Video {
        String id, title, channel, channelId;
        long published;
    }

    private static YouTube instance;

    static synchronized YouTube get(Context c) {
        if (instance == null) instance = new YouTube(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences store;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile List<Video> videos = new ArrayList<>();
    private volatile long fetchedAt, failedAt;
    private volatile String fetchedFor = "";    // the channels the videos were fetched for
    private volatile boolean complete;          // every channel answered last time (else: again in 10 minutes)
    private volatile boolean fetching;

    private YouTube(Context c) {
        context = c;
        store = c.getSharedPreferences("youtube", Context.MODE_PRIVATE);
        try {
            videos = parse(new JSONArray(store.getString("videos", "[]")));
            fetchedAt = store.getLong("at", 0);
            fetchedFor = store.getString("channels", "");
            complete = store.getBoolean("complete", false);
        } catch (Exception ignored) {
        }
    }

    /** The categories' name in the TV's language. */
    static String name(String[] category) {
        switch (category[0]) {
            case "tech": return Ui.tr("Tech");
            case "science": return Ui.tr("Science");
            case "gaming": return Ui.tr("Gaming");
            case "cars": return Ui.tr("Cars");
            case "sport": return Ui.tr("Sports");
            case "news": return Ui.tr("News");
            case "food": return Ui.tr("Food");
            case "music": return Ui.tr("Music");
            case "fun": return Ui.tr("Entertainment");
            case "nature": return Ui.tr("Nature & documentaries");
            case "kids": return Ui.tr("Kids");
            default: return category[0];
        }
    }

    /** config.properties "youtube_channels" set: they win, and the categories aren't asked. */
    static boolean configured() {
        return !Config.YOUTUBE_CHANNELS.trim().isEmpty();
    }

    /** The tab's first card asks for categories until they are picked once (an empty pick counts: the mix). */
    static boolean needsSetup() {
        return !configured() && Prefs.youtubeCategories() == null;
    }

    /** The channels to show: from config.properties, or from the picked categories (or the mix). */
    private static List<String> channels() {
        List<String> out = new ArrayList<>();
        if (configured()) {
            for (String s : Config.YOUTUBE_CHANNELS.split("[,\\s]+")) if (!s.trim().isEmpty()) out.add(s.trim());
            return out;
        }
        List<String> picked = Prefs.youtubeCategories();
        if (picked == null || picked.isEmpty()) picked = java.util.Arrays.asList(MIX);
        for (String[] c : CATEGORIES) {
            if (!picked.contains(c[0])) continue;
            for (int i = FIRST_CHANNEL; i < c.length; i++) if (!out.contains(c[i])) out.add(c[i]);
        }
        return out;
    }

    /** SmartTube or YouTube is on the TV: the home screen shows the YouTube tab. */
    boolean on() {
        return app() != null;
    }

    private String[] app() {
        for (String[] a : APPS) if (HomeApps.launchIntent(context.getPackageManager(), a[0]) != null) return a;
        return null;
    }

    /** A row of the YouTube tab: a category (or "YouTube" for the channels in config.properties) and its videos. */
    static final class Row {
        final String title;
        final List<Recs.Pick> picks = new ArrayList<>();

        Row(String title) {
            this.title = title;
        }
    }

    /**
     * The tab's rows: one per picked category (or the mix), each with its best-scored videos (newest first, the channels
     * opened most moved up), one per channel while there are other channels to pick from. Rows with no videos yet are
     * left out; the first time, the first row starts with the "choose your categories" card.
     */
    List<Row> rows() {
        List<Row> out = new ArrayList<>();
        String[] app = app();
        if (app == null) return out;
        long now = System.currentTimeMillis();
        Set<String> seen = new HashSet<>(store.getStringSet("seen", Collections.<String>emptySet()));
        List<Video> fresh = new ArrayList<>();
        boolean hideAi = Prefs.youtubeHideAi();
        for (Video v : videos) {
            if (!seen.contains(v.id) && now - v.published < MAX_AGE && !(hideAi && AI.matcher(v.title).find())) fresh.add(v);
        }
        Map<String, Double> score = new HashMap<>();
        for (Video v : fresh) score.put(v.id, score(v, now));
        Collections.sort(fresh, (a, b) -> Double.compare(score.get(b.id), score.get(a.id)));
        if (configured()) {
            fill(out, "YouTube", channels(), fresh, app, now);
        } else {
            List<String> picked = Prefs.youtubeCategories();
            if (picked == null || picked.isEmpty()) picked = java.util.Arrays.asList(MIX);
            for (String[] c : CATEGORIES) {
                if (picked.contains(c[0])) fill(out, name(c), java.util.Arrays.asList(c).subList(FIRST_CHANNEL, c.length), fresh, app, now);
            }
        }
        if (needsSetup()) {
            if (out.isEmpty()) out.add(new Row("YouTube"));
            List<Recs.Pick> first = out.get(0).picks;
            first.add(0, setupCard());
            while (first.size() > Recs.MAX_CARDS) first.remove(first.size() - 1);
        }
        return out;
    }

    /** Adds a row with the videos of these channels (handles were saved as their ids), if it has any. */
    private void fill(List<Row> rows, String title, List<String> channels, List<Video> sorted, String[] app, long now) {
        Set<String> wanted = new HashSet<>();
        for (String c : channels) wanted.add(CHANNEL_ID.matcher(c).matches() ? c : store.getString("id:" + c, c));
        Row row = new Row(title);
        // One per channel first, then a second from each, then any: a channel that posts all day doesn't fill the row.
        Map<String, Integer> perChannel = new HashMap<>();
        for (int pass = 1; pass <= 3 && row.picks.size() < Recs.MAX_CARDS; pass++) {
            for (Video v : sorted) {
                if (row.picks.size() >= Recs.MAX_CARDS) break;
                if (!wanted.contains(v.channelId)) continue;
                int count = perChannel.containsKey(v.channelId) ? perChannel.get(v.channelId) : 0;
                if ((pass < 3 && count >= pass) || contains(row.picks, v.id)) continue;
                perChannel.put(v.channelId, count + 1);
                row.picks.add(pick(v, app, now));
            }
        }
        if (!row.picks.isEmpty()) rows.add(row);
    }

    /** The first row's videos (Recs.now("yt"): the home screen preloads the backdrops of the first ones). */
    List<Recs.Pick> now() {
        List<Row> rows = rows();
        return rows.isEmpty() ? new ArrayList<>() : rows.get(0).picks;
    }

    // MARK: - Thumbnails for the rows: YouTube's 320×180 copy (16:9, always there), kept in memory (~115 KB each)

    private final Map<String, android.graphics.Bitmap> thumbs = new java.util.LinkedHashMap<String, android.graphics.Bitmap>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, android.graphics.Bitmap> eldest) {
            return size() > 60;
        }
    };
    private final Set<String> thumbPending = new HashSet<>();
    private final ExecutorService thumbIo = Executors.newFixedThreadPool(2);
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    /** The video's thumbnail, or null: it is fetched in the background and `ready` runs on the main thread. */
    android.graphics.Bitmap thumb(String video, Runnable ready) {
        synchronized (thumbs) {
            android.graphics.Bitmap b = thumbs.get(video);
            if (b != null || !thumbPending.add(video)) return b;
        }
        thumbIo.execute(() -> {
            android.graphics.Bitmap b = null;
            try {
                HttpURLConnection h = open("https://i.ytimg.com/vi/" + video + "/mqdefault.jpg");
                try (InputStream in = h.getInputStream()) {
                    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                    o.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
                    b = android.graphics.BitmapFactory.decodeStream(in, null, o);
                } finally {
                    h.disconnect();
                }
            } catch (Exception e) {
                AppLog.w("youtube thumbnail " + video + ": " + e.getMessage());
            }
            final android.graphics.Bitmap done = b;
            synchronized (thumbs) {
                if (done != null) thumbs.put(video, done);
            }
            // A failed one stays pending until a minute has passed: not fetched again on every frame.
            main.postDelayed(() -> { synchronized (thumbs) { thumbPending.remove(video); } }, done != null ? 0 : 60_000L);
            if (done != null) main.post(ready);
        });
        return null;
    }

    /** The first time: a card that opens Settings > YouTube (HomeView opens it, kind "yt-setup"). */
    private static Recs.Pick setupCard() {
        Recs.Pick p = new Recs.Pick();
        p.kind = "yt-setup";
        p.video = "setup";
        p.title = Ui.tr("Choose your YouTube categories");
        p.meta = Ui.tr("OK to choose · only this once");
        p.detailed = true;
        return p;
    }

    private static boolean contains(List<Recs.Pick> picks, String id) {
        for (Recs.Pick p : picks) if (id.equals(p.video)) return true;
        return false;
    }

    /** Newer is better (half after two days); each open of the channel from here adds about a day and a half. */
    private double score(Video v, long now) {
        double hours = Math.max(0, now - v.published) / 3600_000.0;
        int opens = store.getInt("opens:" + v.channelId, 0);
        return 1 / (1 + hours / 48) + 0.35 * Math.log(1 + opens);
    }

    private static Recs.Pick pick(Video v, String[] app, long now) {
        Recs.Pick p = new Recs.Pick();
        p.kind = "yt";
        p.video = v.id;
        p.title = v.title;
        p.backdrop = "https://i.ytimg.com/vi/" + v.id + "/maxresdefault.jpg";
        p.link = "https://www.youtube.com/watch?v=" + v.id;
        p.pkg = app[0];
        p.app = app[1];
        p.meta = v.channel + " · " + age(now - v.published) + " · " + Recs.serviceName(p);
        p.detailed = true;
        return p;
    }

    private static String age(long ms) {
        long minutes = Math.max(1, ms / 60_000L);
        if (minutes < 60) return Ui.trf("{n} min ago", "n", minutes);
        if (minutes < 24 * 60) return Ui.trf("{n} h ago", "n", minutes / 60);
        return Ui.plural("plural|one:{n} day ago|other:{n} days ago", minutes / 1440);
    }

    /** A video was opened from the tab: its channel moves up and the video leaves the tab. */
    void opened(Recs.Pick p) {
        if (p == null || !"yt".equals(p.kind)) return;
        String channel = null;
        for (Video v : videos) if (v.id.equals(p.video)) channel = v.channelId;
        Set<String> seen = new HashSet<>(store.getStringSet("seen", Collections.<String>emptySet()));
        seen.add(p.video);
        if (seen.size() > 300) seen.clear();    // old ones are past MAX_AGE anyway
        SharedPreferences.Editor e = store.edit().putStringSet("seen", seen);
        if (channel != null) e.putInt("opens:" + channel, store.getInt("opens:" + channel, 0) + 1);
        e.apply();
    }

    /** Fetches the feeds once an hour (after a failure, not again for 10 minutes); `done` runs when there's news. */
    void refreshIfStale(Runnable done) {
        if (!on() || fetching) return;
        List<String> channels = channels();
        String key = String.join(",", channels);
        boolean picked = !key.equals(fetchedFor);     // other categories: right away
        long now = System.currentTimeMillis();
        long every = complete ? EVERY : 10 * 60_000L;
        if (!picked && now - fetchedAt < every && now >= fetchedAt && !videos.isEmpty()) return;
        if (!picked && failedAt != 0 && now - failedAt >= 0 && now - failedAt < 10 * 60_000L) return;
        fetching = true;
        io.execute(() -> {
            try {
                List<Video> all = new ArrayList<>(), before = videos;
                String last = "";
                int failed = 0;
                for (String c : channels) {
                    String id = null;
                    try {
                        id = channelId(c);
                        all.addAll(videos(id));
                    } catch (Exception e) {
                        // YouTube answers 404 for a feed now and then: the channel keeps the videos it had.
                        failed++;
                        last = c + ": " + (e.getMessage() == null ? e.toString() : e.getMessage());
                        for (Video v : before) if (v.channelId.equals(id)) all.add(v);
                    }
                }
                if (failed > 0) AppLog.w("youtube: " + failed + " of " + channels.size() + " feeds failed (" + last + ")");
                if (all.isEmpty() && failed > 0) throw new IOException("no videos");
                videos = all;
                fetchedFor = key;
                fetchedAt = System.currentTimeMillis();
                complete = failed == 0;
                failedAt = failed == channels.size() ? fetchedAt : 0;
                save();
                done.run();
            } catch (Exception e) {
                failedAt = System.currentTimeMillis();
                AppLog.w("youtube: " + e.getMessage());
            } finally {
                fetching = false;
            }
        });
    }

    /** "UC…" as it is; "@handle" (or a channel's address) is looked up once on its page and remembered. */
    private String channelId(String c) throws IOException {
        if (CHANNEL_ID.matcher(c).matches()) return c;
        String saved = store.getString("id:" + c, null);
        if (saved != null) return saved;
        String url = c.startsWith("https://") ? c : "https://www.youtube.com/" + (c.startsWith("@") ? c : "@" + c);
        HttpURLConnection h = open(url);
        h.setRequestProperty("Cookie", "SOCS=CAI");    // past the EU consent page
        try (BufferedReader in = new BufferedReader(new InputStreamReader(h.getInputStream(), StandardCharsets.UTF_8))) {
            // The id is in the page's head: read only until it shows up (the whole page is ~3 MB).
            char[] buffer = new char[16384];
            StringBuilder s = new StringBuilder();
            int n, read = 0;
            while ((n = in.read(buffer)) > 0 && read < 1_500_000) {
                read += n;
                s.append(buffer, 0, n);
                Matcher m = PAGE_ID.matcher(s);
                if (m.find()) {
                    store.edit().putString("id:" + c, m.group(1)).apply();
                    return m.group(1);
                }
                if (s.length() > 4096) s.delete(0, s.length() - 4096);
            }
        } finally {
            h.disconnect();
        }
        throw new IOException("channel not found");
    }

    /**
     * The channel's newest uploads: its feed, or when YouTube refuses that (it answers 404 to every feed from an address
     * for hours at a time) the channel's Videos page.
     */
    private static List<Video> videos(String channelId) throws Exception {
        try {
            return feed(channelId);
        } catch (Exception feedError) {
            try {
                return page(channelId);
            } catch (Exception pageError) {
                throw new IOException("feed: " + feedError.getMessage() + "; page: " + pageError.getMessage());
            }
        }
    }

    private static final Pattern ITEM_TITLE = Pattern.compile("\"title\":\\{\"content\":\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern ITEM_ID = Pattern.compile("\"contentId\":\"([A-Za-z0-9_-]{11})\"");
    private static final Pattern ITEM_AGE = Pattern.compile("\"content\":\"(?:Streamed )?(\\d+)\\s?(mo|s|m|h|d|w|y|second|minute|hour|day|week|month|year)s?\\s?ago\"");
    private static final Pattern CHANNEL_NAME = Pattern.compile("\"channelMetadataRenderer\":\\{\"title\":\"((?:[^\"\\\\]|\\\\.)*)\"");

    /**
     * The channel's Videos page (no Shorts there): each video's id, title and age ("3d ago", in English: the page is
     * asked for in English). The age gives an approximate date. Upcoming videos (no age yet) are skipped.
     */
    private static List<Video> page(String channelId) throws Exception {
        HttpURLConnection h = open("https://www.youtube.com/channel/" + channelId + "/videos");
        h.setRequestProperty("Cookie", "SOCS=CAI");     // past the EU consent page
        h.setRequestProperty("Accept-Language", "en");
        String html;
        try (InputStream in = h.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            html = out.toString("UTF-8");
        } finally {
            h.disconnect();
        }
        Matcher name = CHANNEL_NAME.matcher(html);
        String channel = name.find() ? unescape(name.group(1)) : "";
        long now = System.currentTimeMillis();
        List<Video> out = new ArrayList<>();
        String marker = "\"richItemRenderer\":{\"content\":{\"lockupViewModel\"";
        for (int at = html.indexOf(marker); at >= 0 && out.size() < 15; ) {
            int next = html.indexOf(marker, at + marker.length());
            String item = html.substring(at, next < 0 ? Math.min(html.length(), at + 20000) : next);
            at = next;
            Matcher id = ITEM_ID.matcher(item), title = ITEM_TITLE.matcher(item), age = ITEM_AGE.matcher(item);
            if (!id.find() || !title.find() || !age.find()) continue;
            Video v = new Video();
            v.id = id.group(1);
            v.title = unescape(title.group(1));
            v.channel = channel;
            v.channelId = channelId;
            v.published = now - ago(Integer.parseInt(age.group(1)), age.group(2));
            out.add(v);
        }
        if (out.isEmpty()) throw new IOException("no videos on the page");
        return out;
    }

    private static long ago(int n, String unit) {
        long hour = 3600_000L;
        switch (unit) {
            case "s": case "second": return n * 1000L;
            case "m": case "minute": return n * 60_000L;
            case "h": case "hour": return n * hour;
            case "d": case "day": return n * 24 * hour;
            case "w": case "week": return n * 7 * 24 * hour;
            case "mo": case "month": return n * 30 * 24 * hour;
            default: return n * 365 * 24 * hour;
        }
    }

    /** A JSON string's text ("&" → "&", "\"" → '"'). */
    private static String unescape(String json) {
        try {
            return new JSONObject("{\"s\":\"" + json + "\"}").getString("s");
        } catch (Exception e) {
            return json;
        }
    }

    /** The channel's 15 newest uploads, without Shorts. */
    private static List<Video> feed(String channelId) throws Exception {
        HttpURLConnection h = open("https://www.youtube.com/feeds/videos.xml?channel_id=" + channelId);
        List<Video> out = new ArrayList<>();
        try (InputStream in = h.getInputStream()) {
            int code = h.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            XmlPullParser x = Xml.newPullParser();
            x.setInput(in, "UTF-8");
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            iso.setTimeZone(TimeZone.getTimeZone("UTC"));
            String channel = "";
            Video v = null;
            boolean shorts = false;
            for (int e = x.getEventType(); e != XmlPullParser.END_DOCUMENT; e = x.next()) {
                if (e == XmlPullParser.START_TAG) {
                    String tag = x.getName();
                    if (tag.equals("entry")) {
                        v = new Video();
                        v.channelId = channelId;
                        shorts = false;
                    } else if (v == null) {
                        if (tag.equals("title")) channel = x.nextText();
                    } else if (tag.equals("videoId") || tag.equals("yt:videoId")) {   // without namespaces, the prefix stays
                        v.id = x.nextText();
                    } else if (tag.equals("title") && v.title == null) {
                        v.title = x.nextText();
                    } else if (tag.equals("link")) {
                        shorts |= String.valueOf(x.getAttributeValue(null, "href")).contains("/shorts/");
                    } else if (tag.equals("published")) {
                        String t = x.nextText();
                        v.published = iso.parse(t.length() >= 19 ? t.substring(0, 19) : t).getTime();
                    }
                } else if (e == XmlPullParser.END_TAG && x.getName().equals("entry") && v != null) {
                    if (!shorts && v.id != null && v.title != null) {
                        v.channel = channel;
                        out.add(v);
                    }
                    v = null;
                }
            }
        } finally {
            h.disconnect();
        }
        return out;
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(8000);
        h.setReadTimeout(10000);
        h.setRequestProperty("User-Agent", "Mozilla/5.0");
        return h;
    }

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Video v : videos) {
                a.put(new JSONObject().put("id", v.id).put("title", v.title).put("channel", v.channel)
                        .put("channelId", v.channelId).put("published", v.published));
            }
            store.edit().putString("videos", a.toString()).putLong("at", fetchedAt).putString("channels", fetchedFor)
                    .putBoolean("complete", complete).apply();
        } catch (Exception ignored) {
        }
    }

    private static List<Video> parse(JSONArray a) {
        List<Video> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Video v = new Video();
            v.id = o.optString("id");
            v.title = o.optString("title");
            v.channel = o.optString("channel");
            v.channelId = o.optString("channelId");
            v.published = o.optLong("published");
            out.add(v);
        }
        return out;
    }
}
