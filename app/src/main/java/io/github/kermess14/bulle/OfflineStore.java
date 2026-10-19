package io.github.kermess14.bulle;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lecture hors connexion.
 *
 * Un album « gardé » est copié dans la mémoire de l'appli : ses pages (à la taille d'un téléphone), ses cases,
 * sa fiche, et une fois pour toutes le lecteur de Bulle lui-même (reader.html, reader.js, style, polices).
 * Quand le serveur ne répond pas, l'appli sert ces copies à la place du serveur : le lecteur habituel s'ouvre
 * comme d'habitude, sans réseau. Les pages d'un album gardé sont toujours lues sur le téléphone (plus rapide,
 * et le NAS souffle). La page lue hors connexion est renvoyée au serveur au retour du réseau.
 */
class OfflineStore {
    interface Listener {
        void progress(int id, int done, int total, String state);
    }

    static final String LIST_PATH = "/__hors_connexion";
    private static final String TAG = "BulleApp";
    private static final int PAGE_WIDTH = 1600;
    private static final Pattern STATIC_REF = Pattern.compile("[\"'(](/static/[^\"')?#]+)");

    private final File base;
    private final Context ctx;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<Integer, int[]> busy = new ConcurrentHashMap<>();       // id -> {done, total}
    private final Set<Integer> cancelled = ConcurrentHashMap.newKeySet();
    private Listener listener;
    private String server = "";
    private volatile long upCheckedAt = 0;
    private volatile boolean upCached = true;

    OfflineStore(Context ctx) {
        this.ctx = ctx;
        this.base = new File(ctx.getFilesDir(), "horsconnexion");
    }

    void setListener(Listener l) { listener = l; }

    void setServer(String s) { server = s == null ? "" : s; }

