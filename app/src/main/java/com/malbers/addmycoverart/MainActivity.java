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
import com.malbers.addmycoverart.core.ArtLookup;
import com.malbers.addmycoverart.core.LookupResult;
import com.malbers.addmycoverart.core.RipCheck;
import com.malbers.addmycoverart.core.ScanResult;
import com.malbers.addmycoverart.core.UpdateCheck;
import com.malbers.addmycoverart.core.UrlHttp;
import com.malbers.addmycoverart.ui.FetchJob;
import com.malbers.addmycoverart.ui.ItemAdapter;
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
    private enum Screen { ACCESS, BROWSE, SCANNING, RESULTS, FETCHING, REPORT, RIPCHECK, RIPREPORT }

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
        root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        int pad = Ui.dp(this, 20);
        root.setPadding(pad, Ui.dp(this, 14), pad, Ui.dp(this, 14));
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
        String last = prefs.getString("last", null);
        File f = last == null ? null : new File(last);
        showBrowse(f != null && f.isDirectory() && f.canRead() ? f : null);
    }

    // ---------- frame ----------

    /** Clears everything and lays out header, body and bottom buttons. */
    private void frame(Screen s, String subtitle, View body, View... buttons) {
        frameWithLeft(s, subtitle, body, null, buttons);
    }

    /** As {@link #frame}, with an optional view at the bottom left, opposite the buttons. */
    private void frameWithLeft(Screen s, String subtitle, View body, View left, View... buttons) {
        screen = s;
        root.removeAllViews();
        LinearLayout header = Ui.row(this);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        int iz = Ui.dp(this, 36);
        header.addView(icon, new LinearLayout.LayoutParams(iz, iz));
        LinearLayout titles = Ui.column(this);
        titles.setPadding(Ui.dp(this, 12), 0, 0, 0);
        titles.addView(Ui.oneLine(this, "AddMyCoverArt", 20, Ui.TEXT, true));
        if (subtitle != null) titles.addView(Ui.oneLine(this, subtitle, 13, Ui.SUB, false));
        header.addView(titles, Ui.weight(1));
        TextView credit = Ui.oneLine(this, "by @malbers", 13, Ui.SUB, false);
        credit.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), 0, Ui.dp(this, 8));
        credit.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(CHANNEL)));
            } catch (Exception ignored) {
                // No browser on this device; the credit stays as text.
            }
        });
        header.addView(credit, Ui.wrap(Ui.dp(this, 12)));
        root.addView(header);

        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        bp.topMargin = Ui.dp(this, 12);
        bp.bottomMargin = Ui.dp(this, 12);
        root.addView(body, bp);

        if (buttons.length > 0 || left != null) {
            LinearLayout bar = Ui.row(this);
            if (left != null) bar.addView(left);
            // Zero height: a bare View sized wrap_content grows to fill the screen and squeezes the list out.
            bar.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1));
            for (View b : buttons) bar.addView(b, Ui.wrap(Ui.dp(this, 10)));
            root.addView(bar);
        }
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

    /** "Update available" pill that opens the download in the browser, or null when up to date. */
    private View updatePill() {
        String v = prefs.getString("upd_version", null);
        String url = prefs.getString("upd_url", null);
        if (v == null || url == null || !UpdateCheck.isNewer(v, BuildConfig.VERSION_NAME)) return null;
        updateText = Ui.text(this, "", 15, Ui.GOOD, true);
        updateText.setBackground(Ui.rounded(this, Ui.CARD, 24));
        int h = Ui.dp(this, 18);
        updateText.setPadding(h, Ui.dp(this, 12), h, Ui.dp(this, 12));
        showUpdateState(v);
        updateText.setOnClickListener(x -> {
            if (downloadId < 0 || "failed".equals(downloadState)) downloadUpdate(v, url);
        });
        return updateText;
    }

    private TextView updateText;
    private long downloadId = -1;
    private String downloadState; // null, "running", "done", "failed"

    private void showUpdateState(String v) {
        if (updateText == null) return;
        if ("running".equals(downloadState)) {
            updateText.setTextColor(Ui.ACCENT);
            updateText.setText("Downloading " + v + "…");
        } else if ("done".equals(downloadState)) {
            updateText.setTextColor(Ui.GOOD);
            updateText.setText("Saved. Open Files › Storage › Download and tap it to install");
        } else if ("failed".equals(downloadState)) {
            updateText.setTextColor(Ui.BAD);
            updateText.setText("Download failed · tap to try again");
        } else {
            updateText.setTextColor(Ui.GOOD);
            updateText.setText("Update available: " + v + " · Download");
        }
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
                if (c != null && c.moveToFirst()) status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            } catch (Exception ignored) {
                // treated as failed below
            }
            if (status == DownloadManager.STATUS_SUCCESSFUL) downloadState = "done";
            else if (status == DownloadManager.STATUS_FAILED || status == -1) downloadState = "failed";
            else {
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

    private void showAccess() {
        LinearLayout body = card();
        int p = Ui.dp(this, 18);
        body.setPadding(p, p, p, p);
        body.addView(Ui.text(this, "One permission first", 18, Ui.TEXT, true));
        TextView t = Ui.text(this, "To add covers, AddMyCoverArt needs access to your music folders. "
                + "It only ever adds a folder.jpg next to your tracks. It never changes, moves or deletes your music.\n\n"
                + "Two quick steps: turn on \"Allow access to manage all files\" and come back, then tap Allow on the pop-up.", 15, Ui.SUB, false);
        t.setPadding(0, Ui.dp(this, 8), 0, 0);
        body.addView(t);
        frame(Screen.ACCESS, null, body, Ui.button(this, "Grant access", true, v -> requestAccess()));
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

    // ---------- browse ----------

    private void showBrowse(File dir) {
        browseDir = dir;
        ItemAdapter adapter = new ItemAdapter(this);
        if (dir == null) {
            for (File[] v : volumes()) {
                adapter.add(new ItemAdapter.Item(v[1].getName(), v[0].getPath(), "›", Ui.SUB, null, false, v[0]).withIcon(R.drawable.ic_drive));
            }
        } else {
            File[] kids = dir.listFiles(f -> f.isDirectory() && !f.getName().startsWith("."));
            if (kids != null) {
                Arrays.sort(kids, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                for (File k : kids) adapter.add(new ItemAdapter.Item("/" + k.getName(), null, "›", Ui.SUB, null, false, k).withIcon(R.drawable.ic_folder));
            }
            if (adapter.getCount() == 0) adapter.add(new ItemAdapter.Item("No folders in here", "Scan it, or go up", null, Ui.SUB, null, false, null));
        }
        LinearLayout body = card();
        ListView l = list(adapter);
        l.setOnItemClickListener((p, v, pos, id) -> {
            Object tag = adapter.getItem(pos).tag;
            if (tag instanceof File) showBrowse((File) tag);
        });
        body.addView(l, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        String sub = dir == null ? "Choose where your music is" : dir.getPath();
        View update = updatePill();
        if (dir == null) {
            frameWithLeft(Screen.BROWSE, sub, body, update);
        } else {
            frameWithLeft(Screen.BROWSE, sub, body, update,
                    Ui.button(this, "Up", v -> goUp()),
                    Ui.button(this, "Scan this folder", true, v -> startScan(dir)));
        }
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
        showBrowse(isVolumeRoot(browseDir) ? null : browseDir.getParentFile());
    }

    // ---------- scan ----------

    private void startScan(File dir) {
        prefs.edit().putString("last", dir.getPath()).apply();
        scanRoot = dir;
        LinearLayout body = Ui.column(this);
        body.setGravity(Gravity.CENTER);
        ProgressBar spin = new ProgressBar(this);
        spin.setIndeterminateTintList(ColorStateList.valueOf(Ui.ACCENT));
        body.addView(spin);
        TextView t = Ui.text(this, "Looking for albums in " + dir.getName() + "…", 16, Ui.SUB, false);
        t.setPadding(0, Ui.dp(this, 12), 0, 0);
        body.addView(t);
        frame(Screen.SCANNING, dir.getPath(), body);
        new Thread(() -> {
            ScanResult r = AlbumScanner.scan(dir);
            runOnUiThread(() -> {
                if (screen == Screen.SCANNING) showResults(r);
            });
        }).start();
    }

    private void showResults(ScanResult r) {
        scan = r;
        int total = r.missing.size() + r.withArt.size();
        LinearLayout body = Ui.column(this);
        LinearLayout chips = Ui.row(this);
        chips.addView(chip(total + (total == 1 ? " album" : " albums"), Ui.TEXT));
        chips.addView(chip(r.withArt.size() + " have art", Ui.GOOD), Ui.wrap(Ui.dp(this, 8)));
        chips.addView(chip(r.missing.size() + " missing art", r.missing.isEmpty() ? Ui.SUB : Ui.ACCENT), Ui.wrap(Ui.dp(this, 8)));
        body.addView(chips);

        LinearLayout box = card();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        lp.topMargin = Ui.dp(this, 12);
        if (r.missing.isEmpty()) {
            box.setGravity(Gravity.CENTER);
            box.addView(Ui.text(this, total == 0 ? "No albums found in this folder." : "Every album here already has art. Nothing to do.", 17, Ui.TEXT, true));
            body.addView(box, lp);
            frameWithLeft(Screen.RESULTS, scanRoot.getPath(), body, total == 0 ? null : ripCheckButton(),
                    Ui.button(this, "Choose another folder", true, v -> showBrowse(scanRoot)));
            return;
        }
        ItemAdapter adapter = new ItemAdapter(this);
        for (Album a : r.missing) adapter.add(new ItemAdapter.Item(a.dir.getName(), parentName(a),
                a.embeddedArt ? "art in tracks · will copy" : a.trackCount + " tracks", a.embeddedArt ? Ui.GOOD : Ui.SUB, null, true, a));
        box.addView(list(adapter), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        body.addView(box, lp);
        frameWithLeft(Screen.RESULTS, scanRoot.getPath(), body, ripCheckButton(),
                Ui.button(this, "Choose another folder", v -> showBrowse(scanRoot)),
                Ui.button(this, "Fetch Album Art (" + r.missing.size() + ")", true, v -> startFetch(r.missing)));
    }

    private Button ripCheckButton() {
        return Ui.button(this, "Rip Error Check", v -> startRipCheck());
    }

    private static String parentName(Album a) {
        File p = a.dir.getParentFile();
        return p == null ? null : p.getName();
    }

    // ---------- fetch ----------

    private ImageView bigCover;
    private TextView counter, nowArtist, nowTitle, nowStatus;
    private ProgressBar progress;
    private ItemAdapter done;
    private Button stopButton;
    private final List<ItemAdapter.Item> added = new ArrayList<>();
    private final List<ItemAdapter.Item> notFound = new ArrayList<>();

    private void startFetch(List<Album> albums) {
        added.clear();
        notFound.clear();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout body = Ui.row(this);
        body.setGravity(Gravity.TOP);
        int heightDp = (int) (getResources().getDisplayMetrics().heightPixels / getResources().getDisplayMetrics().density);
        int coverDp = Math.max(140, Math.min(340, heightDp - 150));
        bigCover = Ui.coverView(this, Ui.dp(this, coverDp));
        bigCover.setBackground(Ui.rounded(this, Ui.CARD, 18));
        bigCover.setElevation(Ui.dp(this, 6));
        body.addView(bigCover);

        LinearLayout side = Ui.column(this);
        side.setPadding(Ui.dp(this, 20), 0, 0, 0);
        counter = Ui.oneLine(this, "", 13, Ui.SUB, true);
        side.addView(counter);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(albums.size());
        progress.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.CARD_HI));
        side.addView(progress, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 10)));
        nowArtist = Ui.oneLine(this, "", 14, Ui.SUB, false);
        nowArtist.setPadding(0, Ui.dp(this, 10), 0, 0);
        side.addView(nowArtist);
        nowTitle = Ui.oneLine(this, "", 22, Ui.TEXT, true);
        side.addView(nowTitle);
        nowStatus = Ui.oneLine(this, "", 15, Ui.ACCENT, true);
        nowStatus.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 10));
        side.addView(nowStatus);

        done = new ItemAdapter(this);
        LinearLayout box = card();
        ListView l = list(done);
        // The newest finished album stays in view without scrolling.
        l.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_ALWAYS_SCROLL);
        l.setStackFromBottom(false);
        box.addView(l, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        side.addView(box, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        body.addView(side, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));

        stopButton = Ui.button(this, "Stop", v -> {
            if (job != null) job.cancel();
            stopButton.setEnabled(false);
            stopButton.setText("Stopping after this album…");
        });
        frame(Screen.FETCHING, "Fetching covers for " + scanRoot.getName(), body, stopButton);

        File runLog = new File(getFilesDir(), "run-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".log");
        job = new FetchJob(albums, new ArtLookup(new UrlHttp(BuildConfig.USER_AGENT)), runLog, new FetchJob.Listener() {
            @Override
            public void onStart(Album album, String artist, String title, int index, int total) {
                runOnUiThread(() -> {
                    counter.setText((index + 1) + " of " + total);
                    progress.setProgress(index);
                    nowArtist.setText(artist != null ? artist : parentNameOrEmpty(album));
                    nowTitle.setText(title);
                    nowStatus.setTextColor(Ui.ACCENT);
                    nowStatus.setText("Searching…");
                    bigCover.setImageResource(R.drawable.ic_disc_placeholder);
                    bigCover.setAlpha(0.6f);
                });
            }

            @Override
            public void onResult(Album album, LookupResult r, File saved, String error) {
                Bitmap big = r.image != null ? Ui.decode(r.image, 600) : null;
                Bitmap thumb = big != null ? Bitmap.createScaledBitmap(big, 96, 96 * big.getHeight() / Math.max(1, big.getWidth()), true) : null;
                runOnUiThread(() -> {
                    progress.setProgress(progress.getProgress() + 1);
                    String name = album.dir.getName();
                    String sub = parentName(album);
                    ItemAdapter.Item item;
                    if (saved != null) {
                        bigCover.setImageBitmap(big);
                        bigCover.setAlpha(0f);
                        bigCover.animate().alpha(1f).setDuration(300).start();
                        nowStatus.setTextColor(Ui.GOOD);
                        nowStatus.setText(r.fromTracks() ? "Copied from the tracks · saved folder.jpg" : "Found · saved folder.jpg");
                        item = new ItemAdapter.Item(name, sub, r.fromTracks() ? "Added (from tracks)" : "Added", Ui.GOOD, thumb, true, album);
                        added.add(item);
                    } else {
                        String why = error != null ? error : r.reason;
                        nowStatus.setTextColor(Ui.BAD);
                        nowStatus.setText("Not added: " + why);
                        item = new ItemAdapter.Item(name, sub, why, Ui.BAD, null, true, album);
                        notFound.add(item);
                    }
                    done.add(item);
                });
            }

            @Override
            public void onDone(boolean cancelled) {
                runOnUiThread(() -> {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    showReport(cancelled, albums.size());
                });
            }
        });
        new Thread(job, "fetch").start();
    }

    private static String parentNameOrEmpty(Album a) {
        String p = parentName(a);
        return p == null ? "" : p;
    }

    // ---------- report ----------

    private void showReport(boolean cancelled, int planned) {
        job = null;
        int skipped = scan == null ? 0 : scan.withArt.size();
        LinearLayout body = Ui.column(this);
        LinearLayout chips = Ui.row(this);
        chips.addView(chip("Added " + added.size(), Ui.GOOD));
        chips.addView(chip("Couldn't find " + notFound.size(), notFound.isEmpty() ? Ui.SUB : Ui.BAD), Ui.wrap(Ui.dp(this, 8)));
        chips.addView(chip("Skipped " + skipped + " (had art)", Ui.SUB), Ui.wrap(Ui.dp(this, 8)));
        body.addView(chips);

        String note = (cancelled ? "Stopped after " + (added.size() + notFound.size()) + " of " + planned + " albums. " : "")
                + (added.isEmpty() ? "No covers were added." : "Now rescan your library on the Eversolo so the new covers show.");
        TextView n = Ui.text(this, note, 15, Ui.TEXT, false);
        n.setPadding(Ui.dp(this, 4), Ui.dp(this, 12), 0, Ui.dp(this, 8));
        body.addView(n);

        ItemAdapter adapter = new ItemAdapter(this);
        adapter.addAll(added);
        adapter.addAll(notFound);
        LinearLayout box = card();
        box.addView(list(adapter), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        body.addView(box, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        frame(Screen.REPORT, scanRoot.getPath(), body, Ui.button(this, "Done", true, v -> showBrowse(scanRoot)));
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
        TextView now = Ui.oneLine(this, "", 18, Ui.TEXT, true);
        now.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        body.addView(now);
        ItemAdapter found = new ItemAdapter(this);
        LinearLayout box = card();
        ListView l = list(found);
        l.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_ALWAYS_SCROLL);
        box.addView(l, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        body.addView(box, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        Button stop = Ui.button(this, "Stop", null);
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
        String status = r.problems.size() == 1 ? "1 issue" : r.problems.size() + " issues";
        return new ItemAdapter.Item(r.album.dir.getName(), String.join(" · ", r.problems), status, Ui.BAD, null, false, r.album);
    }

    private void showRipReport(boolean cancelled, int planned) {
        List<ItemAdapter.Item> issues = new ArrayList<>();
        List<ItemAdapter.Item> skipped = new ArrayList<>();
        int checked = 0;
        for (RipCheck.Result r : ripResults) {
            if (r.skipped != null) {
                skipped.add(new ItemAdapter.Item(r.album.dir.getName(), parentName(r.album), "Not checked: " + r.skipped, Ui.SUB, null, false, r.album));
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
                    ? "No missing or short tracks found in the albums checked."
                    : "These albums have fewer tracks than the release, or tracks much shorter than they should be. Nothing was changed.")
                + (skipped.isEmpty() ? "" : " Albums without a MusicBrainz ID can't be checked.");
        TextView n = Ui.text(this, note, 15, Ui.TEXT, false);
        n.setPadding(Ui.dp(this, 4), Ui.dp(this, 12), 0, Ui.dp(this, 8));
        body.addView(n);

        ItemAdapter adapter = new ItemAdapter(this);
        adapter.addAll(issues);
        adapter.addAll(skipped);
        LinearLayout box = card();
        box.addView(list(adapter), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        body.addView(box, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        frame(Screen.RIPREPORT, scanRoot.getPath(), body, Ui.button(this, "Back to scan results", true, v -> showResults(scan)));
    }

    // ---------- back ----------

    @Override
    public void onBackPressed() {
        switch (screen) {
            case BROWSE:
                if (browseDir != null) { goUp(); return; }
                break;
            case SCANNING:
            case RESULTS:
            case REPORT:
                showBrowse(scanRoot);
                return;
            case RIPREPORT:
                showResults(scan);
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
