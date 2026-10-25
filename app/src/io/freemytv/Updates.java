package io.freemytv;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.PersistableBundle;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Updates from GitHub's releases (Android only). Once a week, and from Home Settings > Updates, the latest release is
 * looked up; a newer one's FreeMyTV.apk is downloaded, checked against GitHub's SHA-256 and against this app's
 * signature, then handed to Android's installer, which asks before installing. Only the APK from the releases is
 * signed with the project's key: a build made on a computer has its own, so it's only told that a new version is out.
 *
 * The state is a small file in the app's storage: the week's job runs in the main process, Settings in :home.
 */
final class Updates {
    private Updates() {}

    private static final String LATEST = "https://api.github.com/repos/simbasjungles/free-my-tv/releases/latest";
    private static final String ASSET = "FreeMyTV.apk";
    private static final long WEEK = 7 * 24 * 3600_000L;
    private static final int JOB = 2900;
    static final String ACTION = "io.freemytv.UPDATE_STATUS";

    // What the last check found (state.json's "state").
    static final String CHECKING = "checking", CURRENT = "current", BUILD = "build", DOWNLOADING = "downloading",
            READY = "ready", FAILED = "failed";

    /** The week's check, as a job that waits for the network and survives restarts (scheduled once). */
    static void schedule(Context c) {
        JobScheduler scheduler = c.getSystemService(JobScheduler.class);
        if (scheduler == null || scheduler.getPendingJob(JOB) != null) return;
        PersistableBundle extras = new PersistableBundle();
        extras.putString("action", ModeJob.UPDATE);
        scheduler.schedule(new JobInfo.Builder(JOB, new ComponentName(c, ModeJob.class)).setExtras(extras)
                .setPeriodic(WEEK).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build());
    }

    // MARK: - State

    private static File stateFile(Context c) {
        return new File(c.getFilesDir(), "update.json");
    }

    static JSONObject state(Context c) {
        try (InputStream in = new FileInputStream(stateFile(c))) {
            return new JSONObject(read(in));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void save(Context c, JSONObject o) {
        File f = stateFile(c), tmp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(f);
    }

    private static void set(Context c, String state, String version, String error) {
        JSONObject o = state(c);
        try {
            o.put("state", state).put("version", version).put("error", error == null ? "" : error);
            if (!state.equals(CHECKING) && !state.equals(DOWNLOADING)) o.put("at", System.currentTimeMillis());
        } catch (org.json.JSONException ignored) {
        }
        save(c, o);
    }

    /** Home Settings' Updates row: what the last check found, or what to press. */
    static String summary(Context c) {
        JSONObject o = state(c);
        String v = o.optString("version"), state = o.optString("state");
        switch (state) {
            case CHECKING: return Ui.tr("Checking…");
            case DOWNLOADING: return Ui.tr("Downloading…");
            case CURRENT: return Ui.tr("Up to date");
            case BUILD: return Ui.trf("{version} is out: build it again", "version", v);
            case READY: return Ui.trf("Install {version}", "version", v);
            case FAILED: return Ui.trf("Couldn't update: {error}", "error", o.optString("error"));
            default: return Ui.tr("Check now");
        }
    }

    /** The home screen says once, per version, that one is ready (the week's check found it). */
    static void tellOnce(Activity a) {
        JSONObject o = state(a);
        String v = o.optString("version");
        if (!READY.equals(o.optString("state")) || v.equals(o.optString("told"))) return;
        try {
            save(a, o.put("told", v));
        } catch (org.json.JSONException ignored) {
        }
        Toast.makeText(a, Ui.trf("Free My TV {version} is ready: Home Settings > Updates.", "version", v), Toast.LENGTH_LONG).show();
    }

    // MARK: - Check and download

    /** Looks for a newer release and, on the APK from the releases, downloads it. Off the UI thread. */
    static void check(Context c) {
        String state = state(c).optString("state");
        if (state.equals(CHECKING) || state.equals(DOWNLOADING)) {
            // Left over from a check the system stopped: started again unless one is surely still running.
            if (System.currentTimeMillis() - stateFile(c).lastModified() < 10 * 60_000L) return;
        }
        String version = "";
        try {
            set(c, CHECKING, "", "");
            JSONObject release = new JSONObject(get(LATEST));
            version = release.optString("tag_name").replaceFirst("^[vV]", "");
            String installed = installedVersion(c);
            AppLog.w("update: latest release " + version + ", installed " + installed);
            if (compare(version, installed) <= 0) {
                set(c, CURRENT, version, "");
                return;
            }
            if (!Config.GENERIC) {
                set(c, BUILD, version, "");
                return;
            }
            JSONObject asset = null;
            JSONArray assets = release.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                if (ASSET.equals(assets.getJSONObject(i).optString("name"))) asset = assets.getJSONObject(i);
            }
            if (asset == null) throw new IOException(ASSET + " isn't in release " + version);
            File apk = apkFile(c, version);
            if (!apk.exists()) {
                set(c, DOWNLOADING, version, "");
                download(asset.getString("browser_download_url"), asset.optString("digest"), apk);
                AppLog.w("update: downloaded " + version + " (" + apk.length() / 1024 + " KB), SHA-256 as GitHub's");
            }
            if (!sameKey(c, apk)) {
                AppLog.w("update: " + version + " is signed with another key");
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
                set(c, BUILD, version, "");    // a copy built elsewhere with --generic: another key
                return;
            }
            set(c, READY, version, "");
            AppLog.w("update: " + version + " ready");
        } catch (Exception e) {
            set(c, FAILED, version, e.getMessage() == null ? e.toString() : e.getMessage());
            AppLog.w("update: " + e.getMessage());
        }
    }

    private static File apkFile(Context c, String version) {
        return new File(new File(c.getCacheDir(), "update"), "FreeMyTV-" + version + ".apk");
    }

    /** Downloaded updates go once one is installed (MY_PACKAGE_REPLACED). */
    static void cleanUp(Context c) {
        File[] files = new File(c.getCacheDir(), "update").listFiles();
        for (int i = 0; files != null && i < files.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
        set(c, CURRENT, installedVersion(c), "");
    }

    private static String installedVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    /** "1.10.0" vs "1.9": numbers compared one by one, missing ones as 0. */
    static int compare(String a, String b) {
        String[] x = a.split("[.\\-]"), y = b.split("[.\\-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? number(x[i]) : 0, q = i < y.length ? number(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D.*", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The APK into `to`, only if its SHA-256 is GitHub's ("sha256:…"; older releases have none). */
    private static void download(String url, String digest, File to) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        to.getParentFile().mkdirs();
        File part = new File(to.getPath() + ".part");
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        HttpURLConnection h = open(url);
        try {
            if (h.getResponseCode() != 200) throw new IOException("GitHub returned HTTP " + h.getResponseCode());
            try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    sha.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                }
            }
        } finally {
            h.disconnect();
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : sha.digest()) hex.append(String.format("%02x", b));
        if (digest.startsWith("sha256:") && !digest.substring(7).equalsIgnoreCase(hex.toString())) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new IOException("the download doesn't match GitHub's SHA-256");
        }
        if (!part.renameTo(to)) throw new IOException("couldn't save the update");
    }

