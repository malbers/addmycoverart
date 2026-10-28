package com.malbers.addmycoverart;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
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
import com.malbers.addmycoverart.core.ScanResult;
import com.malbers.addmycoverart.core.UrlHttp;
import com.malbers.addmycoverart.ui.FetchJob;
import com.malbers.addmycoverart.ui.ItemAdapter;
import com.malbers.addmycoverart.ui.Ui;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Five screens, each built fresh so nothing carries over: access, browse, results, fetching, report.
 */
public class MainActivity extends Activity {
    private enum Screen { ACCESS, BROWSE, SCANNING, RESULTS, FETCHING, REPORT }

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

        if (buttons.length > 0) {
            LinearLayout bar = Ui.row(this);
            bar.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            for (View b : buttons) bar.addView(b, Ui.wrap(Ui.dp(this, 10)));
            root.addView(bar);
        }
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
        if (dir == null) {
            frame(Screen.BROWSE, sub, body);
        } else {
            frame(Screen.BROWSE, sub, body,
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
            frame(Screen.RESULTS, scanRoot.getPath(), body, Ui.button(this, "Choose another folder", true, v -> showBrowse(scanRoot)));
            return;
        }
        ItemAdapter adapter = new ItemAdapter(this);
        for (Album a : r.missing) adapter.add(new ItemAdapter.Item(a.dir.getName(), parentName(a),
                a.embeddedArt ? "art in tracks · will copy" : a.trackCount + " tracks", a.embeddedArt ? Ui.GOOD : Ui.SUB, null, true, a));
        box.addView(list(adapter), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        body.addView(box, lp);
        frame(Screen.RESULTS, scanRoot.getPath(), body,
                Ui.button(this, "Choose another folder", v -> showBrowse(scanRoot)),
                Ui.button(this, "Fetch Album Art (" + r.missing.size() + ")", true, v -> startFetch(r.missing)));
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
            case FETCHING:
                return; // Stop is explicit; back must not abandon a run half-way.
            default:
                break;
        }
        super.onBackPressed();
    }
}