    private File serverDir() {
        String key = server.replaceAll("^https?://", "").replaceAll("[^A-Za-z0-9.]", "_");
        File d = new File(base, key.isEmpty() ? "_" : key);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private File albumDir(int id) { return new File(serverDir(), String.valueOf(id)); }

    private File staticDir() { return new File(serverDir(), "_lecteur"); }

    // ------------------------------------------------------------------ état

    JSONObject meta(int id) {
        try {
            File f = new File(albumDir(id), "meta.json");
            if (!f.exists()) return null;
            return new JSONObject(new String(read(f), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private void saveMeta(int id, JSONObject m) throws IOException {
        write(new File(albumDir(id), "meta.json"), m.toString().getBytes(StandardCharsets.UTF_8));
    }

    String state(int id) {
        try {
            JSONObject o = new JSONObject();
            int[] b = busy.get(id);
            JSONObject m = meta(id);
            int total = m != null ? m.optInt("pages", 0) : 0;
            o.put("busy", b != null);
            o.put("done", b != null ? b[0] : countPages(id));
            o.put("total", b != null ? b[1] : total);
            o.put("ready", m != null && m.optBoolean("ready", false));
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private int countPages(int id) {
        String[] f = albumDir(id).list((d, name) -> name.startsWith("p") && name.endsWith(".jpg"));
        return f == null ? 0 : f.length;
    }

    String list() {
        JSONArray arr = new JSONArray();
        File[] dirs = serverDir().listFiles(File::isDirectory);
        if (dirs != null) {
            Arrays.sort(dirs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File d : dirs) {
                if (!d.getName().matches("\\d+")) continue;
                int id = Integer.parseInt(d.getName());
                JSONObject m = meta(id);
                if (m == null) continue;
                try {
                    m.put("done", m.optBoolean("ready", false));
                    m.put("saved", countPages(id));
                    m.put("bytes", size(d));
                    m.put("busy", busy.containsKey(id));
                } catch (Exception ignored) {
                }
                arr.put(m);
            }
        }
        return arr.toString();
    }

    private static long size(File f) {
        if (f.isFile()) return f.length();
        long n = 0;
        File[] k = f.listFiles();
        if (k != null) for (File c : k) n += size(c);
        return n;
    }

    void remove(int id) {
        cancelled.add(id);
        deleteTree(albumDir(id));
    }

    void cancel(int id) { cancelled.add(id); }

    private static void deleteTree(File f) {
        File[] k = f.listFiles();
        if (k != null) for (File c : k) deleteTree(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** Page atteinte pendant une lecture hors connexion : gardée pour être renvoyée au serveur plus tard. */
    void notePage(int id, int page, boolean offline) {
        JSONObject m = meta(id);
        if (m == null) return;
        try {
            m.put("page", page);
            if (offline) m.put("pending", true);
            saveMeta(id, m);
        } catch (Exception ignored) {
        }
    }

    /** Au retour du réseau : renvoie au serveur la page atteinte hors connexion. */
    void syncProgress() {
        worker.execute(() -> {
            File[] dirs = serverDir().listFiles(File::isDirectory);
            if (dirs == null) return;
            for (File d : dirs) {
                if (!d.getName().matches("\\d+")) continue;
                int id = Integer.parseInt(d.getName());
                JSONObject m = meta(id);
                if (m == null || !m.optBoolean("pending", false)) continue;
                try {
                    int page = m.optInt("page", 0);
                    JSONObject body = new JSONObject().put("page", page);
                    if (page >= m.optInt("pages", 0) - 1) body.put("completed", true);
                    int code = send("PUT", server + "/api/progress/" + id, body.toString());
                    if (code < 300) {
                        m.remove("pending");
                        saveMeta(id, m);
                        Log.i(TAG, "progression hors connexion renvoyée : album " + id + " page " + page);
                    }
                } catch (Exception e) {
                    Log.i(TAG, "progression non renvoyée : " + e);
                }
            }
        });
    }

    // ------------------------------------------------------------------ téléchargement

    void download(int id) {
        if (busy.containsKey(id)) return;
        cancelled.remove(id);
        busy.put(id, new int[]{countPages(id), 0});
        worker.execute(() -> {
            int done = 0, total = 0;
            try {
                File dir = albumDir(id);
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                byte[] itemBytes = fetch(server + "/api/item/" + id);
                JSONObject item = new JSONObject(new String(itemBytes, StandardCharsets.UTF_8));
                write(new File(dir, "item.json"), itemBytes);
                total = item.optInt("pages", 0);
                JSONObject m = meta(id) != null ? meta(id) : new JSONObject();
                m.put("id", id);
                m.put("name", item.optString("name", "Album " + id));
                m.put("pages", total);
                m.put("mtime", item.optLong("mtime", 0));
                JSONArray crumbs = item.optJSONArray("breadcrumb");
                if (crumbs != null && crumbs.length() > 1) m.put("folder", crumbs.getJSONObject(crumbs.length() - 1).optString("name", ""));
                JSONObject prog = item.optJSONObject("progress");
                if (prog != null && !m.has("page")) m.put("page", prog.optInt("page", 0));
                m.put("ready", false);
                saveMeta(id, m);
                saveReader();
                busy.put(id, new int[]{countPages(id), total});
                emit(id, countPages(id), total, "busy");

                for (int n = 0; n < total; n++) {
                    if (cancelled.contains(id)) throw new InterruptedException("arrêté");
                    File page = new File(dir, String.format(Locale.ROOT, "p%04d.jpg", n));
                    if (!page.exists() || page.length() == 0) {
                        write(page, fetch(server + "/api/page/" + id + "/" + n + "?w=" + PAGE_WIDTH));
                    }
                    File panels = new File(dir, String.format(Locale.ROOT, "c%04d.json", n));
                    if (!panels.exists()) {
                        try {
                            write(panels, fetch(server + "/api/panels/" + id + "/" + n));
                        } catch (IOException ignored) {   // cases indisponibles : la page reste lisible
                        }
                    }
                    done = n + 1;
                    busy.put(id, new int[]{done, total});
                    emit(id, done, total, "busy");
                }
                m = meta(id);
                if (m != null) {
                    m.put("ready", true);
                    m.put("savedAt", System.currentTimeMillis());
                    saveMeta(id, m);
                }
                busy.remove(id);
                emit(id, total, total, "done");
            } catch (Exception e) {
                Log.i(TAG, "téléchargement " + id + " interrompu : " + e);
                busy.remove(id);
                if (cancelled.contains(id)) emit(id, done, total, "cancelled");
                else emit(id, done, total, "error");
            }
        });
    }

    private void emit(int id, int done, int total, String state) {
        if (listener != null) listener.progress(id, done, total, state);
    }

    /** Le lecteur de Bulle lui-même (page, script, style, polices) : copié une fois, rafraîchi à chaque téléchargement. */
    private void saveReader() {
        File dir = staticDir();
        try {
            byte[] html = fetch(server + "/read/0");
            write(new File(dir, "read.html"), html);
            Set<String> refs = new LinkedHashSet<>();
            collect(new String(html, StandardCharsets.UTF_8), refs);
            for (String path : new ArrayList<>(refs)) {
                byte[] b = fetch(server + path);
                write(new File(dir, path.substring(1)), b);
                if (path.endsWith(".css")) collect(new String(b, StandardCharsets.UTF_8), refs);
            }
            for (String path : refs) {
                File f = new File(dir, path.substring(1));
                if (!f.exists()) write(f, fetch(server + path));
            }
            write(new File(dir, "settings.json"), fetch(server + "/api/settings"));
        } catch (Exception e) {
            Log.i(TAG, "copie du lecteur incomplète : " + e);
        }
    }

    private static void collect(String text, Set<String> out) {
        Matcher mt = STATIC_REF.matcher(text);
        while (mt.find()) {
            String p = mt.group(1);
            if (!p.endsWith(".webmanifest")) out.add(p);
        }
    }

    // ------------------------------------------------------------------ réseau

    private Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        String c = CookieManager.getInstance().getCookie(server);
        if (c != null) h.put("Cookie", c);
        return h;
    }

    private byte[] fetch(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(60000);
            for (Map.Entry<String, String> e : headers().entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("HTTP " + code + " pour " + url);
            return readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private int send(String method, String url, String json) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(5000);
            c.setReadTimeout(10000);
            c.setRequestMethod(method);
            c.setDoOutput(true);
            for (Map.Entry<String, String> e : headers().entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
            c.setRequestProperty("Content-Type", "application/json");
            c.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
            return c.getResponseCode();
        } finally {
            c.disconnect();
        }
    }

    /** Le serveur répond-il ? (simple ouverture de connexion, réponse gardée 8 secondes) */
    boolean serverUp() {
        long now = System.currentTimeMillis();
        if (now - upCheckedAt < 8000) return upCached;
        boolean up;
        try (Socket s = new Socket()) {
            Uri u = Uri.parse(server);
            int port = u.getPort() != -1 ? u.getPort() : ("https".equals(u.getScheme()) ? 443 : 80);
            s.connect(new InetSocketAddress(u.getHost(), port), 1500);
            up = true;
        } catch (Exception e) {
            up = false;
        }
        upCached = up;
        upCheckedAt = now;
        return up;
    }

    /** Dernier état connu, sans réseau (utilisable depuis l'interface). */
    boolean lastKnownUp() { return upCached; }

    void markDown() {
        upCached = false;
        upCheckedAt = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ réponses à la place du serveur

    private static final Pattern PAGE = Pattern.compile("^/api/page/(\\d+)/(\\d+)$");
    private static final Pattern PANELS = Pattern.compile("^/api/panels/(\\d+)/(\\d+)$");
    private static final Pattern ITEM = Pattern.compile("^/api/item/(\\d+)$");
    private static final Pattern READ = Pattern.compile("^/read/(\\d+)$");
    private static final Pattern PROGRESS = Pattern.compile("^/api/progress/(\\d+)$");
    private static final Pattern COVER = Pattern.compile("^" + LIST_PATH + "/couverture/(\\d+)$");

    /** Réponse locale pour cette requête, ou null pour laisser faire le réseau. */
    WebResourceResponse intercept(WebResourceRequest req) {
        Uri u = req.getUrl();
        String path = u.getPath() == null ? "/" : u.getPath();
        String method = req.getMethod();
        Matcher mt;
        try {
            if (path.equals(LIST_PATH)) return html(listPage());
            if ((mt = COVER.matcher(path)).matches()) return file(new File(albumDir(int1(mt)), "p0000.jpg"), "image/jpeg");

            // pages et cases d'un album gardé : toujours depuis le téléphone
            if ((mt = PAGE.matcher(path)).matches() && "GET".equals(method)) {
                File f = new File(albumDir(int1(mt)), String.format(Locale.ROOT, "p%04d.jpg", Integer.parseInt(mt.group(2))));
                return f.exists() ? file(f, "image/jpeg") : null;
            }
            if ((mt = PANELS.matcher(path)).matches() && "GET".equals(method)) {
                File f = new File(albumDir(int1(mt)), String.format(Locale.ROOT, "c%04d.json", Integer.parseInt(mt.group(2))));
                return f.exists() ? file(f, "application/json") : null;
            }

            // le reste seulement si le serveur ne répond pas
            boolean known = false;
            int id = -1;
            if ((mt = ITEM.matcher(path)).matches() || (mt = READ.matcher(path)).matches() || (mt = PROGRESS.matcher(path)).matches()) {
                id = int1(mt);
                known = meta(id) != null;
            }
            boolean reader = path.startsWith("/static/") || path.equals("/api/settings");
            if (!known && !reader) return null;
            if (serverUp()) return null;

            if (path.startsWith("/static/")) return file(new File(staticDir(), path.substring(1)), mime(path));
            if (path.equals("/api/settings")) return file(new File(staticDir(), "settings.json"), "application/json");
            if (READ.matcher(path).matches()) return file(new File(staticDir(), "read.html"), "text/html");
            if (ITEM.matcher(path).matches()) {
                JSONObject item = new JSONObject(new String(read(new File(albumDir(id), "item.json")), StandardCharsets.UTF_8));
                JSONObject m = meta(id);
                if (m != null && m.has("page")) {
                    JSONObject p = item.optJSONObject("progress");
                    if (p == null) p = new JSONObject();
                    p.put("page", m.optInt("page", 0));
                    p.put("pages", m.optInt("pages", 0));
                    item.put("progress", p);
                }
                return bytes(item.toString().getBytes(StandardCharsets.UTF_8), "application/json");
            }
            if (PROGRESS.matcher(path).matches()) {
                // enregistrement de la progression sans réseau : accepté ici, renvoyé au serveur plus tard
                JSONObject m = meta(id);
                JSONObject p = new JSONObject();
                if (m != null) p.put("page", m.optInt("page", 0)).put("pages", m.optInt("pages", 0));
                return bytes(p.toString().getBytes(StandardCharsets.UTF_8), "application/json");
            }
        } catch (Exception e) {
            Log.i(TAG, "hors connexion, " + path + " : " + e);
        }
        return null;
    }

    private static int int1(Matcher m) { return Integer.parseInt(m.group(1)); }

    private static String mime(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".woff")) return "font/woff";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    private static WebResourceResponse file(File f, String mime) throws IOException {
        if (!f.exists()) return null;
        return bytes(read(f), mime);
    }

    private static WebResourceResponse bytes(byte[] b, String mime) {
        String enc = mime.startsWith("text/") || mime.contains("json") || mime.contains("javascript") ? "utf-8" : null;
        WebResourceResponse r = new WebResourceResponse(mime, enc, new ByteArrayInputStream(b));
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-store");
        r.setResponseHeaders(h);
        return r;
    }

    private static WebResourceResponse html(String s) {
        return bytes(s.getBytes(StandardCharsets.UTF_8), "text/html");
    }

    // ------------------------------------------------------------------ page « Hors connexion »

    private String listPage() {
        StringBuilder rows = new StringBuilder();
        try {
            JSONArray arr = new JSONArray(list());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.getJSONObject(i);
                int id = m.optInt("id");
                int pages = m.optInt("pages", 0), page = m.optInt("page", 0);
                boolean ready = m.optBoolean("ready", false);
                String status = ready
                        ? (page > 0 ? "Page " + (page + 1) + " sur " + pages : pages + " pages")
                        : "Incomplet : " + m.optInt("saved", 0) + " / " + pages + " pages";
                rows.append("<li class=\"alb\"><a class=\"cov\" href=\"/read/").append(id).append("?page=").append(page)
                        .append("\"><img loading=\"lazy\" src=\"").append(LIST_PATH).append("/couverture/").append(id).append("\" alt=\"\"></a>")
                        .append("<div class=\"txt\"><b>").append(esc(m.optString("name"))).append("</b>")
                        .append("<small>").append(esc(m.optString("folder", ""))).append("</small>")
                        .append("<small>").append(status).append(" · ").append(mb(m.optLong("bytes", 0))).append("</small>")
                        .append("<div class=\"acts\">")
                        .append(ready ? "<a class=\"btn primary\" href=\"/read/" + id + "?page=" + page + "\">Lire</a>"
                                : "<button class=\"btn primary\" type=\"button\" onclick=\"BulleApp.offlineDownload(" + id + ");this.textContent='Reprise…'\">Reprendre</button>")
                        .append("<button class=\"btn\" type=\"button\" onclick=\"if(confirm('Retirer cet album du téléphone ?')){BulleApp.offlineRemove(").append(id).append(");location.reload()}\">Retirer</button>")
                        .append("</div></div></li>");
            }
        } catch (Exception e) {
            Log.i(TAG, "liste hors connexion : " + e);
        }
        boolean up = serverUp();
        return "<!doctype html><html lang=\"fr\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
                + "<title>Hors connexion — Bulle</title><style>"
                + ":root{color-scheme:dark}*{box-sizing:border-box}body{margin:0;background:#121318;color:#f1f1f4;font:16px/1.4 system-ui,Roboto,sans-serif}"
                + "header{display:flex;align-items:center;gap:12px;padding:16px 18px;border-bottom:1px solid #2c2f3a}"
                + "header h1{margin:0;font-size:22px;color:#ffd43b;font-style:italic;font-weight:900;text-shadow:2px 2px 0 #000;flex:1}"
                + "header a{color:#f1f1f4;text-decoration:none;border:1px solid #2c2f3a;border-radius:10px;padding:8px 12px;font-size:14px}"
                + ".note{color:#9a9cab;font-size:14px;margin:14px 18px}ul{list-style:none;margin:0;padding:6px 12px 30px}"
                + ".alb{display:flex;gap:14px;padding:12px 6px;border-bottom:1px solid #22252f}.cov img{width:88px;height:124px;object-fit:cover;border-radius:6px;background:#1c1e26;box-shadow:3px 3px 0 #000}"
                + ".txt{display:flex;flex-direction:column;gap:3px;min-width:0}.txt b{font-size:16px}.txt small{color:#9a9cab;font-size:13px}"
                + ".acts{display:flex;gap:8px;margin-top:8px}.btn{border:1px solid #2c2f3a;background:transparent;color:#f1f1f4;border-radius:10px;padding:8px 14px;font-size:14px;font-weight:600;text-decoration:none}"
                + ".btn.primary{background:#ffd43b;color:#1a1a1a;border-color:#ffd43b}.empty{text-align:center;color:#9a9cab;padding:60px 24px}"
                + "</style></head><body><header><h1>Hors connexion</h1>"
                + (up ? "<a href=\"/\">Bibliothèque</a>" : "<a href=\"#\" onclick=\"BulleApp.retry();return false\">Réessayer</a>")
                + "</header>"
                + "<p class=\"note\">" + (up ? "Ces albums sont gardés sur le téléphone : ils se lisent même sans réseau."
                : "Pas de connexion à votre serveur Bulle : voici les albums gardés sur le téléphone.") + "</p>"
                + (rows.length() == 0 ? "<p class=\"empty\">Aucun album gardé pour l'instant.<br><br>Dans la bibliothèque, ouvrez la fiche d'un album (bouton « i ») puis « Garder hors connexion ».</p>" : "<ul>" + rows + "</ul>")
                + "</body></html>";
    }

    private static String mb(long b) {
        return String.format(Locale.FRANCE, "%.1f Mo", b / 1048576.0);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ------------------------------------------------------------------ fichiers

    private static byte[] read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        return out.toByteArray();
    }

    private static void write(File f, byte[] data) throws IOException {
        File parent = f.getParentFile();
        if (parent != null) //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        File tmp = new File(f.getPath() + ".part");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
        }
        if (!tmp.renameTo(f)) throw new IOException("écriture impossible : " + f);
    }

    @SuppressWarnings("unused")
    List<Integer> ids() {
        List<Integer> l = new ArrayList<>();
        File[] dirs = serverDir().listFiles(File::isDirectory);
        if (dirs != null) for (File d : dirs) if (d.getName().matches("\\d+")) l.add(Integer.parseInt(d.getName()));
        return l;
    }
}
