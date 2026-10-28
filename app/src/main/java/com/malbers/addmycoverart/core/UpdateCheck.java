package com.malbers.addmycoverart.core;

import java.nio.charset.StandardCharsets;

import org.json.JSONArray;
import org.json.JSONObject;

/** Asks GitHub for the latest release. Never throws; null means "nothing to report". */
public final class UpdateCheck {
    public static final String LATEST = "https://api.github.com/repos/malbers/addmycoverart/releases/latest";

    private UpdateCheck() {}

    public static final class Release {
        public final String version;
        /** Direct APK download, or the release page when no APK is attached. */
        public final String url;

        Release(String version, String url) {
            this.version = version;
            this.url = url;
        }
    }

    public static Release latest(Http http) {
        try {
            Http.Response r = http.get(LATEST);
            if (r.code != 200) return null;
            JSONObject j = new JSONObject(new String(r.body, StandardCharsets.UTF_8));
            String tag = j.optString("tag_name", "");
            String version = tag.startsWith("v") ? tag.substring(1) : tag;
            if (version.isEmpty()) return null;
            String url = j.optString("html_url", null);
            JSONArray assets = j.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name").toLowerCase().endsWith(".apk")) {
                        url = a.optString("browser_download_url", url);
                        break;
                    }
                }
            }
            return url == null ? null : new Release(version, url);
        } catch (Exception e) {
            return null;
        }
    }

    /** True when {@code remote} (e.g. "1.0.3") is a higher version than {@code local} (e.g. "1.0.2" or "1.0.1-test"). */
    public static boolean isNewer(String remote, String local) {
        int[] a = parts(remote);
        int[] b = parts(local);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] parts(String v) {
        String core = v == null ? "" : v.split("[^0-9.]", 2)[0];
        String[] s = core.split("\\.");
        int[] out = new int[s.length];
        for (int i = 0; i < s.length; i++) {
            try {
                out[i] = Integer.parseInt(s[i]);
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }
}
