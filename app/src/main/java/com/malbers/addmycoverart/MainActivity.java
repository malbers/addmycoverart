package com.malbers.addmycoverart;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.malbers.addmycoverart.core.Album;
import com.malbers.addmycoverart.core.AlbumScanner;
import com.malbers.addmycoverart.core.CaaExtras;
import com.malbers.addmycoverart.core.ExtrasLookup;
import com.malbers.addmycoverart.core.FetchText;
import com.malbers.addmycoverart.core.FolderSummary;
import com.malbers.addmycoverart.core.ArtLookup;
import com.malbers.addmycoverart.core.LookupResult;
import com.malbers.addmycoverart.core.RipCheck;
import com.malbers.addmycoverart.core.ScanResult;
import com.malbers.addmycoverart.core.UpdateCheck;
import com.malbers.addmycoverart.core.UrlHttp;
import com.malbers.addmycoverart.ui.CoverLoader;
import com.malbers.addmycoverart.ui.CoverView;
import com.malbers.addmycoverart.ui.FetchJob;
import com.malbers.addmycoverart.ui.ItemAdapter;
import com.malbers.addmycoverart.ui.PictureViewer;
import com.malbers.addmycoverart.ui.Theme;
import com.malbers.addmycoverart.ui.TileGrid;
import com.malbers.addmycoverart.ui.Ui;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Five screens, each built fresh so nothing carries over: access, browse, results, fetching, report.
 */
public class MainActivity extends Activity {
    private enum Screen { ACCESS, BROWSE, SCANNING, RESULTS, ALBUM, FETCHING, STOPPED, REPORT, RIPCHECK, RIPREPORT }

    private static final int REQ_LEGACY = 1;
    private static final String CHANNEL = "https://www.youtube.com/@malbers";

