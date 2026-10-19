package io.github.kermess14.bulle;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Bulle pour Android : une « coquille » autour de l'interface web de Bulle.
 * Au premier lancement, on choisit son serveur (ou on le cherche sur le Wi-Fi) ; ensuite l'appli s'ouvre
 * directement sur la bibliothèque. Le lecteur passe en plein écran et garde l'écran allumé.
 */
public class MainActivity extends Activity {
    private static final String PREFS = "bulle";
    private static final String KEY_SERVER = "server";
    private static final String SETUP = "file:///android_asset/setup.html";
    private static final String ERROR = "file:///android_asset/error.html";
    private static final int DEFAULT_PORT = 2205;
    private static final int PICK_FILE = 41;
    static final String ACTION_CHANGE_SERVER = "io.github.kermess14.bulle.CHANGE_SERVER";

    private WebView web;
    private FrameLayout root;
    private SharedPreferences prefs;
    private boolean reading = false;            // dans le lecteur : plein écran, écran allumé
    private String failedUrl = null;            // page qui n'a pas pu s'ouvrir (pour « Réessayer »)
    private String failedReason = "";
    private ValueCallback<Uri[]> pendingFiles = null;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newCachedThreadPool();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            applyPadding(insets);
            return insets;
        });

        setupWebView();
        setupBack();

        if (ACTION_CHANGE_SERVER.equals(getIntent().getAction())) { web.loadUrl(SETUP); return; }
        if (state != null && web.restoreState(state) != null) return;
        String server = server();
        if (server.isEmpty()) web.loadUrl(SETUP);
        else web.loadUrl(server + "/");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.i("BulleApp", "onNewIntent " + intent.getAction());
        setIntent(intent);
        if (ACTION_CHANGE_SERVER.equals(intent.getAction())) {
            setReading(false);
            web.loadUrl(SETUP);
        }
    }

    // ------------------------------------------------------------------ affichage

    /** Marges pour ne pas passer sous la barre d'état et la barre de navigation (sauf dans le lecteur, en plein écran). */
    private void applyPadding(WindowInsets insets) {
        if (reading) {
            root.setPadding(0, 0, 0, 0);
            return;
        }
        int l, t, r, b;
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout());
            l = i.left; t = i.top; r = i.right; b = i.bottom;
        } else {
            l = insets.getSystemWindowInsetLeft(); t = insets.getSystemWindowInsetTop();
            r = insets.getSystemWindowInsetRight(); b = insets.getSystemWindowInsetBottom();
        }
        root.setPadding(l, t, r, b);
    }

    /** Lecteur : plein écran (les barres reviennent d'un glissement depuis le bord) et écran toujours allumé. */
    private void setReading(boolean on) {
        if (on == reading) return;
        reading = on;
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                if (on) {
                    c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    c.hide(WindowInsets.Type.systemBars());
                } else {
                    c.show(WindowInsets.Type.systemBars());
                }
            }
        } else {
            int flags = on
                    ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                      | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    : View.SYSTEM_UI_FLAG_VISIBLE;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
        root.requestApplyInsets();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && reading) { reading = false; setReading(true); }   // le plein écran revient après une boîte de dialogue
    }

    // ------------------------------------------------------------------ WebView

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setSupportZoom(false);                 // le zoom des planches est géré par Bulle lui-même
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setUserAgentString(s.getUserAgentString() + " BulleAndroid/" + versionName());
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.addJavascriptInterface(new Bridge(), "BulleApp");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String url = u.toString();
                if (url.startsWith("file:///android_asset/")) return false;
                if (isOurServer(u)) return false;
                // lien vers un autre site (Bédéthèque, GitHub…) : ouvert dans le navigateur du téléphone
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(MainActivity.this, "Aucune application pour ouvrir ce lien", Toast.LENGTH_SHORT).show();
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                Log.i("BulleApp", "page : " + url);
                setReading(isReaderUrl(url));
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                setReading(isReaderUrl(url));
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame()) return;
                String url = request.getUrl().toString();
                if (url.startsWith("file:")) return;
                failedUrl = url;
                failedReason = String.valueOf(error.getDescription());
                setReading(false);
                view.loadUrl(ERROR);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            // choix d'un fichier (photo de profil…)
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (pendingFiles != null) pendingFiles.onReceiveValue(null);
                pendingFiles = callback;
                try {
                    startActivityForResult(params.createIntent(), PICK_FILE);
                } catch (ActivityNotFoundException e) {
                    pendingFiles = null;
                    return false;
                }
                return true;
            }
        });

        // téléchargement d'un album (bouton « Télécharger » de la fiche) : confié au gestionnaire de téléchargements
        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) -> {
            try {
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                String cookies = CookieManager.getInstance().getCookie(url);
                if (cookies != null) r.addRequestHeader("Cookie", cookies);
                r.addRequestHeader("User-Agent", userAgent);
                String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
                r.setTitle(name);
                r.setMimeType(mimeType);
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                dm.enqueue(r);
                Toast.makeText(this, "Téléchargement de « " + name + " »…", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Téléchargement impossible : " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_FILE && pendingFiles != null) {
            pendingFiles.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            pendingFiles = null;
        }
    }

    private boolean isReaderUrl(String url) {
        if (url == null || !url.startsWith("http")) return false;
        String path = Uri.parse(url).getPath();
        return path != null && path.startsWith("/read/");
    }

    private boolean isOurServer(Uri u) {
        String server = server();
        if (server.isEmpty()) return false;
        Uri s = Uri.parse(server);
        return u.getHost() != null && u.getHost().equalsIgnoreCase(s.getHost()) && port(u) == port(s);
    }

    private static int port(Uri u) {
        if (u.getPort() != -1) return u.getPort();
        return "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
    }

    // ------------------------------------------------------------------ retour arrière

    private void setupBack() {
        if (Build.VERSION.SDK_INT >= 33) {
            OnBackInvokedCallback cb = this::goBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        goBack();
    }

    private void goBack() {
        String url = web.getUrl();
        Log.i("BulleApp", "retour : url=" + url + " canGoBack=" + web.canGoBack() + " historique=" + web.copyBackForwardList().getSize());
        if (url != null && url.startsWith(ERROR)) { finish(); return; }
        if (web.canGoBack()) web.goBack();
        else finish();
    }

    // ------------------------------------------------------------------ cycle de vie

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.i("BulleApp", "onPause (finishing=" + isFinishing() + ")");
        web.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        bg.shutdownNow();
        web.destroy();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ serveur

    private String server() {
        return prefs.getString(KEY_SERVER, "");
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** « 192.168.0.10 », « 192.168.0.10:2205 », « http://nas.local:2205/ »… → « http://192.168.0.10:2205 ». */
    static String normalize(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return "";
        if (!s.matches("(?i)^https?://.*")) s = "http://" + s;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        Uri u = Uri.parse(s);
        if (u.getHost() == null || u.getHost().isEmpty()) return "";
        if (u.getPort() == -1 && "http".equalsIgnoreCase(u.getScheme()) && (u.getPath() == null || u.getPath().isEmpty()))
            s = s + ":" + DEFAULT_PORT;          // pas de port précisé : celui de Bulle par défaut
        return s;
    }

    /** Renvoie la version de Bulle si l'adresse répond comme un serveur Bulle, sinon null. */
    static String probe(String base, int timeoutMs) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + "/api/settings").openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs * 3);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) return null;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0 && buf.size() < 256 * 1024) buf.write(b, 0, n);
            String body = buf.toString("UTF-8");
            if (code == 401 && body.contains("\"")) return "?";     // Bulle protégé par un code d'accès : c'est bien lui
            JSONObject j = new JSONObject(body);
            if (j.has("version") && (j.has("global") || j.has("profile"))) return j.optString("version", "?");
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Adresse IPv4 du téléphone sur le réseau local (pour chercher Bulle autour). */
    private String localIPv4() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            if (n == null) return null;
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) return null;
            for (LinkAddress la : lp.getLinkAddresses()) {
                InetAddress a = la.getAddress();
                if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void js(String code) {
        ui.post(() -> web.evaluateJavascript(code, null));
    }

    private static String q(String s) {
        return JSONObject.quote(s == null ? "" : s);
    }

    /** Ce que les pages de l'appli (choix du serveur, erreur) peuvent demander. */
    private class Bridge {
        @JavascriptInterface
        public String getServer() {
            return server();
        }

        @JavascriptInterface
        public String getVersion() {
            return versionName();
        }

        @JavascriptInterface
        public String getFailure() {
            try {
                JSONObject j = new JSONObject();
                j.put("url", failedUrl == null ? "" : failedUrl);
                j.put("reason", failedReason);
                j.put("server", server());
                return j.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        /** Teste l'adresse puis, si c'est bien Bulle, l'enregistre et l'ouvre. Réponse via window.onConnect(ok, message). */
        @JavascriptInterface
        public void connect(String raw) {
            final String base = normalize(raw);
            if (base.isEmpty()) {
                js("window.onConnect && onConnect(false, " + q("Adresse incomplète") + ")");
                return;
            }
            bg.execute(() -> {
                String v = probe(base, 4000);
                if (v == null && base.startsWith("http://") && base.matches(".*:\\d+$") == false) v = probe(base + ":" + DEFAULT_PORT, 4000);
                if (v == null) {
                    js("window.onConnect && onConnect(false, " + q("Pas de réponse de Bulle à l'adresse " + base) + ")");
                    return;
                }
                prefs.edit().putString(KEY_SERVER, base).apply();
                final String target = base + "/";
                js("window.onConnect && onConnect(true, " + q("Connecté à Bulle " + v) + ")");
                ui.postDelayed(() -> {
                    web.clearHistory();
                    web.loadUrl(target);
                }, 400);
            });
        }

        /** Cherche Bulle sur le Wi-Fi (port 2205, toutes les adresses du réseau). Réponse via window.onScan(liste). */
        @JavascriptInterface
        public void scan() {
            final String me = localIPv4();
            if (me == null) {
                js("window.onScan && onScan([], " + q("Pas de réseau Wi-Fi") + ")");
                return;
            }
            bg.execute(() -> {
                String prefix = me.substring(0, me.lastIndexOf('.') + 1);
                List<String> found = Collections.synchronizedList(new ArrayList<>());
                ExecutorService pool = Executors.newFixedThreadPool(48);
                for (int i = 1; i < 255; i++) {
                    final String base = "http://" + prefix + i + ":" + DEFAULT_PORT;
                    pool.execute(() -> {
                        String v = probe(base, 700);
                        if (v != null) found.add(base + "|" + v);
                    });
                }
                pool.shutdown();
                try {
                    pool.awaitTermination(25, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
                JSONArray arr = new JSONArray();
                for (String f : found) {
                    String[] p = f.split("\\|", 2);
                    try {
                        arr.put(new JSONObject().put("url", p[0]).put("version", p.length > 1 ? p[1] : "?"));
                    } catch (Exception ignored) {
                    }
                }
                js("window.onScan && onScan(" + arr + ", " + q("Réseau " + prefix + "x") + ")");
            });
        }

        @JavascriptInterface
        public void retry() {
            ui.post(() -> web.loadUrl(failedUrl != null ? failedUrl : (server().isEmpty() ? SETUP : server() + "/")));
        }

        @JavascriptInterface
        public void changeServer() {
            ui.post(() -> {
                setReading(false);
                web.loadUrl(SETUP);
            });
        }
    }
}