    /** Signed with the same key as this app: Android would refuse it otherwise. */
    @SuppressWarnings("deprecation")   // GET_SIGNATURES: GET_SIGNING_CERTIFICATES is Android 9+
    private static boolean sameKey(Context c, File apk) throws PackageManager.NameNotFoundException {
        PackageManager pm = c.getPackageManager();
        PackageInfo theirs = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNATURES);
        PackageInfo ours = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNATURES);
        if (theirs == null || theirs.signatures == null || ours.signatures == null) return false;
        if (!c.getPackageName().equals(theirs.packageName)) return false;
        Signature[] a = theirs.signatures.clone(), b = ours.signatures.clone();
        Arrays.sort(a, (x, y) -> x.toCharsString().compareTo(y.toCharsString()));
        Arrays.sort(b, (x, y) -> x.toCharsString().compareTo(y.toCharsString()));
        return Arrays.equals(a, b);
    }

    // MARK: - Install

    /** Hands the downloaded APK to Android's installer, which asks first (and, the first time, to allow it). */
    static void install(Context c) {
        JSONObject o = state(c);
        String version = o.optString("version");
        File apk = apkFile(c, version);
        if (!READY.equals(o.optString("state")) || !apk.exists()) return;
        new Thread(() -> {
            PackageInstaller installer = c.getPackageManager().getPackageInstaller();
            try {
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(c.getPackageName());
                int id = installer.createSession(params);
                try (PackageInstaller.Session session = installer.openSession(id)) {
                    try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite(ASSET, 0, apk.length())) {
                        byte[] buffer = new byte[16384];
                        int n;
                        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                        session.fsync(out);
                    }
                    Intent done = new Intent(c, HomeReceiver.class).setAction(ACTION).putExtra("version", version);
                    // Mutable: the installer adds its status (FLAG_MUTABLE, Android 12+; its value, as Android 8 lacks it).
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT | (android.os.Build.VERSION.SDK_INT >= 31 ? 0x02000000 : 0);
                    session.commit(PendingIntent.getBroadcast(c, id, done, flags).getIntentSender());
                }
                AppLog.w("update: installing " + version);
            } catch (Exception e) {
                set(c, FAILED, version, e.getMessage() == null ? e.toString() : e.getMessage());
                AppLog.w("update install: " + e.getMessage());
            }
        }, "update").start();
    }

    /** The installer's answer (HomeReceiver): it asks the person first, or says why it didn't install. */
    static void status(Context c, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            AppLog.w("update: not installed (" + status + "): " + message);
            // Cancelled on the installer's screen: still ready, for another try.
            if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                set(c, FAILED, intent.getStringExtra("version"), message == null ? String.valueOf(status) : message);
            }
        }
    }

    // MARK: - HTTP

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(10000);
        h.setReadTimeout(30000);
        h.setRequestProperty("User-Agent", "FreeMyTV");
        return h;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection h = open(url);
        h.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            if (h.getResponseCode() != 200) throw new IOException("GitHub returned HTTP " + h.getResponseCode());
            try (InputStream in = h.getInputStream()) {
                return read(in);
            }
        } finally {
            h.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