    private LinearLayout root;
    private Screen screen;
    private File browseDir; // null = list of storage volumes
    private ScanResult scan;
    private File scanRoot;
    private FetchJob job;
    private SharedPreferences prefs;
    private boolean sentToSettings;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        prefs = getSharedPreferences("amca", MODE_PRIVATE);
        Theme.init(this);
        root = Ui.column(this);
        root.setBackgroundColor(Theme.GROUND);
        setContentView(root);
        start();
        checkForUpdate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (screen != Screen.ACCESS) return;
        if (hasAccess()) start();
        // Back from the All files screen: go straight on to the second prompt, once (a Deny must not loop).
        else if (sentToSettings && hasManager()) {
            sentToSettings = false;
            requestAccess();
        }
    }

    private void start() {
        if (!hasAccess()) { showAccess(); return; }
        // tags5: a fresh launch opens the main screen (Michael kept landing on the last folder he'd scanned,
        // "even if I wasn't there last time"); Back from an album still returns to where he was
        showBrowse(null);
    }

    // ---------- frame ----------

    /** Clears everything and lays out header, body and bottom buttons. */
    private void frame(Screen s, String subtitle, View body, View... buttons) {
        frameWithLeft(s, subtitle, body, null, buttons);
    }

    /** As {@link #frame}, with an optional view at the bottom left, opposite the buttons. */
    private void frameWithLeft(Screen s, String subtitle, View body, View left, View... buttons) {
        frameFull(s, subtitle, body, false, left, null, buttons);
    }

    /**
     * 1.1.0 (handoff "Global layout"): header 84 with a bottom rule, content, action bar 96 with a top rule.
     * fullBleed = the body lays out its own margins (rebuilt screens); otherwise it gets the 40 screen margin.
     * note = the short reassurance line to the left of the primary (17, text-3, up to 2 lines).
     */
    private void frameFull(Screen s, String subtitle, View body, boolean fullBleed, View left, String note, View... buttons) {
        screen = s;
        root.removeAllViews();

        LinearLayout header = Ui.row(this);
        header.setPadding(Theme.d(40), 0, Theme.d(32), 0);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        icon.setBackground(Theme.round(Theme.SURFACE, 10));
        icon.setClipToOutline(true);
        int iz = Theme.d(44);
        header.addView(icon, new LinearLayout.LayoutParams(iz, iz));
        LinearLayout titles = Ui.column(this);
        titles.setPadding(Theme.d(16), 0, Theme.d(16), 0);
        TextView name = Theme.oneLine(Theme.text(this, "AddMyCoverArt", 26, 600, Theme.TEXT));
        name.setLetterSpacing(-0.01f);
        titles.addView(name);
        if (subtitle != null) {
            // 1.1.0 (Michael: "the path should scroll if too long, helps me see if it is CD1 or CD2")
            TextView sub = Theme.scrolling(Theme.monoText(this, subtitle, 15, 400, Theme.TEXT_3));
            sub.setPadding(0, Theme.d(4), 0, 0);
            titles.addView(sub);
        }
        header.addView(titles, Ui.weight(1));
        // P2: the update pill lives in the header now, left of "by @malbers", on every screen
        View pill = updatePill();
        if (pill != null) {
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, Theme.d(48 + 12));
            pp.rightMargin = Theme.d(16 - 6);
            header.addView(pill, pp);
        }
        TextView credit = Theme.oneLine(Theme.text(this, "by @malbers", 18, 400, Theme.TEXT_3));
        credit.setGravity(Gravity.CENTER_VERTICAL);
        credit.setMinHeight(Theme.d(48));
        credit.setPadding(Theme.d(12), 0, Theme.d(12), 0);
        credit.setFocusable(true);
        credit.setBackground(focusWash(24));
        credit.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(CHANNEL)));
            } catch (Exception ignored) {
                // No browser on this device; the credit stays as text.
            }
        });
        header.addView(credit);
        root.addView(header, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Theme.d(84)));
        root.addView(rule());

        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        if (!fullBleed) {
            bp.leftMargin = bp.rightMargin = Theme.d(40);
            bp.topMargin = bp.bottomMargin = Theme.d(16);
        }
        root.addView(body, bp);

        if (buttons.length > 0 || left != null) {
            root.addView(rule());
            LinearLayout bar = Ui.row(this);
            // P6 (Michael 2026-11-23: "all nav buttons should be on bottom right"): the note sits bottom LEFT, and
            // every button (secondary/back first, the primary last) is grouped at the bottom right.
            bar.setPadding(Theme.d(40), 0, Theme.d(40 - 6), 0);
            if (note != null) {
                TextView n = Theme.text(this, note, 17, 400, Theme.TEXT_3);
                n.setMaxLines(2);
                n.setMaxWidth(Theme.d(400));
                Theme.lineHeight(n, 1.3f);
                bar.addView(n);
            }
            // Zero height: a bare View sized wrap_content grows to fill the screen and squeezes the list out.
            bar.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1));
            if (left != null) bar.addView(left, Ui.wrap(Theme.d(16)));
            for (View b : buttons) bar.addView(b, Ui.wrap(Theme.d(4)));
            root.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Theme.d(96)));
        }
    }

    /** A 1 px rule in the "line" colour (header bottom, action bar top). */
    private View rule() {
        View v = new View(this);
        v.setBackgroundColor(Theme.LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        return v;
    }

    /** Transparent at rest, a light wash when focused, pressed or hovered (the credit link). */
    private static android.graphics.drawable.Drawable focusWash(float radius) {
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.Drawable on = Theme.round(Theme.WASH, radius, Theme.ACCENT, 3);
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{android.R.attr.state_pressed}, Theme.round(Theme.WASH, radius));
        s.addState(new int[]{android.R.attr.state_hovered}, Theme.round(Theme.WASH, radius));
        s.addState(new int[]{}, Theme.round(android.graphics.Color.TRANSPARENT, radius));
        return s;
    }

    // ---------- update check ----------

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    /** At most once a day, ask GitHub for the latest release; the answer is kept for the pill below. */
    private void checkForUpdate() {
        long now = System.currentTimeMillis();
        if (now - prefs.getLong("upd_checked", 0) < DAY_MS) return;
        prefs.edit().putLong("upd_checked", now).apply();
        new Thread(() -> {
            UpdateCheck.Release r = UpdateCheck.latest(new UrlHttp(BuildConfig.USER_AGENT));
            if (r == null) return;
            prefs.edit().putString("upd_version", r.version).putString("upd_url", r.url).apply();
            runOnUiThread(() -> {
                if (screen == Screen.BROWSE) showBrowse(browseDir);
            });
        }, "update-check").start();
    }

    /**
     * P2, header update pill (handoff "Header"): height 48, radius 24, green stroke and tint, a green dot, 18 600.
     * "Update x · Download", then "Downloading x · 46%" (not focusable), then "Install x". Null when up to date.
     */
    private View updatePill() {
        String v = prefs.getString("upd_version", null);
        String url = prefs.getString("upd_url", null);
        if (v == null || url == null || !UpdateCheck.isNewer(v, BuildConfig.VERSION_NAME)) return null;
        LinearLayout pill = Ui.row(this);
        int ring = Theme.d(6);
        pill.setPadding(Theme.d(20) + ring, 0, Theme.d(20) + ring, 0);
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.Drawable body = new android.graphics.drawable.InsetDrawable(
                Theme.round(Theme.SUCCESS_TINT, 24, 0x733CC46E, 1), ring, ring, ring, ring);
        android.graphics.drawable.GradientDrawable focus = Theme.round(android.graphics.Color.TRANSPARENT, 24 + 6, Theme.ACCENT, 3);
        bg.addState(new int[]{android.R.attr.state_focused},
                new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{focus, body}));
        bg.addState(new int[]{}, body);
        pill.setBackground(bg);
        pill.setPadding(Theme.d(20) + ring, 0, Theme.d(20) + ring, 0);
        View dot = new View(this);
        android.graphics.drawable.GradientDrawable dd = new android.graphics.drawable.GradientDrawable();
        dd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dd.setColor(Theme.SUCCESS);
        dot.setBackground(dd);
        int dz = Theme.d(8);
        pill.addView(dot, new LinearLayout.LayoutParams(dz, dz));
        updateText = Theme.oneLine(Theme.text(this, "", 18, 600, Theme.SUCCESS));
        LinearLayout.LayoutParams tp = Ui.wrap(Theme.d(10));
        pill.addView(updateText, tp);
        updatePillView = pill;
        showUpdateState(v);
        pill.setOnClickListener(x -> {
            if ("done".equals(downloadState)) {
                if (!installDownloaded(v)) toast("Saved to Download. Open Files › Storage › Download and tap it to install");
            } else if (downloadId < 0 || "failed".equals(downloadState)) {
                downloadUpdate(v, url);
            }
        });
        return pill;
    }

    private TextView updateText;
    private View updatePillView;
    private long downloadId = -1;
    private String downloadState; // null, "running", "done", "failed"
    private int downloadPct = -1;

    private void showUpdateState(String v) {
        if (updateText == null) return;
        boolean running = "running".equals(downloadState);
        if (running) updateText.setText("Downloading " + v + (downloadPct >= 0 ? " · " + downloadPct + "%" : "…"));
        else if ("done".equals(downloadState)) updateText.setText("Install " + v);
        else if ("failed".equals(downloadState)) updateText.setText("Download failed · tap to try again");
        else updateText.setText("Update " + v + " · Download");
        if (updatePillView != null) {
            updatePillView.setFocusable(!running);
            updatePillView.setClickable(!running);
        }
    }

    /** EverSignal fix13: hand the downloaded APK to Android's installer. False = not possible here (Files path). */
    private boolean installDownloaded(String v) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                // one time: Android's own "Install unknown apps" switch for AddMyCoverArt
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                toast("Allow AddMyCoverArt to install apps, then come back and tap Install " + v + " again");
                return true;
            }
            DownloadManager dm = getSystemService(DownloadManager.class);
            Uri apk = downloadId >= 0 ? dm.getUriForDownloadedFile(downloadId) : null;
            if (apk == null) return false;
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(apk, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show();
    }

    /**
     * Saves the APK to the shared Download folder with Android's own download service, so no browser
     * is needed (most Eversolos have none). Falls back to a browser only if that service is missing.
     */
    private void downloadUpdate(String v, String url) {
        DownloadManager dm = getSystemService(DownloadManager.class);
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url))
                    .setTitle("AddMyCoverArt " + v)
                    .setMimeType("application/vnd.android.package-archive")
                    .addRequestHeader("User-Agent", BuildConfig.USER_AGENT)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "AddMyCoverArt-" + v + ".apk");
            downloadId = dm.enqueue(req);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception ignored) {
                downloadState = "failed";
                showUpdateState(v);
            }
            return;
        }
        downloadState = "running";
        showUpdateState(v);
        pollDownload(dm, v);
    }

    private void pollDownload(DownloadManager dm, String v) {
        root.postDelayed(() -> {
            int status = -1;
            try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(downloadId))) {
                if (c != null && c.moveToFirst()) {
                    status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    long got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    long total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    downloadPct = total > 0 ? (int) Math.min(100, got * 100 / total) : -1;
                }
            } catch (Exception ignored) {
                // treated as failed below
            }
            if (status == DownloadManager.STATUS_SUCCESSFUL) downloadState = "done";
            else if (status == DownloadManager.STATUS_FAILED || status == -1) downloadState = "failed";
            else {
                showUpdateState(v);
                pollDownload(dm, v);
                return;
            }
            showUpdateState(v);
        }, 500);
    }

    private LinearLayout card() {
        LinearLayout c = Ui.column(this);
        c.setBackground(Ui.rounded(this, Ui.CARD, 16));
        int p = Ui.dp(this, 6);
        c.setPadding(p, p, p, p);
        return c;
    }

    private ListView list(ItemAdapter adapter) {
        ListView l = new ListView(this);
        l.setAdapter(adapter);
        l.setDivider(null);
        l.setSelector(Ui.rounded(this, Ui.CARD_HI, 10));
        l.setCacheColorHint(0);
        return l;
    }

    private TextView chip(String s, int color) {
        TextView t = Ui.text(this, s, 15, color, true);
        t.setBackground(Ui.rounded(this, Ui.CARD, 18));
        int h = Ui.dp(this, 14);
        t.setPadding(h, Ui.dp(this, 7), h, Ui.dp(this, 7));
        return t;
    }

    // ---------- access ----------

    private boolean hasManager() {
        return Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
    }

    private boolean hasLegacy() {
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    // Both are needed on Android 11 with targetSdk 29: All files access alone leaves attached drives looking empty.
    private boolean hasAccess() {
        return hasManager() && hasLegacy();
    }

    /** 1.1.0, handoff frame 01a: the promise as a headline, and a diagram of the one file the app adds. */
    private void showAccess() {
        LinearLayout body = Ui.row(this);
        body.setPadding(Theme.d(56), Theme.d(40), Theme.d(56), Theme.d(16));
        body.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout text = Ui.column(this);
        TextView label = Theme.monoText(this, "ONE PERMISSION FIRST", 15, 400, Theme.ACCENT);
        label.setLetterSpacing(0.14f);
        text.addView(label);
        TextView head = Theme.lineHeight(Theme.text(this, "Your music stays exactly as it is.", 44, 700, Theme.TEXT), 1.1f);
        head.setLetterSpacing(-0.02f);
        head.setPadding(0, Theme.d(16), 0, Theme.d(20));
        text.addView(head);
        TextView para = Theme.lineHeight(Theme.text(this, "To add covers, AddMyCoverArt needs to see your music folders. "
                + "It only ever adds a picture next to your tracks. It never changes, moves or deletes a music file.",
                22, 400, Theme.TEXT_2), 1.45f);
        para.setMaxWidth(Theme.d(620));
        text.addView(para);
        text.addView(step("1", "Turn on “Allow access to manage all files”, then come back."), stepParams(28));
        text.addView(step("2", "Tap Allow on the pop-up."), stepParams(12));
        body.addView(text, Ui.weight(1));

        LinearLayout right = Ui.column(this);
        LinearLayout card = Ui.column(this);
        card.setBackground(Theme.round(Theme.SURFACE, 18, 0x1AF2EDE4, 1));
        card.setPadding(Theme.d(22), Theme.d(20), Theme.d(22), Theme.d(20));
        TextView path = Theme.monoText(this, "Music / Agnes Obel / Aventine /", 15, 400, Theme.TEXT_3);
        path.setPadding(Theme.d(12), 0, Theme.d(12), Theme.d(10));
        card.addView(path);
        for (String f : new String[]{"01 Chord Left.flac", "02 Fuel to Fire.flac", "03 Dorian.flac", "04 Aventine.flac",
                "05 Run Cried the Crawling.flac"}) {
            card.addView(fileRow(f, "unchanged", Theme.TEXT_2, Theme.TEXT_4, false));
        }
        TextView more = Theme.monoText(this, "… 6 more tracks, unchanged", 15, 400, Theme.TEXT_4);
        more.setPadding(Theme.d(12), Theme.d(6), Theme.d(12), Theme.d(10));
        card.addView(more);
        card.addView(fileRow("+ folder.jpg", "added", Theme.SUCCESS, Theme.SUCCESS, true));
        right.addView(card);
        TextView cap = Theme.text(this, "This is everything AddMyCoverArt writes to an album.", 17, 400, Theme.TEXT_3);
        cap.setPadding(Theme.d(4), Theme.d(14), 0, 0);
        right.addView(cap);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(Theme.d(540), LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.leftMargin = Theme.d(64);
        body.addView(right, rp);

        TextView grant = Theme.button(this, "Grant access", Theme.PRIMARY, v -> requestAccess());
        frameFull(Screen.ACCESS, "Adds missing album covers", body, true, null, null, grant);
        grant.requestFocus();
    }

    private LinearLayout step(String n, String s) {
        LinearLayout r = Ui.row(this);
        r.setGravity(Gravity.TOP);
        TextView num = Theme.monoText(this, n, 17, 400, Theme.ACCENT);
        num.setPadding(0, Theme.d(2), Theme.d(14), 0);
        r.addView(num);
        r.addView(Theme.text(this, s, 20, 400, Theme.TEXT), Ui.weight(1));
        return r;
    }

    private static LinearLayout.LayoutParams stepParams(float top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = Theme.d(top);
        return p;
    }

    private LinearLayout fileRow(String name, String state, int nameCol, int stateCol, boolean added) {
        LinearLayout r = Ui.row(this);
        r.setPadding(Theme.d(12), Theme.d(added ? 12 : 6), Theme.d(12), Theme.d(added ? 12 : 6));
        if (added) r.setBackground(Theme.round(Theme.SUCCESS_TINT, 10, 0x593CC46E, 1));
        TextView a = Theme.oneLine(Theme.monoText(this, name, 17, added ? 500 : 400, nameCol));
        r.addView(a, Ui.weight(1));
        r.addView(Theme.monoText(this, state, 17, added ? 500 : 400, stateCol));
        return r;
    }

    private void requestAccess() {
        if (hasManager()) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_LEGACY);
            return;
        }
        sentToSettings = true;
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req == REQ_LEGACY && hasAccess()) start();
    }

    // ---------- browse (P2, handoff 02a-c: a cover grid) ----------

    private int browseGen;

    private static String plural(int n, String w) {
        return n + " " + w + (n == 1 ? "" : "s");
    }

    /** True when the folder holds audio files directly (it is an album, not a folder of albums). */
    private static boolean holdsAudio(File dir) {
        String[] names = dir.list();
        if (names == null) return false;
        for (String n : names) if (AlbumScanner.isAudioName(n)) return true;
        return false;
    }

    private void showBrowse(File dir) {
        // An album folder is shown from its parent (e.g. "Back to …" after scanning one album).
        if (dir != null && !isVolumeRoot(dir) && holdsAudio(dir) && dir.getParentFile() != null
                && FolderSummary.of(dir).isAlbum()) dir = dir.getParentFile();
        browseDir = dir;
        final int gen = ++browseGen;
        List<File> kids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        if (dir == null) {
            for (File[] v : volumes()) {
                kids.add(v[0]);
                names.add(v[1].getName());
            }
        } else {
            File[] ks = dir.listFiles(f -> f.isDirectory() && !f.getName().startsWith("."));
            if (ks != null) {
                Arrays.sort(ks, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                for (File k : ks) {
                    kids.add(k);
                    names.add(k.getName());
                }
            }
        }
        if (dir != null && kids.isEmpty()) {
            showEmptyFolder(dir);
            return;
        }

        LinearLayout body = Ui.column(this);
        LinearLayout head = headingRow();
        TextView name = Theme.oneLine(Theme.text(this, dir == null ? "Your drives" : dir.getName(), 30, 700, Theme.TEXT));
        name.setLetterSpacing(-0.01f);
        head.addView(name);
        TextView count = Theme.text(this, dir == null ? plural(kids.size(), "drive") : plural(kids.size(), "folder"), 19, 400, Theme.TEXT_3);
        head.addView(count, Ui.wrap(Theme.d(16)));
        TextView miss = Theme.text(this, "", 19, 500, Theme.ACCENT);
        miss.setVisibility(View.GONE);
        head.addView(miss, Ui.wrap(Theme.d(16)));
        TextView extra = Theme.text(this, "", 19, 400, Theme.TEXT_3);
        extra.setVisibility(View.GONE);
        head.addView(extra, Ui.wrap(Theme.d(16)));
        body.addView(head);

        TileGrid grid = new TileGrid(this);
        List<TileGrid.Tile> tiles = new ArrayList<>();
        for (int i = 0; i < kids.size(); i++) {
            TileGrid.Tile t = new TileGrid.Tile();
            File k = kids.get(i);
            t.folder = true;
            t.title = names.get(i);
            t.meta = "";
            t.tag = k;
            File from = dir;
            t.onClick = v -> {
                savePlace(from, k);
                showBrowse(k);
            };
            tiles.add(t);
        }
        grid.addTiles(tiles);
        body.addView(grid, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        String sub = dir == null ? "Choose where your music is" : dir.getPath();
        if (dir == null) {
            frameFull(Screen.BROWSE, sub, body, true, null, null);
        } else {
            File here = dir;
            frameFull(Screen.BROWSE, sub, body, true, Theme.button(this, "Up", Theme.GHOST, v -> goUp()),
                    "Finds albums without a cover. Changes nothing.",
                    Theme.button(this, "Scan this folder", Theme.PRIMARY, v -> startScan(here)));
        }
        currentGrid = grid;
        if (!restoreBrowsePlace(grid, dir)) grid.focusFirstTile();

        // Fill in each tile (album or folder, covers, counts) off the main thread, a few at a time.
        boolean drives = dir == null;
        File here0 = dir;
        new Thread(() -> {
            int[] totals = new int[5]; // album tiles, folder tiles, without a cover (album tiles), (folder tiles), with extra art
            for (int i = 0; i < kids.size(); i++) {
                if (gen != browseGen) return;
                FolderSummary s = FolderSummary.of(kids.get(i));
                TileGrid.Tile t = tiles.get(i);
                if (s.isAlbum()) {
                    totals[0]++;
                    if (s.art == null) totals[2]++;
                    if (s.extras > 0) totals[4]++;
                } else {
                    totals[1]++;
                    totals[3] += s.missing;
                    totals[4] += s.albumsWithExtras;
                }
                runOnUiThread(() -> {
                    if (gen != browseGen) return;
                    applySummary(t, s, here0);
                    scheduleGridRefresh(grid);
                });
            }
            runOnUiThread(() -> {
                if (gen != browseGen || drives) return;
                grid.refresh();
                int albumTiles = totals[0], folderTiles = totals[1];
                int m = totals[2] + totals[3];
                if (albumTiles > 0 && folderTiles == 0) count.setText(plural(albumTiles, "album"));
                else if (folderTiles > 0 && albumTiles > 0) count.setText(plural(folderTiles, "folder") + " · " + plural(albumTiles, "album"));
                if (m > 0) {
                    miss.setText("· " + m + (albumTiles == 0 ? (m == 1 ? " album" : " albums") : "") + " without a cover");
                    miss.setVisibility(View.VISIBLE);
                }
                // 1.1.0 (Michael: at the top level "it's not clear how many booklets… so I can see which to fill in")
                if (totals[4] > 0) {
                    extra.setText("· " + totals[4] + " with extra art");
                    extra.setVisibility(View.VISIBLE);
                }
            });
        }, "browse").start();
    }

    private boolean refreshQueued;

    /** At most four re-binds a second while summaries stream in, so covers on screen aren't restarted constantly. */
    private void scheduleGridRefresh(TileGrid grid) {
        if (refreshQueued) return;
        refreshQueued = true;
        root.postDelayed(() -> {
            refreshQueued = false;
            grid.refresh();
        }, 250);
    }

    private void applySummary(TileGrid.Tile t, FolderSummary s, File browsing) {
        if (s.isAlbum()) {
            Album a = s.toAlbum();
            t.folder = false;
            t.art = s.art;
            t.meta = s.art != null ? TileGrid.plain(plural(s.tracks, "track"), Theme.TEXT_3) : TileGrid.plain("No cover", Theme.ACCENT);
            t.badge = s.extras > 0 ? "+" + s.extras : null;
            t.onClick = v -> {
                savePlace(browsing, a.dir);
                showAlbum(a, FROM_BROWSE);
            };
            return;
        }
        t.folder = true;
        t.badge = s.albumsWithExtras > 0 ? s.albumsWithExtras + "/" + s.albums : null;
        List<File> cells = new ArrayList<>();
        for (FolderSummary.Cell c : s.mosaic) cells.add(c.art);
        t.cells = cells;
        t.cellsKnown = true;
        if (s.albums > 0) {
            t.meta = TileGrid.twoTone(plural(s.albums, "album"), Theme.TEXT_3,
                    s.missing > 0 ? " · " + s.missing + " no cover" : null, Theme.ACCENT);
        } else if (s.subfolders > 0) {
            t.meta = TileGrid.plain(plural(s.subfolders, "folder"), Theme.TEXT_3);
        } else {
            t.meta = TileGrid.plain("Empty", Theme.TEXT_3);
        }
    }

    /** Heading row above a grid (handoff: padding 22 40 6, baseline aligned, gap 16). */
    private LinearLayout headingRow() {
        LinearLayout head = Ui.row(this);
        head.setGravity(Gravity.NO_GRAVITY);
        head.setBaselineAligned(true);
        head.setPadding(Theme.d(40), Theme.d(22), Theme.d(40), Theme.d(6));
        return head;
    }

    /** 02c: nothing to show in this folder. */
    private void showEmptyFolder(File dir) {
        frameFull(Screen.BROWSE, dir.getPath(), emptyBody("There are no music files or album folders in " + dir.getName()
                + ". Go up and pick the folder that holds your albums."), true, null, null,
                Theme.button(this, "Up one folder", Theme.PRIMARY, v -> goUp()));
    }

    private LinearLayout emptyBody(String text) {
        LinearLayout body = Ui.column(this);
        body.setGravity(Gravity.CENTER);
        body.setPadding(Theme.d(40), 0, Theme.d(40), Theme.d(40));
        CoverView blank = new CoverView(this, Theme.d(120));
        blank.setBlank(CoverView.MUTED, "");
        body.addView(blank);
        TextView h = Theme.text(this, "No albums in this folder", 30, 700, Theme.TEXT);
        h.setPadding(0, Theme.d(16), 0, Theme.d(16));
        body.addView(h);
        TextView p = Theme.lineHeight(Theme.text(this, text, 21, 400, Theme.TEXT_2), 1.45f);
        p.setGravity(Gravity.CENTER_HORIZONTAL);
        p.setMaxWidth(Theme.d(620));
        body.addView(p);
        return body;
    }

    /** {dir, label-as-File-name} pairs for mounted storage. */
    private List<File[]> volumes() {
        List<File[]> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 30) {
            for (StorageVolume v : getSystemService(StorageManager.class).getStorageVolumes()) {
                if (!Environment.MEDIA_MOUNTED.equals(v.getState()) || v.getDirectory() == null) continue;
                out.add(new File[]{v.getDirectory(), new File(v.getDescription(this))});
            }
        } else {
            out.add(new File[]{Environment.getExternalStorageDirectory(), new File("Internal storage")});
            File[] others = new File("/storage").listFiles();
            if (others != null) for (File f : others) {
                if (f.getName().equals("emulated") || f.getName().equals("self") || !f.canRead()) continue;
                out.add(new File[]{f, new File(f.getName())});
            }
        }
        return out;
    }

    private boolean isVolumeRoot(File f) {
        for (File[] v : volumes()) if (v[0].equals(f)) return true;
        return false;
    }

    private void goUp() {
        if (browseDir == null) return;
        File parent = isVolumeRoot(browseDir) ? null : browseDir.getParentFile();
        // p3c: back in the parent, on the folder we came out of (its place if we were there, else scrolled to it)
        String k = placeKey(parent);
        Object[] p = places.get(k);
        if (p == null || !browseDir.equals(p[1])) places.put(k, new Object[]{p == null ? null : p[0], browseDir});
        restoreNext = true;
        showBrowse(parent);
    }

    // ---------- scan (P2, handoff 03a: tiles appear as albums are found) ----------

    private volatile boolean scanCancelled;

    private static File artFile(Album a) {
        return a.existingArt == null ? null : new File(a.dir, a.existingArt);
    }

    private void startScan(File dir) {
        prefs.edit().putString("last", dir.getPath()).apply();
        scanRoot = dir;
        scanCancelled = false;
        final int gen = ++browseGen;

        LinearLayout body = Ui.column(this);
        LinearLayout head = headingRow();
        head.addView(Theme.text(this, "Looking for albums…", 30, 700, Theme.TEXT));
        TextView count = Theme.monoText(this, plural(0, "album") + " found", 18, 400, Theme.TEXT_3);
        head.addView(count, Ui.wrap(Theme.d(16)));
        TextView miss = Theme.monoText(this, "", 18, 400, Theme.ACCENT);
        head.addView(miss, Ui.wrap(Theme.d(16)));
        body.addView(head);
        TileGrid grid = new TileGrid(this);
        body.addView(grid, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        TextView stop = Theme.button(this, "Stop", Theme.GHOST, v -> {
            scanCancelled = true;
            showBrowse(dir);
        });
        // P6 (Michael: Stop on the left was "jarring"): Stop sits where "Scan this folder" was
        frameFull(Screen.SCANNING, "Looking for albums in " + dir.getName() + "…", body, true, null,
                "Reading folders only. Nothing is being written.", stop);
        stop.requestFocus();

        List<Album> found = new ArrayList<>();
        int[] shown = {0, 0}; // albums shown, of them without a cover
        Runnable flush = () -> {
            if (gen != browseGen) return;
            List<Album> batch;
            synchronized (found) {
                batch = new ArrayList<>(found.subList(shown[0], found.size()));
            }
            for (Album a : batch) {
                TileGrid.Tile t = new TileGrid.Tile();
                t.title = a.dir.getName();
                t.art = artFile(a);
                t.animateIn = true;
                grid.appendTile(t);
                shown[0]++;
                if (a.existingArt == null) shown[1]++;
            }
            count.setText(plural(shown[0], "album") + " found");
            miss.setText(shown[1] > 0 ? "· " + shown[1] + " without a cover" : "");
        };
        new Thread(() -> {
            long[] last = {0};
            ScanResult r = AlbumScanner.scan(dir, a -> {
                synchronized (found) { found.add(a); }
                long now = System.currentTimeMillis();
                if (now - last[0] > 120) {
                    last[0] = now;
                    runOnUiThread(flush);
                }
                return scanCancelled;
            });
            runOnUiThread(() -> {
                if (gen != browseGen || scanCancelled || screen != Screen.SCANNING) return;
                flush.run();
                showResults(r);
            });
        }, "scan").start();
    }

    // ---------- results (P2, handoff 04a-c) ----------

    private void showResults(ScanResult r) {
        scan = r;
        int total = r.missing.size() + r.withArt.size();
        String scope = scanRoot.getName();
        if (total == 0) {
            frameFull(Screen.RESULTS, scanRoot.getPath(), emptyBody("There are no music files in " + scope
                    + ". Pick the folder that holds your albums."), true, null, null,
                    Theme.button(this, "‹ Back to " + browseName(scanRoot), Theme.PRIMARY, v -> showBrowse(scanRoot)));
            return;
        }
        // 04c, the one-album rule: one album that already has its cover opens its album page directly
        if (total == 1 && r.missing.isEmpty()) {
            showAlbum(r.withArt.get(0), FROM_SCAN);
            return;
        }

        TileGrid grid = new TileGrid(this).top(22);
        int m = r.missing.size();
        if (m > 0) {
            LinearLayout h = Ui.row(this);
            h.setGravity(Gravity.NO_GRAVITY);
            h.setBaselineAligned(true);
            h.setPadding(Theme.d(6), 0, Theme.d(6), Theme.d(16));
            TextView big = Theme.text(this, "", 32, 700, Theme.TEXT);
            big.setLetterSpacing(-0.01f);
            big.setText(TileGrid.twoTone(plural(m, "album"), Theme.ACCENT, m == 1 ? " has no cover" : " have no cover", Theme.TEXT));
            h.addView(big);
            h.addView(Theme.text(this, plural(total, "album") + " in " + scope, 19, 400, Theme.TEXT_3), Ui.wrap(Theme.d(14)));
            grid.addRow(h);
            grid.addLabel("No cover · " + m, Theme.ACCENT);
            List<TileGrid.Tile> tiles = new ArrayList<>();
            for (Album a : r.missing) tiles.add(albumTile(a, FROM_RESULTS));
            grid.addTiles(tiles);
            grid.addSpacer(8);
        } else {
            LinearLayout h = Ui.row(this);
            h.setPadding(Theme.d(6), 0, Theme.d(6), Theme.d(18));
            TextView tick = Theme.text(this, "✓", 24, 700, Theme.GROUND);
            tick.setGravity(Gravity.CENTER);
            android.graphics.drawable.GradientDrawable o = new android.graphics.drawable.GradientDrawable();
            o.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            o.setColor(Theme.SUCCESS);
            tick.setBackground(o);
            h.addView(tick, new LinearLayout.LayoutParams(Theme.d(40), Theme.d(40)));
            h.addView(Theme.text(this, "Every album here has a cover.", 32, 700, Theme.TEXT), Ui.wrap(Theme.d(16)));
            TextView s = Theme.oneLine(Theme.text(this, plural(total, "album") + " in " + scope + ". Tap one to find more art for it.",
                    19, 400, Theme.TEXT_3));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            sp.leftMargin = Theme.d(16);
            h.addView(s, sp);
            grid.addRow(h);
        }
        if (!r.withArt.isEmpty()) {
            grid.addLabel("Already have a cover · " + r.withArt.size(), Theme.TEXT_3);
            List<TileGrid.Tile> tiles = new ArrayList<>();
            for (Album a : r.withArt) tiles.add(albumTile(a, FROM_RESULTS));
            grid.addTiles(tiles);
        }

        TextView primary;
        if (m > 0) {
            LinearLayout left = quietGroup(
                    Theme.button(this, "‹ Back to " + browseName(scanRoot), Theme.QUIET, v -> showBrowse(scanRoot)),
                    ripCheckButton());
            primary = Theme.button(this, "Fetch album art (" + m + ")", Theme.PRIMARY, v -> startFetch(r.missing));
            frameFull(Screen.RESULTS, scanRoot.getPath(), grid, true, left,
                    "Saves a folder.jpg in each album folder. Music files aren’t touched.", primary);
        } else {
            primary = Theme.button(this, "‹ Back to " + browseName(scanRoot), Theme.PRIMARY, v -> showBrowse(scanRoot));
            frameFull(Screen.RESULTS, scanRoot.getPath(), grid, true, ripCheckButton(), null, primary);
        }
        currentGrid = grid;
        if (!restorePlace(grid)) primary.requestFocus();
    }

    /** An album tile: its cover file, or the blank "No cover" square. Tapping opens the album page. */
    private TileGrid.Tile albumTile(Album a, int from) {
        TileGrid.Tile t = new TileGrid.Tile();
        t.title = a.dir.getName();
        t.art = artFile(a);
        t.meta = t.art != null ? TileGrid.plain(parentNameOrEmpty(a), Theme.TEXT_3) : TileGrid.plain("No cover", Theme.ACCENT);
        int ex = FolderSummary.extrasOf(a.dir);
        t.badge = ex > 0 ? "+" + ex : null;
        t.tag = a;
        t.onClick = v -> showAlbum(a, from);
        return t;
    }

    /** Several quiet buttons on the left of the action bar, gap 8; tagged so the bar uses the 28 margin. */
    private LinearLayout quietGroup(View... buttons) {
        LinearLayout g = Ui.row(this);
        for (int i = 0; i < buttons.length; i++) g.addView(buttons[i], Ui.wrap(i == 0 ? 0 : Theme.d(4)));
        return g;
    }

    /** The folder Browse will show for {@code f} (an album folder is shown from its parent). */
    private String browseName(File f) {
        File p = f.getParentFile();
        boolean album = p != null && !isVolumeRoot(f) && holdsAudio(f) && FolderSummary.of(f).isAlbum();
        return (album ? p : f).getName();
    }

    private TextView ripCheckButton() {
        return Theme.button(this, "Rip Error Check", Theme.QUIET, v -> startRipCheck());
    }

    private static String parentName(Album a) {
        File p = a.dir.getParentFile();
        return p == null ? null : p.getName();
    }

    private static String parentNameOrEmpty(Album a) {
        String p = parentName(a);
        return p == null ? "" : p;
    }

    // ---------- album page (P2: the cover and details, enough for the one-album rule; the panel is P3) ----------

    private static final int FROM_BROWSE = 0, FROM_SCAN = 1, FROM_RESULTS = 2, FROM_REPORT = 3;
    private Album album;
    private int albumFrom;

    /** P3: the grid on screen, and where to put it back when returning from an album page. */
    private TileGrid currentGrid;
    private int[] returnScroll;
    private Object returnFocus;
    private boolean restoreNext;

    /** p3c: per browse folder, where it was scrolled to and which tile to focus on coming back (Up or Back). */
    private final java.util.Map<String, Object[]> places = new java.util.HashMap<>();

    private static String placeKey(File dir) {
        return dir == null ? "" : dir.getPath();
    }

    /** Before leaving a browse folder for a subfolder or an album: remember its scroll and the tile opened. */
    private void savePlace(File browsing, Object opened) {
        if (currentGrid != null) places.put(placeKey(browsing), new Object[]{currentGrid.scrollState(), opened});
    }

    private boolean restoreBrowsePlace(TileGrid grid, File dir) {
        boolean go = restoreNext;
        restoreNext = false;
        Object[] p = places.get(placeKey(dir));
        if (!go || p == null) return false;
        grid.restore((int[]) p[0], p[1]);
        return true;
    }

    /** On the way back from an album: same scroll, focus on the album that was opened. False = nothing to restore. */
    private boolean restorePlace(TileGrid grid) {
        boolean go = restoreNext && returnScroll != null;
        restoreNext = false;
        if (!go) return false;
        grid.restore(returnScroll, returnFocus);
        return true;
    }

    /**
     * P3, handoff 07a/07b (idle): cover 440 and details, the "In this album folder" row (every picture in the
     * folder and its Scan/ folder), and the "More art" panel. Looking up and saving extras (07c-g) arrive with P6.
     */
    // ---- P6: Find more art (handoff 07c-g), the panel's states
    private static final int ART_IDLE = 0, ART_LOOKING = 1, ART_FOUND = 2, ART_FRONT_ONLY = 3, ART_NONE = 4,
            ART_NO_PICTURES = 5, ART_NETWORK = 6, ART_SAVED = 7, ART_BUSY = 8;
    private int artState = ART_IDLE;
    /** 1.1.0.1: why the last Find more art couldn't reach the database (host + cause), shown under the panel title. */
    private String artNetDetail;
    /** What Find more art searched for ("“Album” by Artist"), for the not-found panel; null = nothing to search. */
    private String artSearched;
    /** 1.1.0.1: the same for a Fetch run that stopped on the network. */
    private volatile String fetchNetDetail;
    private int artGen;
    private List<CaaExtras.Extra> found = new ArrayList<>();
    private File[] foundThumb = new File[0], foundBig = new File[0];
    private boolean[] selected = new boolean[0];
    /** "Bigger online": the online front is clearly larger than the album's own cover. */
    private boolean frontBigger;
    private String foundRelease;
    /** 1.1.1 close matches: all of them, their previews and full-size files, and which one is shown (-1 = a sure match). */
    private List<ExtrasLookup.Match> closeMatches = new ArrayList<>();
    private List<File[]> closeThumbs = new ArrayList<>(), closeBigs = new ArrayList<>();
    private int closeIdx = -1;
    /** tags4 D1: which found pictures are already in the album folder (same bytes), per match for close/editions. */
    private boolean[] already = new boolean[0];
    private List<boolean[]> closeAlready = new ArrayList<>();
    /** tags4 D3: browsing "More editions" (vs close matches); the state to go back to when there are none. */
    private boolean editionsMode;
    /** tags5: what the looking-up panel says ("Looking for other editions…"); null = "Looking up <album>…". */
    private String lookingText;
    private int savedCount;
    private boolean savedFolderJpg;
    private final java.util.Set<File> justSaved = new java.util.HashSet<>();
    private TextView savePrimary;

    private void showAlbum(Album a, int from) {
        // remember the grid's place before leaving it (Back returns to it)
        if (currentGrid != null && from == FROM_RESULTS) {
            returnScroll = currentGrid.scrollState();
            returnFocus = a;
        }
        currentGrid = null;
        album = a;
        albumFrom = from;
        artState = ART_IDLE;
        artGen++;
        justSaved.clear();
        renderAlbum();
    }

    /**
     * P3 + P6, handoff 07: cover 440 and details, the "In this album folder" row (every picture in the folder and
     * its Scan/ folder; hidden while looking up / choosing, so the panel has room), and the panel in its state.
     */
    private void renderAlbum() {
        Album a = album;
        int from = albumFrom;
        LinearLayout body = Ui.row(this);
        body.setGravity(Gravity.TOP);
        body.setPadding(Theme.d(40), Theme.d(24), Theme.d(40), Theme.d(16));

        int side = Theme.d(440);
        CoverView cover = new CoverView(this, side);
        cover.setElevation(Theme.d(18));
        cover.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), v.getWidth() * 0.03f);
            }
        });
        File art = artFile(a);
        if (art == null) cover.setBlank(CoverView.FLAG, "No cover yet");
        else {
            cover.setEmpty();
            CoverLoader.get().load(art, side, b -> {
                if (album == a) cover.setImage(b);
            });
            // p3b: the big cover opens full screen too (the first picture in the viewer)
            cover.setFocusable(true);
            cover.setClickable(true);
            cover.setOnClickListener(v -> openViewer(0));
            // p3c: a visible focus ring for the remote (inside the cover's edge; the page has no room outside it)
            android.graphics.drawable.StateListDrawable ring = new android.graphics.drawable.StateListDrawable();
            ring.addState(new int[]{android.R.attr.state_focused}, Theme.round(android.graphics.Color.TRANSPARENT, 13, Theme.ACCENT, 3));
            ring.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            cover.setForeground(ring);
            if (artState == ART_SAVED && savedFolderJpg) appear(cover);
        }
        body.addView(cover);

        LinearLayout info = Ui.column(this);
        String artist = parentNameOrEmpty(a);
        if (from == FROM_SCAN) {
            TextView note = Theme.monoText(this, "✓ Scanned " + artist + ": 1 album, and it already has its cover.", 15, 400, Theme.SUCCESS);
            note.setPadding(0, 0, 0, Theme.d(8));
            info.addView(note);
        }
        info.addView(Theme.oneLine(Theme.text(this, artist, 20, 400, Theme.TEXT_3)));
        TextView title = Theme.scrolling(Theme.lineHeight(Theme.text(this, a.dir.getName(), 38, 700, Theme.TEXT), 1.1f));
        title.setLetterSpacing(-0.015f);
        title.setPadding(0, Theme.d(4), 0, Theme.d(4));
        info.addView(title);
        List<String[]> bad = AlbumScanner.badArtFiles(a.dir);
        String coverPart = a.existingArt != null ? a.existingArt
                : bad.isEmpty() ? "no cover file" : "no cover file · " + bad.get(0)[0] + " " + bad.get(0)[1];
        TextView meta = Theme.monoText(this, plural(a.trackCount, "track") + " · " + format(a) + " · " + coverPart, 16, 400, Theme.TEXT_3);
        meta.setPadding(0, Theme.d(4), 0, 0);
        info.addView(meta);

        View here = hereRow(a); // always built: it also lists what the big cover opens
        if (artState != ART_LOOKING && artState != ART_FOUND) {
            // "IN THIS ALBUM FOLDER · n music files, never changed"
            TextView hereLabel = Theme.monoText(this, ("In this album folder · " + plural(a.trackCount, "music file") + ", never changed")
                    .toUpperCase(Locale.ROOT), 14, 400, Theme.TEXT_3);
            hereLabel.setLetterSpacing(0.12f);
            hereLabel.setPadding(0, Theme.d(12), 0, Theme.d(8 - 6)); // thumbnails carry a 6 focus inset
            info.addView(Theme.oneLine(hereLabel));
            info.addView(here);
        }

        boolean has = art != null;
        // 1.1.0 (Michael's Bob Marley screenshot: the panel text was cut off): tighter spacing, and the panel
        // scrolls when it still can't fit, so nothing is ever silently clipped
        android.widget.ScrollView panelBox = new android.widget.ScrollView(this);
        panelBox.setFillViewport(true);
        panelBox.setBackground(Theme.round(Theme.SURFACE, 16, 0x1AF2EDE4, 1));
        panelBox.setPadding(Theme.d(20), Theme.d(14), Theme.d(20), Theme.d(14));
        panelBox.setClipToPadding(false);
        LinearLayout panel = Ui.column(this);
        panelBox.addView(panel, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        List<View> buttons = new ArrayList<>();
        TextView primary = buildPanel(panel, a, has, buttons);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        pp.topMargin = Theme.d(12);
        info.addView(panelBox, pp);

        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        ip.leftMargin = Theme.d(48);
        body.addView(info, ip);

        // bottom right, in order: back (+ Rip Error Check after a one-album scan), the panel's own buttons, the primary
        List<View> group = new ArrayList<>();
        if (artState != ART_LOOKING && artState != ART_FOUND) {
            group.add(Theme.button(this, albumBackLabel(), Theme.GHOST, v -> albumBack()));
            if (from == FROM_SCAN) group.add(ripCheckButton());
        }
        group.addAll(buttons);
        View left = group.isEmpty() ? null : quietGroup(group.toArray(new View[0]));
        if (primary != null) frameFull(Screen.ALBUM, a.dir.getPath(), body, true, left, null, primary);
        else frameFull(Screen.ALBUM, a.dir.getPath(), body, true, left, null);
        if (primary != null && primary.isEnabled()) primary.requestFocus();
        else if (!buttons.isEmpty()) buttons.get(buttons.size() - 1).requestFocus();
    }

    /** tags4 D3: a visible pill (Michael's UI rule: no text-only buttons). */
    private TextView moreEditionsButton() {
        return Theme.button(this, "Fetch more editions", Theme.GHOST, v -> startEditions());
    }

    /** Fills the panel for the current state; returns the primary button (or null) and adds secondary buttons. */
    private TextView buildPanel(LinearLayout panel, Album a, boolean has, List<View> buttons) {
        String saveWhere = "Saves into " + a.dir.getName() + "/Scan/" + (has ? "" : " (front as folder.jpg)") + ". Music files are never touched.";
        switch (artState) {
            case ART_LOOKING: {
                panel.addView(Theme.oneLine(Theme.text(this, lookingText != null ? lookingText
                        : "Looking up " + a.dir.getName() + "…", 24, 600, Theme.TEXT)));
                LinearLayout row = Ui.row(this);
                row.setPadding(0, Theme.d(14), 0, 0);
                for (int i = 0; i < 7; i++) {
                    View sk = new View(this);
                    sk.setBackground(Theme.round(Theme.SURFACE_RAISED, 8));
                    android.animation.ObjectAnimator p = android.animation.ObjectAnimator.ofFloat(sk, "alpha", 1f, 0.45f);
                    p.setDuration(600);
                    p.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                    p.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                    p.setStartDelay(i * 100L);
                    p.start();
                    row.addView(sk, sizedWrap(Theme.d(THUMB), i == 0 ? 0 : Theme.d(10)));
                }
                android.widget.HorizontalScrollView sc = new android.widget.HorizontalScrollView(this);
                sc.setHorizontalScrollBarEnabled(false);
                sc.addView(row);
                panel.addView(sc);
                panel.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
                panel.addView(Theme.monoText(this, "MusicBrainz · Cover Art Archive", 15, 400, Theme.TEXT_3));
                // Cancel sits bottom right (Michael: Stop/Cancel where the run was started)
                buttons.add(Theme.button(this, "Cancel", Theme.GHOST, v -> {
                    artGen++;
                    artState = ART_IDLE;
                    renderAlbum();
                }));
                return null;
            }
            case ART_FOUND: {
                if (closeIdx >= 0) {
                    // 1.1.1 close match: say plainly it may be the wrong album, nothing selected until Michael picks
                    // tags4 D3: the same browser for "More editions"
                    String what = editionsMode ? "Edition " : "Close match ";
                    String head = what + (closeIdx + 1) + " of " + closeMatches.size() + ": " + closeMatches.get(closeIdx).label;
                    TextView ct = Theme.scrolling(Theme.text(this, head, 22, 600, editionsMode ? Theme.TEXT : Theme.ACCENT));
                    panel.addView(ct);
                    panel.addView(Theme.oneLine(Theme.text(this, (editionsMode
                            ? FetchText.plural(found.size(), "picture") + ", nothing picked yet."
                            : "No sure match, so check it’s your album.") + " Tap one to see it, the check picks it.", 17, 400, Theme.TEXT_3)));
                    if (closeMatches.size() > 1) {
                        buttons.add(Theme.button(this, editionsMode ? "Next edition" : "Another match",
                                Theme.GHOST, v -> showClose(closeIdx + 1)));
                    }
                } else {
                    LinearLayout head = Ui.row(this);
                    head.setGravity(Gravity.NO_GRAVITY);
                    head.setBaselineAligned(true);
                    head.addView(Theme.text(this, FetchText.plural(found.size(), "picture") + " found", 24, 600, Theme.TEXT));
                    // Michael 2026-11-23: tap = see it full screen, the check = leave it in or out ("you wanna see before selecting")
                    head.addView(Theme.oneLine(Theme.text(this, "Tap one to see it. The check leaves it out.", 17, 400, Theme.TEXT_3)), Ui.wrap(Theme.d(14)));
                    panel.addView(head);
                }
                panel.addView(foundRow());
                panel.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
                panel.addView(Theme.monoText(this, saveWhere, 15, 400, Theme.TEXT_3));
                buttons.add(Theme.button(this, "Cancel", Theme.GHOST, v -> {
                    artState = ART_IDLE;
                    renderAlbum();
                }));
                if (closeIdx < 0) buttons.add(moreEditionsButton());
                savePrimary = Theme.button(this, "", Theme.PRIMARY, v -> saveSelected());
                updateSaveLabel();
                return savePrimary;
            }
            case ART_FRONT_ONLY:
                panelText(panel, "Only the front cover is online",
                        "The Cover Art Archive has just the front for this release, and you already have it. "
                                + "Other editions may have more: try Fetch more editions.", null);
                buttons.add(moreEditionsButton());
                return Theme.wide(Theme.button(this, "Done", Theme.PRIMARY, v -> artDone()));
            case ART_NONE:
                // 1.1.1: say what was actually searched (Václav H. read the shelf folder "A" as the artist)
                panelText(panel, "This album isn’t in the database", artSearched == null
                        ? "There’s no album name to search for: the tags don’t have one, and neither does the folder name. "
                                + "Naming the folder Artist - Album can help."
                        : "No match on MusicBrainz for " + artSearched + ". If the tags or the folder name differ from the "
                                + "album’s real title, fixing them can help.", null);
                return Theme.wide(Theme.button(this, "Done", Theme.PRIMARY, v -> artDone()));
            case ART_NO_PICTURES:
                panelText(panel, "No pictures for this album yet",
                        "MusicBrainz knows this album, but nobody has added pictures of this edition to the Cover Art "
                                + "Archive. Other editions may have some: try Fetch more editions.", null);
                buttons.add(moreEditionsButton());
                return Theme.wide(Theme.button(this, "Done", Theme.PRIMARY, v -> artDone()));
            case ART_BUSY:
                panelText(panel, "The cover database is busy", "It asked us to wait a moment. Try again in a minute. Nothing was changed.", null);
                buttons.add(Theme.button(this, "Done", Theme.GHOST, v -> artDone()));
                return Theme.button(this, "Try again", Theme.PRIMARY, v -> startFind());
            case ART_NETWORK:
                panelText(panel, "Couldn’t reach the cover database", (artNetDetail != null ? artNetDetail
                        : "The streamer seems to be offline.") + " Nothing was changed.", null);
                buttons.add(Theme.button(this, "Done", Theme.GHOST, v -> artDone()));
                return Theme.button(this, "Try again", Theme.PRIMARY, v -> startFind());
            case ART_SAVED: {
                LinearLayout head = Ui.row(this);
                TextView tick = Theme.text(this, "✓", 18, 700, Theme.GROUND);
                tick.setGravity(Gravity.CENTER);
                android.graphics.drawable.GradientDrawable o = new android.graphics.drawable.GradientDrawable();
                o.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                o.setColor(Theme.SUCCESS);
                tick.setBackground(o);
                head.addView(tick, new LinearLayout.LayoutParams(Theme.d(30), Theme.d(30)));
                String t = savedFolderJpg && savedCount == 1 ? "Saved the cover as folder.jpg"
                        : "Saved " + FetchText.plural(savedCount, "picture") + (savedFolderJpg ? " (the cover as folder.jpg)" : " to Scan/");
                head.addView(Theme.oneLine(Theme.text(this, t, 24, 600, Theme.TEXT)), Ui.wrap(Theme.d(12)));
                panel.addView(head);
                TextView es = Theme.lineHeight(Theme.text(this, "", 19, 400, Theme.TEXT_2), 1.45f);
                android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder("In EverSignal,");
                sb.setSpan(new android.text.style.ForegroundColorSpan(Theme.TEXT), 0, sb.length(), 0);
                sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, sb.length(), 0);
                sb.append(" Full-Screen Album Mode shows these as a slideshow while this album plays.");
                es.setText(sb);
                es.setMaxWidth(Theme.d(660));
                es.setPadding(0, Theme.d(12), 0, 0);
                panel.addView(es);
                panel.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
                panel.addView(Theme.monoText(this, "Music files changed: 0", 15, 400, Theme.TEXT_3));
                return Theme.wide(Theme.button(this, "Done", Theme.PRIMARY, v -> artDone()));
            }
            default: {
                panelText(panel, has ? "More art for this album" : "No cover yet", has
                        ? "Looks this album up on MusicBrainz and the Cover Art Archive for the back cover, booklet pages, disc and tray. You choose what to save."
                        : "Looks this album up on MusicBrainz and the Cover Art Archive for its front cover, plus any back cover, booklet pages, disc and tray.",
                        saveWhere);
                return Theme.button(this, has ? "Find more art for this album" : "Find art for this album", Theme.PRIMARY, v -> startFind());
            }
        }
    }

    private void panelText(LinearLayout panel, String title, String body, String footer) {
        panel.addView(Theme.text(this, title, 22, 600, Theme.TEXT));
        TextView pb = Theme.lineHeight(Theme.text(this, body, 17, 400, Theme.TEXT_2), 1.35f);
        pb.setMaxWidth(Theme.d(720));
        pb.setPadding(0, Theme.d(6), 0, Theme.d(8));
        panel.addView(pb);
        if (footer != null) {
            panel.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
            panel.addView(Theme.monoText(this, footer, 15, 400, Theme.TEXT_3));
        }
    }

    private static LinearLayout.LayoutParams sizedWrap(int side, int leftMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(side, side);
        p.leftMargin = leftMargin;
        return p;
    }

    /** End states' Done: back to the idle panel, with the album as it is on disk now. */
    private void artDone() {
        artState = ART_IDLE;
        album = refreshed(album);
        justSaved.clear();
        renderAlbum();
    }

    private File extrasCache(String releaseId) {
        File d = new File(getCacheDir(), "extras/" + (releaseId == null ? "x" : releaseId.replaceAll("[^A-Za-z0-9-]", "")));
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** Downloads a URL into a cache file (kept: reopening the same album is instant). Null on failure. */
    private static File download(UrlHttp http, String url, File to) throws java.io.IOException {
        synchronized (to.getPath().intern()) {
            return downloadOnce(http, url, to);
        }
    }

    private static File downloadOnce(UrlHttp http, String url, File to) throws java.io.IOException {
        if (to.isFile() && to.length() > 0) return to;
        if (url == null) return null;
        com.malbers.addmycoverart.core.Http.Response r = http.get(url);
        if (r.code >= 500) throw new java.io.IOException("HTTP " + r.code);
        if (r.code != 200 || r.body == null || r.body.length == 0) return null;
        File tmp = new File(to.getPath() + ".tmp");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
            out.write(r.body);
        }
        return tmp.renameTo(to) ? to : null;
    }

    private static int minSide(File f) {
        if (f == null) return 0;
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
        return Math.max(0, Math.min(o.outWidth, o.outHeight));
    }

    /** 07c: look the album up, then fetch the previews (250) and the full pictures (1200) into the app's cache. */
    private void startFind() {
        Album a = album;
        boolean has = artFile(a) != null;
        int gen = ++artGen;
        lookingText = null;
        artState = ART_LOOKING;
        renderAlbum();
        new Thread(() -> {
            UrlHttp http = new UrlHttp(BuildConfig.USER_AGENT);
            com.malbers.addmycoverart.core.Tags t = com.malbers.addmycoverart.core.TagReader.read(a.firstAudio);
            ExtrasLookup.Result r = new ExtrasLookup(http).find(a, t, has);
            String searched = com.malbers.addmycoverart.core.ArtLookup.describeSearch(a, t);
            MatchFiles mf = new MatchFiles();
            int state;
            List<CaaExtras.Extra> x = r.extras;
            File[] thumbs = new File[x.size()], bigs = new File[x.size()];
            boolean[] alreadyTmp = new boolean[x.size()];
            boolean bigger = false;
            String netDetail = r.detail;
            if (r.state == ExtrasLookup.NETWORK) state = ART_NETWORK;
            else if (r.state == ExtrasLookup.BUSY) state = ART_BUSY;
            else if (r.state == ExtrasLookup.NOT_IN_DATABASE) state = ART_NONE;
            else if (r.state == ExtrasLookup.CLOSE) {
                // 1.1.1 close matches: previews for every match, so "Another match" switches at once
                state = ART_FOUND;
                try {
                    mf.load(a, r, http, gen);
                } catch (java.io.IOException e) {
                    state = com.malbers.addmycoverart.core.ArtLookup.isBusy(e) ? ART_BUSY : ART_NETWORK;
                    if (state == ART_NETWORK) netDetail = com.malbers.addmycoverart.core.NetFailure.describe(e, new Date());
                }
            }
            else if (r.state == ExtrasLookup.NO_PICTURES || x.isEmpty()) state = ART_NO_PICTURES;
            else {
                state = ART_FOUND;
                File cache = extrasCache(r.releaseId);
                try {
                    for (int i = 0; i < x.size() && gen == artGen; i++) {
                        thumbs[i] = download(http, x.get(i).thumbUrl, new File(cache, i + "-250.jpg"));
                    }
                    // the online front against the album's own cover (Michael: "might be better than my local copy")
                    if (has && CaaExtras.FRONT.equals(x.get(0).kind) && gen == artGen) {
                        bigs[0] = download(http, x.get(0).saveUrl, new File(cache, "0-1200.jpg"));
                        bigger = minSide(bigs[0]) > minSide(artFile(a)) * 1.2f;
                    }
                } catch (java.io.IOException e) {
                    state = com.malbers.addmycoverart.core.ArtLookup.isBusy(e) ? ART_BUSY : ART_NETWORK;
                    if (state == ART_NETWORK) netDetail = com.malbers.addmycoverart.core.NetFailure.describe(e, new Date());
                }
                if (state == ART_FOUND && has && CaaExtras.frontOnly(x) && !bigger) state = ART_FRONT_ONLY;
                // tags5: "Already saved" is checked after the pictures show (fetchBigs), not before
            }
            boolean[] alr = alreadyTmp;
            int st = state;
            boolean big = bigger;
            String nd = netDetail;
            runOnUiThread(() -> {
                if (gen != artGen || album != a || screen != Screen.ALBUM) return;
                artNetDetail = nd;
                artSearched = searched;
                editionsMode = false;
                if (r.state == ExtrasLookup.CLOSE && st == ART_FOUND) {
                    adoptMatches(r, mf);
                    return;
                }
                closeMatches = new ArrayList<>();
                closeIdx = -1;
                already = alr;
                found = x;
                foundRelease = r.releaseId;
                foundThumb = thumbs;
                foundBig = bigs;
                frontBigger = big;
                selected = new boolean[x.size()];
                for (int i = 0; i < x.size(); i++) {
                    // everything selected, except the online front of an album that has a cover unless it is bigger
                    // tags4 D1: and never what's already in the folder
                    selected[i] = !alr[i] && (!(has && CaaExtras.FRONT.equals(x.get(i).kind)) || big);
                }
                artState = st;
                renderAlbum();
                if (st == ART_FOUND) fetchBigs(gen, r.releaseId);
            });
        }, "find-art").start();
    }

    /** Previews, full-size files (when needed) and "already saved" marks for each close match or edition. */
    private final class MatchFiles {
        final List<File[]> thumbs = new ArrayList<>(), bigs = new ArrayList<>();
        final List<boolean[]> already = new ArrayList<>();

        /** Background thread only. */
        void load(Album a, ExtrasLookup.Result r, UrlHttp http, int gen) throws java.io.IOException {
            for (ExtrasLookup.Match m : r.close) {
                File cache = extrasCache(m.releaseId);
                File[] th = new File[m.extras.size()], bg = new File[m.extras.size()];
                for (int i = 0; i < th.length && gen == artGen; i++) {
                    th[i] = download(http, m.extras.get(i).thumbUrl, new File(cache, i + "-250.jpg"));
                }
                thumbs.add(th);
                bigs.add(bg);
                already.add(new boolean[th.length]); // filled in by fetchBigs when this one is shown (tags5)
            }
        }
    }

    /** UI thread: browse these close matches (or editions, {@link #editionsMode}), starting with the first. */
    private void adoptMatches(ExtrasLookup.Result r, MatchFiles mf) {
        closeMatches = r.close;
        closeThumbs = mf.thumbs;
        closeBigs = mf.bigs;
        closeAlready = mf.already;
        showClose(0);
    }

    /**
     * 1.1.1: show close match {@code i}. Nothing is selected: Michael picks, or moves on ("even a directional cover
     * might be fine ... I can still decline or say this is wrong").
     */
    private void showClose(int i) {
        if (closeMatches.isEmpty()) return;
        closeIdx = i % closeMatches.size();
        ExtrasLookup.Match m = closeMatches.get(closeIdx);
        found = m.extras;
        foundRelease = m.releaseId;
        foundThumb = closeThumbs.get(closeIdx);
        foundBig = closeBigs.get(closeIdx);
        already = closeAlready.size() > closeIdx ? closeAlready.get(closeIdx) : new boolean[found.size()];
        frontBigger = false;
        selected = new boolean[found.size()];
        artState = ART_FOUND;
        renderAlbum();
        fetchBigs(artGen, m.releaseId);
    }

    /**
     * tags4 D3 ("More editions", Michael: "maybe it's a toggle you can turn on within the screen"): search wider,
     * sure and close editions alike, most pictures first, and browse them like close matches. Nothing pre-selected.
     */
    private void startEditions() {
        Album a = album;
        boolean has = artFile(a) != null;
        int before = artState;
        int gen = ++artGen;
        lookingText = "Looking for other editions…";
        artState = ART_LOOKING;
        renderAlbum();
        new Thread(() -> {
            UrlHttp http = new UrlHttp(BuildConfig.USER_AGENT);
            com.malbers.addmycoverart.core.Tags t = com.malbers.addmycoverart.core.TagReader.read(a.firstAudio);
            ExtrasLookup.Result r = new ExtrasLookup(http).editions(a, t, has);
            MatchFiles mf = new MatchFiles();
            int state = before;
            String detail = r.detail;
            if (r.state == ExtrasLookup.BUSY) state = ART_BUSY;
            else if (r.state == ExtrasLookup.NETWORK) state = ART_NETWORK;
            else if (r.state == ExtrasLookup.CLOSE) {
                try {
                    mf.load(a, r, http, gen);
                } catch (java.io.IOException e) {
                    state = com.malbers.addmycoverart.core.ArtLookup.isBusy(e) ? ART_BUSY : ART_NETWORK;
                    if (state == ART_NETWORK) detail = com.malbers.addmycoverart.core.NetFailure.describe(e, new Date());
                }
            }
            int st = state;
            String nd = detail;
            runOnUiThread(() -> {
                if (gen != artGen || album != a || screen != Screen.ALBUM) return;
                if (r.state == ExtrasLookup.CLOSE && st == before) {
                    editionsMode = true;
                    adoptMatches(r, mf);
                    return;
                }
                if (st == ART_BUSY || st == ART_NETWORK) artNetDetail = nd;
                else toast("No other editions with pictures.");
                artState = st;
                renderAlbum();
            });
        }, "find-editions").start();
    }

    /**
     * After showing the previews: the 1200 pictures, in the background, for the viewer and for saving.
     * tags4 D1 / tags5: when the album already has pictures, each one is also compared with what's in the folder,
     * and the ones already there get "Already saved" and are unticked once the check is done. Only the match on
     * screen is checked (tags4 checked every edition up front, which made "More editions" slow).
     */
    private void fetchBigs(int gen, String releaseId) {
        Album a = album;
        List<CaaExtras.Extra> x = found;
        File[] bigs = foundBig;
        int idx = closeIdx;
        boolean check = !com.malbers.addmycoverart.core.ExtraWriter.existingPictures(a.dir).isEmpty();
        new Thread(() -> {
            UrlHttp http = new UrlHttp(BuildConfig.USER_AGENT);
            File cache = extrasCache(releaseId);
            boolean[] same = new boolean[x.size()];
            for (int i = 0; i < x.size() && gen == artGen; i++) {
                try {
                    if (bigs[i] == null) bigs[i] = download(http, x.get(i).saveUrl, new File(cache, i + "-1200.jpg"));
                    if (check && bigs[i] != null) {
                        same[i] = com.malbers.addmycoverart.core.ExtraWriter.sameAs(a.dir,
                                java.nio.file.Files.readAllBytes(bigs[i].toPath())) != null;
                    }
                } catch (java.io.IOException ignored) {
                    // saving downloads it again, and says so if it still can't; it also skips an identical file
                }
            }
            if (!check) return;
            runOnUiThread(() -> {
                if (gen != artGen || album != a || found != x) return;
                boolean any = false;
                for (int i = 0; i < same.length; i++) {
                    if (!same[i]) continue;
                    any = true;
                    if (selected.length > i) selected[i] = false;
                }
                already = same;
                if (idx >= 0 && closeAlready.size() > idx) closeAlready.set(idx, same);
                if (any && artState == ART_FOUND) renderAlbum();
            });
        }, "find-art-1200").start();
    }

    private final List<View> foundCells = new ArrayList<>();

    /** 07d: the pictures found, 120 each. Tap = full screen; the check badge (its own 48 target) = in or out. */
    private View foundRow() {
        foundCells.clear();
        android.widget.HorizontalScrollView sc = new android.widget.HorizontalScrollView(this);
        sc.setHorizontalScrollBarEnabled(false);
        sc.setClipToPadding(false);
        sc.setClipChildren(false);
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.TOP);
        row.setClipChildren(false);
        row.setPadding(0, Theme.d(14), Theme.d(14), 0);
        int t = Theme.d(THUMB);
        for (int i = 0; i < found.size(); i++) {
            int idx = i;
            CaaExtras.Extra e = found.get(i);
            android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
            LinearLayout cell = Ui.column(this);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            int p = Theme.d(6);
            cell.setPadding(p, p, p, p);
            cell.setFocusable(true);
            cell.setClickable(true);
            cell.setBackground(thumbBg());
            CoverView thumb = new CoverView(this, t);
            File f = foundThumb.length > i ? foundThumb[i] : null;
            if (f == null) thumb.setBlank(CoverView.MUTED, "");
            else {
                thumb.setEmpty();
                CoverLoader.get().load(f, t, b -> {
                    if (b == null) thumb.setBlank(CoverView.MUTED, "");
                    else thumb.setImage(b);
                });
            }
            cell.addView(thumb);
            TextView label = Theme.oneLine(Theme.monoText(this, e.label, 13, 400, Theme.TEXT_2));
            label.setMaxWidth(t + Theme.d(20));
            label.setPadding(0, Theme.d(5), 0, 0);
            cell.addView(label);
            if (already.length > i && already[i]) {
                // tags4 D1: same picture already in the folder; unticked, and saving skips it anyway
                cell.addView(Theme.monoText(this, "Already saved", 12, 500, Theme.SUCCESS));
            } else if (i == 0 && frontBigger && CaaExtras.FRONT.equals(e.kind) && artFile(album) != null) {
                cell.addView(Theme.monoText(this, "Bigger online", 12, 500, Theme.ACCENT));
            }
            cell.setOnClickListener(v -> openFoundViewer(idx));
            frame.addView(cell);
            // the check badge: 56 circle at the top right inside a 72 touch target. tags6: was 28 in 48; Michael on
            // the A6 with Songs of Freedom's 47 pictures: "I ended up opening the thumb several times ... maybe 2x"
            TextView badge = new TextView(this);
            badge.setGravity(Gravity.CENTER);
            badge.setIncludeFontPadding(false);
            badge.setTypeface(Theme.hanken(this, 700));
            badge.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(30));
            badge.setFocusable(true);
            badge.setClickable(true);
            int hit = Theme.d(72), dot = Theme.d(56);
            int pad = (hit - dot) / 2;
            badge.setPadding(pad, pad, pad, pad);
            android.widget.FrameLayout.LayoutParams bl = new android.widget.FrameLayout.LayoutParams(hit, hit, Gravity.TOP | Gravity.END);
            // the dot sits on the cell's top-right corner (6 outside the picture); the touch target spills past it
            bl.topMargin = -pad;
            bl.rightMargin = -pad;
            frame.setClipChildren(false);
            frame.addView(badge, bl);
            styleBadge(badge, cell, idx);
            badge.setOnClickListener(v -> {
                selected[idx] = !selected[idx];
                styleBadge(badge, cell, idx);
                updateSaveLabel();
            });
            foundCells.add(cell);
            row.addView(frame, Ui.wrap(i == 0 ? 0 : Theme.d(10 - 12 + 4)));
        }
        sc.addView(row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = -Theme.d(6);
        sc.setLayoutParams(lp);
        return sc;
    }

    private void styleBadge(TextView badge, View cell, int i) {
        boolean on = selected[i];
        int inset = badge.getPaddingLeft();
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(on ? Theme.SUCCESS : 0xCC121110);
        g.setStroke(Theme.d(2), on ? Theme.SUCCESS : 0x80F2EDE4);
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
        ring.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        ring.setColor(android.graphics.Color.TRANSPARENT);
        ring.setStroke(Theme.d(3), Theme.ACCENT);
        android.graphics.drawable.Drawable dot = new android.graphics.drawable.InsetDrawable(g, inset);
        s.addState(new int[]{android.R.attr.state_focused}, new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{new android.graphics.drawable.InsetDrawable(ring, Math.max(0, inset - Theme.d(6))), dot}));
        s.addState(new int[]{}, dot);
        badge.setBackground(s);
        badge.setPadding(inset, inset, inset, inset);
        badge.setText(on ? "✓" : "");
        badge.setTextColor(Theme.GROUND);
        ((LinearLayout) cell).getChildAt(0).setAlpha(on ? 1f : 0.35f);
        ((TextView) ((LinearLayout) cell).getChildAt(1)).setTextColor(on ? Theme.TEXT_2 : Theme.TEXT_4);
    }

    private int selectedCount() {
        int n = 0;
        for (boolean b : selected) if (b) n++;
        return n;
    }

    private void updateSaveLabel() {
        if (savePrimary == null) return;
        int n = selectedCount();
        savePrimary.setText(n == found.size() ? "Save all (" + n + ")" : n > 0 ? "Save " + FetchText.plural(n, "picture") : "Nothing selected");
        Theme.setEnabledPrimary(savePrimary, n > 0);
    }

    /** The found pictures full screen: the 1200 one if it's in, else the preview. */
    private void openFoundViewer(int i) {
        List<File> pics = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        List<Integer> map = new ArrayList<>();
        int start = 0;
        for (int k = 0; k < found.size(); k++) {
            File f = foundBig.length > k && foundBig[k] != null ? foundBig[k] : foundThumb.length > k ? foundThumb[k] : null;
            if (f == null) continue;
            if (k == i) start = pics.size();
            pics.add(f);
            labels.add(found.get(k).label);
            map.add(k);
        }
        List<CaaExtras.Extra> x = found;
        File[] bigs = foundBig;
        int gen = artGen;
        String release = foundRelease;
        PictureViewer.show(this, pics, labels, start, last -> {
            if (last >= 0 && last < map.size()) {
                int k = map.get(last);
                if (k < foundCells.size()) foundCells.get(k).requestFocus();
            }
        }, new PictureViewer.Upgrader() {
            // p6b MUST-FIX: the picture being looked at is fetched at full size first, ahead of the background queue
            @Override public boolean isPreview(int j) {
                return bigs[map.get(j)] == null;
            }

            @Override public void fetch(int j, java.util.function.Consumer<File> done) {
                int k = map.get(j);
                new Thread(() -> {
                    File f = null;
                    try {
                        f = download(new UrlHttp(BuildConfig.USER_AGENT), x.get(k).saveUrl, new File(extrasCache(release), k + "-1200.jpg"));
                    } catch (java.io.IOException ignored) {
                        // stays a labelled preview
                    }
                    File got = f;
                    runOnUiThread(() -> {
                        if (got != null && gen == artGen) bigs[k] = got;
                        done.accept(got);
                    });
                }, "find-art-now").start();
            }
        });
    }

    /** 07g: save the selected pictures (Scan/…, a blank album's front as folder.jpg), then show them in the row. */
    private void saveSelected() {
        Album a = album;
        boolean has = artFile(a) != null;
        int gen = ++artGen;
        List<CaaExtras.Extra> x = found;
        boolean[] sel = selected.clone();
        File[] bigs = foundBig.clone();
        Theme.setEnabledPrimary(savePrimary, false);
        savePrimary.setText("Saving…");
        File runLog = new File(getFilesDir(), "extras-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".log");
        new Thread(() -> {
            UrlHttp http = new UrlHttp(BuildConfig.USER_AGENT);
            List<File> written = new ArrayList<>();
            boolean folderJpg = false;
            String problem = null;
            int skipped = 0;
            for (int i = 0; i < x.size(); i++) {
                if (!sel[i]) continue;
                CaaExtras.Extra e = x.get(i);
                try {
                    byte[] bytes = bigs[i] != null ? java.nio.file.Files.readAllBytes(bigs[i].toPath()) : null;
                    if (bytes == null) {
                        com.malbers.addmycoverart.core.Http.Response r = http.get(e.saveUrl);
                        if (r.code != 200 || r.body == null || r.body.length == 0) throw new java.io.IOException("HTTP " + r.code);
                        bytes = r.body;
                    }
                    // tags4 D2: never a second copy of the same picture (Michael's back.jpg + back-2.jpg)
                    if (com.malbers.addmycoverart.core.ExtraWriter.sameAs(a.dir, bytes) != null) {
                        skipped++;
                        continue;
                    }
                    if (CaaExtras.FRONT.equals(e.kind) && !has) {
                        File f = com.malbers.addmycoverart.core.ArtWriter.save(a.dir, bytes, e.saveUrl, runLog);
                        if (f != null) { written.add(f); folderJpg = true; }
                    } else {
                        written.add(com.malbers.addmycoverart.core.ExtraWriter.save(a.dir, e.fileName, bytes));
                    }
                } catch (java.io.IOException ex) {
                    problem = e.label + ": " + ex.getMessage();
                }
            }
            boolean fj = folderJpg;
            String pr = problem;
            int sk = skipped;
            runOnUiThread(() -> {
                if (gen != artGen || album != a) return;
                if (pr != null) toast("Couldn’t save " + pr);
                else if (sk > 0) toast(FetchText.plural(sk, "picture") + " already in the folder, not saved again.");
                savedCount = written.size();
                savedFolderJpg = fj;
                justSaved.clear();
                justSaved.addAll(written);
                album = refreshed(a);
                artState = ART_SAVED;
                renderAlbum();
            });
        }, "save-art").start();
    }

    /** p3b: thumbnail side on the album page (was 72; Michael on the A6: "just too small"). */
    private static final float THUMB = 120;
    /** The pictures the album page can show full screen (real pictures only), their labels, and their cells. */
    private final List<File> viewPics = new ArrayList<>();
    private final List<String> viewLabels = new ArrayList<>();
    private final List<View> viewCells = new ArrayList<>();

    /** Opens the full-screen viewer at picture i; on close, focus goes to the thumbnail of the last picture shown. */
    private void openViewer(int i) {
        if (viewPics.isEmpty()) return;
        PictureViewer.show(this, new ArrayList<>(viewPics), new ArrayList<>(viewLabels), i, last -> {
            if (last >= 0 && last < viewCells.size() && viewCells.get(last) != null) viewCells.get(last).requestFocus();
        });
    }

    /** Every picture in the album folder, then in its Scan/ folder, as thumbnails with their names. Tap = full screen. */
    private View hereRow(Album a) {
        viewPics.clear();
        viewLabels.clear();
        viewCells.clear();
        List<File> pics = new ArrayList<>();
        File[] files = a.dir.listFiles();
        File scanDir = null;
        if (files != null) {
            Arrays.sort(files, (x, y) -> x.getName().compareToIgnoreCase(y.getName()));
            String best = a.existingArt;
            if (best != null) pics.add(new File(a.dir, best));
            for (File f : files) {
                if (f.isDirectory() && (f.getName().equalsIgnoreCase("scan") || f.getName().equalsIgnoreCase("scans"))) scanDir = f;
                else if (f.isFile() && isPictureName(f.getName()) && !f.getName().equals(best)) pics.add(f);
            }
        }
        if (scanDir != null) {
            File[] scans = scanDir.listFiles(f -> f.isFile() && isPictureName(f.getName()));
            if (scans != null) {
                Arrays.sort(scans, (x, y) -> x.getName().compareToIgnoreCase(y.getName()));
                pics.addAll(Arrays.asList(scans));
            }
        }
        if (pics.isEmpty()) {
            TextView none = Theme.text(this, "Only music files. No pictures yet.", 18, 400, Theme.TEXT_3);
            none.setMinHeight(Theme.d(THUMB));
            none.setGravity(Gravity.CENTER_VERTICAL);
            return none;
        }
        android.widget.HorizontalScrollView sc = new android.widget.HorizontalScrollView(this);
        sc.setHorizontalScrollBarEnabled(false);
        sc.setClipToPadding(false);
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.TOP);
        int t = Theme.d(THUMB);
        for (int i = 0; i < pics.size(); i++) {
            File f = pics.get(i);
            boolean ok = AlbumScanner.artProblem(f) == null;
            String name = f.getParentFile() != null && f.getParentFile().equals(scanDir) ? scanDir.getName() + "/" + f.getName() : f.getName();
            LinearLayout cell = Ui.column(this);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            int p = Theme.d(6);
            cell.setPadding(p, p, p, p);
            CoverView thumb = new CoverView(this, t);
            if (!ok) thumb.setBlank(CoverView.MUTED, "");
            else {
                thumb.setEmpty();
                Album want = a;
                CoverLoader.get().load(f, t, b -> {
                    if (album != want) return;
                    if (b == null) thumb.setBlank(CoverView.MUTED, "");
                    else thumb.setImage(b);
                });
            }
            cell.addView(thumb);
            TextView label = Theme.oneLine(Theme.monoText(this, name, 13, 400, Theme.TEXT_3));
            label.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            label.setMaxWidth(t + Theme.d(20));
            label.setPadding(0, Theme.d(5), 0, 0);
            cell.addView(label);
            if (justSaved.contains(f)) appear(cell); // P6 07g: the pictures just saved arrive in the row
            if (ok) {
                // a real picture: focusable, ringed like a tile, opens full screen
                int idx = viewPics.size();
                viewPics.add(f);
                viewLabels.add(name);
                viewCells.add(cell);
                cell.setFocusable(true);
                cell.setClickable(true);
                cell.setBackground(thumbBg());
                cell.setOnClickListener(v -> openViewer(idx));
            }
            row.addView(cell, Ui.wrap(i == 0 ? 0 : Theme.d(10 - 12)));
        }
        sc.addView(row);
        // the row sits 6 further left so the first thumbnail lines up with the text above it
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = -Theme.d(6);
        sc.setLayoutParams(lp);
        return sc;
    }

    /** Focus ring (accent 3, 3 gap) / hover wash for a picture thumbnail, as on the tiles. */
    private static android.graphics.drawable.Drawable thumbBg() {
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, Theme.round(android.graphics.Color.TRANSPARENT, 10, Theme.ACCENT, 3));
        s.addState(new int[]{android.R.attr.state_pressed}, Theme.round(0x0DF2EDE4, 10));
        s.addState(new int[]{android.R.attr.state_hovered}, Theme.round(0x0DF2EDE4, 10));
        s.addState(new int[]{}, Theme.round(android.graphics.Color.TRANSPARENT, 10));
        return s;
    }

    private static boolean isPictureName(String n) {
        String l = n.toLowerCase(Locale.ROOT);
        return l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png");
    }

    /** "FLAC", "MP3"… from the first track's extension. */
    private static String format(Album a) {
        String n = a.firstAudio == null ? "" : a.firstAudio.getName();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "audio" : n.substring(dot + 1).toUpperCase(Locale.ROOT);
    }

    private String albumBackLabel() {
        if (albumFrom == FROM_BROWSE) return "‹ Back to " + parentNameOrEmpty(album);
        if (albumFrom == FROM_SCAN) return "‹ Back to " + parentNameOrEmpty(album);
        if (albumFrom == FROM_REPORT) return "‹ Back to report";
        return "‹ Back to albums";
    }

    private void albumBack() {
        restoreNext = true;
        if (albumFrom == FROM_BROWSE) showBrowse(album.dir.getParentFile());
        else if (albumFrom == FROM_SCAN) showBrowse(scanRoot);
        else if (albumFrom == FROM_REPORT) showReport();
        else showResults(scan);
    }

    /** Runs the normal fetch for one album; the report's Done goes back to its folder. */
    private void fetchOne(Album a) {
        scanRoot = a.dir.getParentFile() != null ? a.dir.getParentFile() : a.dir;
        List<Album> one = new ArrayList<>();
        one.add(a);
        scan = new ScanResult(one, new ArrayList<>());
        startFetch(one);
    }

    // ---------- fetch (P4, handoff 05a-c) ----------

    /** What happened to one album in this fetch session. */
    private static final class Outcome {
        File saved;
        boolean fromFiles;
        String why;
    }

    /** The whole queue for this session (a resume or a "Try again" keeps it), and each album's outcome so far. */
    private List<Album> fetchQueue = new ArrayList<>();
    private final java.util.Map<Album, Outcome> outcomes = new java.util.HashMap<>();
    private boolean fetchOffline, fetchBusy;

    private void startFetch(List<Album> albums) {
        fetchQueue = new ArrayList<>(albums);
        outcomes.clear();
        fetchOffline = false;
        fetchBusy = false;
        fetchNetDetail = null;
        runFetch(new ArrayList<>(albums));
    }

    private List<Album> untried() {
        List<Album> out = new ArrayList<>();
        for (Album a : fetchQueue) if (!outcomes.containsKey(a)) out.add(a);
        return out;
    }

    private int addedCount() {
        int n = 0;
        for (Outcome o : outcomes.values()) if (o.saved != null) n++;
        return n;
    }

    private static final android.view.animation.Interpolator APPEAR = new android.view.animation.PathInterpolator(0.2f, 0.7f, 0.2f, 1f);

    /** A found cover arrives: 700 ms fade and scale .94 to 1 (handoff "Motion"). */
    private static void appear(View v) {
        v.setAlpha(0f);
        v.setScaleX(0.94f);
        v.setScaleY(0.94f);
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(700).setInterpolator(APPEAR).start();
    }

    private TileGrid.Tile queueTile(Album a) {
        TileGrid.Tile t = new TileGrid.Tile();
        t.title = a.dir.getName();
        t.tag = a;
        Outcome o = outcomes.get(a);
        if (o == null) {
            t.blankLabel = "";
            t.meta = TileGrid.plain("Waiting", Theme.TEXT_3);
        } else if (o.saved != null) {
            t.art = o.saved;
            t.meta = TileGrid.plain("Saved", Theme.SUCCESS);
        } else {
            t.tone = CoverView.MUTED;
            t.blankLabel = "Not found";
            t.meta = TileGrid.plain("Not found", Theme.TEXT_3);
        }
        return t;
    }

    /** An 8 tall bar, radius 4, accent on a faint track; the fill scales from the left (600 ms per step). */
    private static final class Bar extends android.widget.FrameLayout {
        final View fill;

        Bar(android.content.Context c, float start) {
            super(c);
            setBackground(Theme.round(0x1AF2EDE4, 4));
            fill = new View(c);
            fill.setBackground(Theme.round(Theme.ACCENT, 4));
            fill.setPivotX(0);
            fill.setScaleX(Math.max(0.001f, start));
            addView(fill, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }

        void set(float f) {
            fill.animate().scaleX(Math.max(0.001f, f)).setDuration(600).start();
        }
    }

    private void runFetch(List<Album> todo) {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        int n = fetchQueue.size();

        LinearLayout body = Ui.row(this);
        body.setGravity(Gravity.TOP);
        body.setPadding(Theme.d(40), Theme.d(28), Theme.d(40), Theme.d(16));

        LinearLayout left = Ui.column(this);
        int side = Theme.d(380);
        CoverView big = new CoverView(this, side);
        big.setElevation(Theme.d(18));
        big.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), v.getWidth() * 0.03f);
            }
        });
        big.setBlank(CoverView.FLAG, "Looking up…"); // still: only the queue tile pulses (Michael: both "a bit too much")
        left.addView(big);
        TextView title = Theme.oneLine(Theme.text(this, "", 26, 600, Theme.TEXT));
        title.setPadding(0, Theme.d(16), 0, Theme.d(4));
        title.setMaxWidth(side);
        left.addView(title);
        TextView artist = Theme.oneLine(Theme.text(this, "", 18, 400, Theme.TEXT_3));
        artist.setMaxWidth(side);
        left.addView(artist);
        TextView status = Theme.lineHeight(Theme.text(this, "Looking up…", 18, 500, Theme.TEXT_3), 1.3f);
        status.setPadding(0, Theme.d(4), 0, 0);
        status.setMaxWidth(side);
        left.addView(status);
        body.addView(left, new LinearLayout.LayoutParams(side, LinearLayout.LayoutParams.MATCH_PARENT));

        LinearLayout right = Ui.column(this);
        LinearLayout top = Ui.row(this);
        TextView pos = Theme.monoText(this, "Album " + Math.min(n, outcomes.size() + 1) + " of " + n, 19, 400, Theme.TEXT);
        top.addView(pos, Ui.weight(1));
        TextView added = Theme.monoText(this, addedCount() + " added", 19, 400, Theme.SUCCESS);
        top.addView(added);
        right.addView(top);
        Bar bar = new Bar(this, n == 0 ? 0 : outcomes.size() / (float) n);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Theme.d(8));
        bp.topMargin = Theme.d(14);
        right.addView(bar, bp);
        TileGrid grid = new TileGrid(this).columns(5, 18, 18).compact().pad(0, 24, 0, 8);
        java.util.Map<Album, TileGrid.Tile> tileOf = new java.util.HashMap<>();
        List<TileGrid.Tile> tiles = new ArrayList<>();
        for (Album a : fetchQueue) {
            TileGrid.Tile t = queueTile(a);
            tileOf.put(a, t);
            tiles.add(t);
        }
        grid.addTiles(tiles);
        grid.setFocusable(false);
        right.addView(grid, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        rp.leftMargin = Theme.d(56);
        body.addView(right, rp);

        TextView stop = Theme.button(this, "Stop", Theme.GHOST, null);
        // P6 (Michael: Stop on the left was "jarring"): Stop sits where "Fetch album art" was
        frameFull(Screen.FETCHING, "Fetching covers for " + scanRoot.getName(), body, true, null,
                "Each cover is saved as folder.jpg inside its album folder.", stop);
        stop.requestFocus();

        File runLog = new File(getFilesDir(), "run-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".log");
        FetchJob[] self = new FetchJob[1];
        stop.setOnClickListener(v -> {
            if (self[0] != null) self[0].cancel();
            stop.setEnabled(false);
            stop.setText("Stopping after this album…");
        });
        self[0] = new FetchJob(todo, new ArtLookup(new UrlHttp(BuildConfig.USER_AGENT)), runLog, new FetchJob.Listener() {
            @Override
            public void onStart(Album album, String artistName, String titleName, int index, int total) {
                runOnUiThread(() -> {
                    big.setBlank(CoverView.FLAG, "Looking up…");
                    title.setText(titleName);
                    artist.setText(artistName != null ? artistName : parentNameOrEmpty(album));
                    status.setTextColor(Theme.TEXT_3);
                    status.setText("Looking up…");
                    pos.setText("Album " + Math.min(n, outcomes.size() + 1) + " of " + n);
                    TileGrid.Tile t = tileOf.get(album);
                    if (t != null) {
                        t.highlight = true;
                        t.tone = CoverView.BUSY;
                        t.blankLabel = "Looking…";
                        t.meta = TileGrid.plain("Looking up…", Theme.TEXT_3);
                    }
                    grid.refresh();
                    grid.scrollToTag(album);
                });
            }

            @Override
            public void onResult(Album album, LookupResult r, File saved, String error) {
                Bitmap bm = saved != null && r.image != null ? Ui.decode(r.image, side) : null;
                runOnUiThread(() -> {
                    Outcome o = new Outcome();
                    o.saved = saved;
                    o.fromFiles = r.fromTracks();
                    o.why = saved != null ? null : error != null ? error : r.reason;
                    outcomes.put(album, o);
                    TileGrid.Tile t = tileOf.get(album);
                    if (saved != null) {
                        big.setImage(bm);
                        appear(big);
                        status.setTextColor(Theme.SUCCESS);
                        status.setText(FetchText.status(r, true));
                    } else {
                        big.setBlank(CoverView.MUTED, "Not found");
                        status.setTextColor(Theme.TEXT_3);
                        status.setText(FetchText.status(r, false));
                    }
                    if (t != null) {
                        TileGrid.Tile fresh = queueTile(album);
                        t.highlight = false;
                        t.art = fresh.art;
                        t.tone = fresh.tone;
                        t.blankLabel = fresh.blankLabel;
                        t.meta = fresh.meta;
                        t.animateIn = saved != null;
                    }
                    grid.refresh();
                    added.setText(addedCount() + " added");
                    bar.set(outcomes.size() / (float) n);
                });
            }

            @Override
            public void onDone(boolean cancelled) {
                runOnUiThread(() -> {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    if (self[0] != null && self[0].wentOffline()) {
                        fetchOffline = true;
                        if (self[0].netDetail() != null) fetchNetDetail = self[0].netDetail();
                    }
                    if (self[0] != null && self[0].wasBusy()) fetchBusy = true;
                    job = null;
                    if (cancelled && !untried().isEmpty()) showStopped();
                    else showReport();
                });
            }
        });
        job = self[0];
        new Thread(self[0], "fetch").start();
    }

    /** 05c: stopped part-way. Done ones as they came out, untried ones dimmed; resume fetches only those. */
    private void showStopped() {
        int n = fetchQueue.size(), done = outcomes.size(), rest = n - done;
        LinearLayout body = Ui.column(this);
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setPadding(Theme.d(40), Theme.d(24), Theme.d(40), Theme.d(24));
        TextView h = Theme.text(this, "Stopped after " + done + " of " + n, 38, 700, Theme.TEXT);
        h.setLetterSpacing(-0.01f);
        body.addView(h);
        TextView sub = Theme.text(this, FetchText.plural(addedCount(), "cover") + " added. "
                + (rest == 1 ? "The other album is just as it was." : "The other " + rest + " albums are just as they were."), 21, 400, Theme.TEXT_2);
        sub.setPadding(0, Theme.d(10), 0, Theme.d(30));
        body.addView(sub);
        android.widget.HorizontalScrollView sc = new android.widget.HorizontalScrollView(this);
        sc.setHorizontalScrollBarEnabled(false);
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.TOP);
        int t = Theme.d(150);
        for (int i = 0; i < fetchQueue.size(); i++) {
            Album a = fetchQueue.get(i);
            Outcome o = outcomes.get(a);
            LinearLayout cell = Ui.column(this);
            CoverView cv = new CoverView(this, t);
            if (o != null && o.saved != null) {
                cv.setEmpty();
                CoverLoader.get().load(o.saved, t, cv::setImage);
            } else if (o != null) cv.setBlank(CoverView.MUTED, "Not found");
            else cv.setBlank(CoverView.FLAG, "No cover");
            cell.addView(cv);
            TextView ct = Theme.oneLine(Theme.text(this, a.dir.getName(), 16, 600, Theme.TEXT));
            ct.setMaxWidth(t);
            ct.setPadding(0, Theme.d(8), 0, 0);
            cell.addView(ct);
            cell.addView(Theme.monoText(this, o == null ? "Not tried" : o.saved != null ? "Saved" : "Not found", 14, 400,
                    o != null && o.saved != null ? Theme.SUCCESS : Theme.TEXT_3));
            if (o == null) cell.setAlpha(0.55f);
            row.addView(cell, Ui.wrap(i == 0 ? 0 : Theme.d(16)));
        }
        sc.addView(row);
        body.addView(sc);

        TextView resume = Theme.button(this, "Fetch the other " + rest, Theme.PRIMARY, v -> runFetch(untried()));
        frameFull(Screen.STOPPED, scanRoot.getPath(), body, true,
                Theme.button(this, "Done for now", Theme.QUIET, v -> showReport()), null, resume);
        resume.requestFocus();
    }

    // ---------- report (P4, handoff 06a-c) ----------

    private void showReport() {
        job = null;
        List<Album> ok = new ArrayList<>(), bad = new ArrayList<>();
        int retry = 0;
        for (Album a : fetchQueue) {
            Outcome o = outcomes.get(a);
            if (o == null) continue; // "Done for now": the report covers what ran
            if (o.saved != null) ok.add(a);
            else {
                bad.add(a);
                if (FetchText.retryable(o.why)) retry++;
            }
        }
        boolean network = fetchOffline && retry > 0;
        int skipped = scan == null ? 0 : scan.withArt.size();

        LinearLayout body = Ui.row(this);
        body.setGravity(Gravity.TOP);
        body.setPadding(Theme.d(40), Theme.d(26), Theme.d(40), Theme.d(16));
        LinearLayout left = Ui.column(this);
        TextView h = Theme.text(this, FetchText.reportTitle(ok.size(), bad.size(), network), 38, 700, Theme.TEXT);
        h.setLetterSpacing(-0.01f);
        left.addView(h);
        String sub = null;
        // 1.1.0: a busy database (HTTP 5xx) is not the streamer being offline (Michael saw "offline", Try again worked)
        if (network && fetchBusy) sub = "The cover database is busy right now. Try again in a minute."
                + (ok.isEmpty() ? " Nothing was changed." : " " + FetchText.plural(ok.size(), "cover") + " from inside your files "
                + (ok.size() == 1 ? "was" : "were") + " saved anyway.");
        else if (network) sub = (fetchNetDetail != null ? fetchNetDetail : "The streamer seems to be offline.") + " " + (ok.isEmpty() ? "Nothing was changed."
                : FetchText.plural(ok.size(), "cover") + (ok.size() == 1 ? " came" : " came") + " from inside your files and "
                + (ok.size() == 1 ? "was" : "were") + " saved anyway.");
        else if (bad.isEmpty() && !ok.isEmpty() && scan != null && fetchQueue.size() == scan.missing.size() && untried().isEmpty())
            sub = "Every album in " + scanRoot.getName() + " now has a cover.";
        if (sub != null) {
            TextView s = Theme.lineHeight(Theme.text(this, sub, 20, 400, Theme.TEXT_2), 1.4f);
            s.setMaxWidth(Theme.d(720));
            s.setPadding(0, Theme.d(8), 0, 0);
            left.addView(s);
        }
        if (!ok.isEmpty()) {
            android.widget.HorizontalScrollView sc = new android.widget.HorizontalScrollView(this);
            sc.setHorizontalScrollBarEnabled(false);
            LinearLayout strip = Ui.row(this);
            strip.setGravity(Gravity.TOP);
            int t = Theme.d(104);
            for (int i = 0; i < ok.size(); i++) {
                Album a = ok.get(i);
                LinearLayout cell = Ui.column(this);
                int p = Theme.d(6);
                cell.setPadding(p, p, p, p);
                CoverView cv = new CoverView(this, t);
                cv.setEmpty();
                CoverLoader.get().load(outcomes.get(a).saved, t, cv::setImage);
                cell.addView(cv);
                TextView ct = Theme.oneLine(Theme.text(this, a.dir.getName(), 15, 600, Theme.TEXT));
                ct.setMaxWidth(t);
                ct.setPadding(0, Theme.d(6), 0, 0);
                cell.addView(ct);
                cell.setFocusable(true);
                cell.setClickable(true);
                cell.setBackground(thumbBg());
                cell.setOnClickListener(v -> showAlbum(refreshed(a), FROM_REPORT));
                strip.addView(cell, Ui.wrap(i == 0 ? 0 : Theme.d(12 - 12)));
            }
            sc.addView(strip);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            sp.topMargin = Theme.d(18 - 6);
            sp.leftMargin = -Theme.d(6);
            left.addView(sc, sp);
        }
        if (!bad.isEmpty()) {
            TextView lab = Theme.monoText(this, ("Couldn’t find " + bad.size()).toUpperCase(Locale.ROOT), 15, 400, Theme.TEXT_3);
            lab.setLetterSpacing(0.12f);
            lab.setPadding(0, Theme.d(22), 0, Theme.d(6));
            left.addView(lab);
            android.widget.ScrollView sv = new android.widget.ScrollView(this);
            LinearLayout rows = Ui.column(this);
            for (Album a : bad) {
                LinearLayout r = Ui.row(this);
                r.setMinimumHeight(Theme.d(76));
                r.setPadding(Theme.d(10), Theme.d(8), Theme.d(10), Theme.d(8));
                r.setFocusable(true);
                r.setClickable(true);
                r.setBackground(thumbBg());
                CoverView cv = new CoverView(this, Theme.d(60));
                cv.setBlank(CoverView.MUTED, "");
                r.addView(cv);
                LinearLayout tx = Ui.column(this);
                tx.setPadding(Theme.d(16), 0, 0, 0);
                TextView name = Theme.oneLine(Theme.text(this, "", 19, 600, Theme.TEXT));
                String ar = parentNameOrEmpty(a);
                name.setText(TileGrid.twoTone(a.dir.getName(), Theme.TEXT, ar.isEmpty() ? null : " · " + ar, Theme.TEXT_3));
                tx.addView(name);
                TextView why = Theme.text(this, FetchText.reason(outcomes.get(a).why), 17, 400, Theme.TEXT_2);
                why.setPadding(0, Theme.d(3), 0, 0);
                tx.addView(why);
                r.addView(tx, Ui.weight(1));
                r.setOnClickListener(v -> showAlbum(refreshed(a), FROM_REPORT));
                rows.addView(r);
            }
            sv.addView(rows);
            LinearLayout.LayoutParams svp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
            svp.leftMargin = svp.rightMargin = -Theme.d(10);
            left.addView(sv, svp);
        }
        body.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));

        // right: what to do next, and the ledger ("Music files changed 0" is a real count: only folder.jpg is written)
        LinearLayout card = Ui.column(this);
        card.setBackground(Theme.round(Theme.SURFACE, 18));
        card.setPadding(Theme.d(26), Theme.d(24), Theme.d(26), Theme.d(24));
        TextView next = Theme.monoText(this, "NEXT STEP", 15, 400, Theme.ACCENT);
        next.setLetterSpacing(0.12f);
        card.addView(next);
        TextView ct = Theme.lineHeight(Theme.text(this, "Rescan your library on the Eversolo", 24, 600, Theme.TEXT), 1.25f);
        ct.setPadding(0, Theme.d(14), 0, Theme.d(10));
        card.addView(ct);
        card.addView(Theme.lineHeight(Theme.text(this, "The streamer only shows new covers after it rescans your music.", 18, 400, Theme.TEXT_2), 1.45f));
        View div = new View(this);
        div.setBackgroundColor(0x1AF2EDE4);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Theme.d(1)));
        dp.topMargin = dp.bottomMargin = Theme.d(18);
        card.addView(div, dp);
        card.addView(ledger("folder.jpg added", String.valueOf(ok.size()), Theme.SUCCESS));
        card.addView(ledger("Already had a cover", String.valueOf(skipped), Theme.TEXT));
        card.addView(ledger("Music files changed", "0", Theme.TEXT));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(Theme.d(400), LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.leftMargin = Theme.d(48);
        body.addView(card, cp);

        TextView primary;
        if (network) {
            List<Album> again = new ArrayList<>();
            for (Album a : bad) if (FetchText.retryable(outcomes.get(a).why)) again.add(a);
            primary = Theme.button(this, "Try again (" + again.size() + ")", Theme.PRIMARY, v -> {
                for (Album a : again) outcomes.remove(a);
                fetchOffline = false;
                fetchBusy = false;
                fetchNetDetail = null;
                runFetch(again);
            });
            frameFull(Screen.REPORT, scanRoot.getPath(), body, true,
                    Theme.button(this, "Done", Theme.QUIET, v -> showBrowse(scanRoot)), null, primary);
        } else {
            primary = Theme.wide(Theme.button(this, "Done", Theme.PRIMARY, v -> showBrowse(scanRoot)));
            frameFull(Screen.REPORT, scanRoot.getPath(), body, true, ripCheckButton(), null, primary);
        }
        primary.requestFocus();
    }

    private LinearLayout ledger(String label, String value, int valueColor) {
        LinearLayout r = Ui.row(this);
        r.setPadding(0, Theme.d(4), 0, Theme.d(4));
        r.addView(Theme.monoText(this, label, 17, 400, Theme.TEXT_2), Ui.weight(1));
        r.addView(Theme.monoText(this, value, 17, 400, valueColor));
        return r;
    }

    /** The album as it is on disk now (a cover may just have been saved). */
    private static Album refreshed(Album a) {
        FolderSummary s = FolderSummary.of(a.dir);
        return s.isAlbum() ? s.toAlbum() : a;
    }

    // ---------- rip check (report only, a separate run started from the scan results) ----------

    private volatile boolean ripCancelled;
    private final List<RipCheck.Result> ripResults = new ArrayList<>();

    private void startRipCheck() {
        List<Album> all = new ArrayList<>(scan.missing);
        all.addAll(scan.withArt);
        all.sort(Comparator.comparing(a -> a.dir.getPath().toLowerCase(Locale.ROOT)));
        ripResults.clear();
        ripCancelled = false;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout body = Ui.column(this);
        TextView count = Ui.oneLine(this, "", 13, Ui.SUB, true);
        body.addView(count);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(all.size());
        bar.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.CARD_HI));
        body.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 10)));
        TextView nowBy = Ui.oneLine(this, "", 14, Ui.SUB, false);
        nowBy.setPadding(0, Ui.dp(this, 10), 0, 0);
        body.addView(nowBy);
        TextView now = Ui.oneLine(this, "", 18, Ui.TEXT, true);
        now.setPadding(0, 0, 0, Ui.dp(this, 10));
        body.addView(now);
        ItemAdapter found = new ItemAdapter(this);
        LinearLayout box = card();
        ListView l = list(found);
        l.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_ALWAYS_SCROLL);
        box.addView(l, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        body.addView(box, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        TextView stop = Ui.button(this, "Stop", null);
        stop.setOnClickListener(v -> {
            ripCancelled = true;
            stop.setEnabled(false);
            stop.setText("Stopping after this album…");
        });
        frame(Screen.RIPCHECK, "Checking rips in " + scanRoot.getName() + " · nothing is changed", body, stop);

        new Thread(() -> {
            RipCheck check = new RipCheck(new UrlHttp(BuildConfig.USER_AGENT));
            for (int i = 0; i < all.size() && !ripCancelled; i++) {
                Album a = all.get(i);
                int n = i;
                runOnUiThread(() -> {
                    count.setText((n + 1) + " of " + all.size());
                    nowBy.setText(parentNameOrEmpty(a));
                    now.setText(a.dir.getName());
                });
                RipCheck.Result r = check.check(a);
                runOnUiThread(() -> {
                    ripResults.add(r);
                    bar.setProgress(ripResults.size());
                    if (!r.problems.isEmpty()) found.add(issueItem(r));
                });
            }
            runOnUiThread(() -> {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                showRipReport(ripCancelled, all.size());
            });
        }, "rip-check").start();
    }

    private ItemAdapter.Item issueItem(RipCheck.Result r) {
        String status = (r.problems.size() == 1 ? "1 issue" : r.problems.size() + " issues") + " · " + basisShort(r.basis);
        String sub = (r.artist != null ? r.artist + " · " : "") + String.join(" · ", r.problems);
        return new ItemAdapter.Item(r.album.dir.getName(), sub, status, Ui.BAD, null, false, r.album);
    }

    /** "by ID" is the exact edition; "by search" is the edition most releases share, so worth a second look. */
    private static String basisShort(String basis) {
        if (RipCheck.BY_ID.equals(basis)) return "by ID";
        if (RipCheck.BY_SEARCH.equals(basis)) return "by search";
        return "by tags";
    }

    private void showRipReport(boolean cancelled, int planned) {
        List<ItemAdapter.Item> issues = new ArrayList<>();
        List<ItemAdapter.Item> skipped = new ArrayList<>();
        int checked = 0;
        for (RipCheck.Result r : ripResults) {
            if (r.skipped != null) {
                skipped.add(new ItemAdapter.Item(r.album.dir.getName(), r.artist != null ? r.artist : parentName(r.album),
                        "Not checked: " + r.skipped, Ui.SUB, null, false, r.album));
                continue;
            }
            checked++;
            if (!r.problems.isEmpty()) issues.add(issueItem(r));
        }

        LinearLayout body = Ui.column(this);
        LinearLayout chips = Ui.row(this);
        chips.addView(chip("Checked " + checked, Ui.TEXT));
        chips.addView(chip(issues.size() + " possible issues", issues.isEmpty() ? Ui.GOOD : Ui.BAD), Ui.wrap(Ui.dp(this, 8)));
        chips.addView(chip("Not checked " + skipped.size(), Ui.SUB), Ui.wrap(Ui.dp(this, 8)));
        body.addView(chips);

        String note = (cancelled ? "Stopped after " + ripResults.size() + " of " + planned + " albums. " : "")
                + (issues.isEmpty()
                    ? "No missing, empty or cut-short tracks found in the albums checked."
                    : "These albums have missing, empty or cut-short tracks. Nothing was changed.")
                + (skipped.isEmpty() ? "" : " " + skipped.size() + (skipped.size() == 1 ? " album" : " albums")
                    + " had nothing reliable to compare with; Save report lists them.");
        TextView n = Ui.text(this, note, 15, Ui.TEXT, false);
        n.setPadding(Ui.dp(this, 4), Ui.dp(this, 12), 0, Ui.dp(this, 8));
        body.addView(n);

        // Only what needs acting on goes on screen; the not-checked list lives in the saved report.
        ItemAdapter adapter = new ItemAdapter(this);
        adapter.addAll(issues);
        LinearLayout box = card();
        box.addView(list(adapter), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        body.addView(box, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        TextView save = Ui.button(this, "Save report", null);
        save.setOnClickListener(v -> {
            File f = saveRipReport(cancelled, planned);
            save.setText(f != null ? "Saved: Download/" + f.getName() : "Couldn't save the report");
            save.setEnabled(f == null);
        });
        frameWithLeft(Screen.RIPREPORT, scanRoot.getPath(), body, save,
                Ui.button(this, "Back to scan results", true, v -> showResults(scan)));
    }

    /**
     * Writes the readout as plain text to the shared Download folder (never into the music folders),
     * so a long report can be read on a computer. Null when it can't be written.
     */
    private File saveRipReport(boolean cancelled, int planned) {
        StringBuilder b = new StringBuilder();
        String now = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());
        b.append("AddMyCoverArt rip check, ").append(BuildConfig.VERSION_NAME).append('\n');
        b.append(now).append(" · ").append(scanRoot.getPath()).append("\n");
        if (cancelled) b.append("Stopped after ").append(ripResults.size()).append(" of ").append(planned).append(" albums.\n");
        b.append("Report only: nothing was changed.\n\n");
        List<RipCheck.Result> issues = new ArrayList<>();
        List<RipCheck.Result> skipped = new ArrayList<>();
        int checked = 0;
        for (RipCheck.Result r : ripResults) {
            if (r.skipped != null) skipped.add(r);
            else {
                checked++;
                if (!r.problems.isEmpty()) issues.add(r);
            }
        }
        b.append("Checked ").append(checked).append(" · possible issues ").append(issues.size())
                .append(" · not checked ").append(skipped.size()).append("\n\n");
        b.append("POSSIBLE ISSUES\n");
        if (issues.isEmpty()) b.append("  none\n");
        for (RipCheck.Result r : issues) {
            b.append("\n").append(r.artist != null ? r.artist + " / " : "").append(r.album.dir.getName())
                    .append("   (compared ").append(basisShort(r.basis)).append(")\n");
            b.append("  ").append(r.album.dir.getPath()).append('\n');
            for (String p : r.problems) b.append("  - ").append(p).append('\n');
        }
        b.append("\nNOT CHECKED\n");
        if (skipped.isEmpty()) b.append("  none\n");
        for (RipCheck.Result r : skipped) {
            b.append("  ").append(r.artist != null ? r.artist + " / " : "").append(r.album.dir.getName())
                    .append(": ").append(r.skipped).append('\n');
        }
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File f = new File(dir, "AddMyCoverArt-rip-report-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + ".txt");
        try {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            try (java.io.Writer w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(f), java.nio.charset.StandardCharsets.UTF_8)) {
                w.write(b.toString());
            }
            return f;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    // ---------- back ----------

    @Override
    public void onBackPressed() {
        switch (screen) {
            case BROWSE:
                if (browseDir != null) { goUp(); return; }
                break;
            case SCANNING:
                scanCancelled = true;
                showBrowse(scanRoot);
                return;
            case ALBUM:
                albumBack();
                return;
            case RESULTS:
            case REPORT:
                showBrowse(scanRoot);
                return;
            case RIPREPORT:
                showResults(scan);
                return;
            case STOPPED:
                showReport();
                return;
            case FETCHING:
            case RIPCHECK:
                return; // Stop is explicit; back must not abandon a run half-way.
            default:
                break;
        }
        super.onBackPressed();
    }
}
