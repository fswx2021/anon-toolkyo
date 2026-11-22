package com.shengjian.player;

import android.app.Activity;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.View;

import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class AnimeWebPlayer implements AutoCloseable {
    interface Callback {
        void media(String url, String referer);
        void pageReady();
        void failed(String reason);
        void position(long milliseconds, boolean ended, boolean playing, double rate);
        void fullscreen(View content, WebChromeClient.CustomViewCallback callback);
        void exitFullscreen();
    }

    private static final String MEDIA_PROBE = """
            function(w, legacy) {
                if (typeof w.__anonToolVideoProbe === 'function') return;
                const d = w.document;
                const bridge = w.anonToolVideoMedia;
                const post = bridge && bridge.postMessage.bind(bridge);
                const reported = new w.Set();
                let best;
                function record(value, kind) {
                    if (!value || value.length > 16000) return;
                    try {
                        const url = new w.URL(value, d.baseURI);
                        if (!/^https?:$/.test(url.protocol)) return;
                        if (/(^|\\.)(googlesyndication\\.com|doubleclick\\.net|adtrafficquality\\.google)$/.test(url.hostname) ||
                                /prestrain(?:\\.|%2e)html/i.test(url.pathname)) return;
                        if (kind === 'iframe') {
                            if (!legacy) return;
                            for (const parameter of url.searchParams.values()) {
                                if (/^https?:/i.test(parameter) && /\\.(?:m3u8?|mpd|mp4|webm)(?:[?#]|$)/i.test(parameter)) {
                                    record(parameter, 'embedded');
                                }
                            }
                            return;
                        }
                        const next = {url: url.href, ref: d.URL, kind: kind};
                        const strength = {resource: 1, embedded: 2, video: 3, playlist: 4};
                        if (!best || strength[best.kind] < strength[kind]) best = next;
                        const key = kind + ':' + url.href;
                        if (post && reported.size < 64 && !reported.has(key)) {
                            reported.add(key);
                            post(JSON.stringify(next));
                        }
                    } catch (_) {}
                }
                function observeVideo(video) {
                    if (video.currentSrc || video.src) record(video.currentSrc || video.src, 'video');
                    if (!video.currentSrc) {
                        for (const source of video.querySelectorAll('source[src]')) record(source.src, 'video');
                    }
                }
                function inspectPage() {
                    for (const video of d.querySelectorAll('video')) observeVideo(video);
                    if (legacy) for (const frame of d.querySelectorAll('iframe[src]')) record(frame.src, 'iframe');
                    if (!best && w.performance) {
                        for (const entry of w.performance.getEntriesByType('resource')) {
                            if (/\\.(?:m3u8?|mpd)(?:[?#]|$)/i.test(entry.name)) record(entry.name, 'resource');
                        }
                    }
                    return best || null;
                }
                w.__anonToolVideoProbe = inspectPage;
                d.addEventListener('loadstart', event => {
                    if (event.target instanceof w.HTMLVideoElement) observeVideo(event.target);
                }, true);
                let queued = false;
                new w.MutationObserver(mutations => {
                    if (queued || !mutations.some(change => change.type === 'attributes' ||
                            Array.from(change.addedNodes).some(node => node.nodeType === 1))) return;
                    queued = true;
                    w.setTimeout(() => { queued = false; inspectPage(); }, 80);
                }).observe(d, {childList: true, subtree: true, attributes: true, attributeFilter: ['src']});
                function inspectBody(url, body) {
                    if (typeof body === 'string' && /^\\s*#EXTM3U(?:\\r?\\n|$)/.test(body)) record(url, 'playlist');
                }
                function inspectType(url, type) {
                    if (/^(?:application\\/(?:vnd\\.apple\\.mpegurl|x-mpegurl|dash\\+xml)|audio\\/(?:mpegurl|x-mpegurl))(?:[; ]|$)/i.test(type || '')) {
                        record(url, 'playlist');
                    }
                }
                const getResponses = new w.WeakSet();
                if (w.Response && w.fetch) {
                    const originalText = w.Response.prototype.text;
                    w.Response.prototype.text = function() {
                        const pending = originalText.apply(this, arguments);
                        if (getResponses.has(this)) pending.then(body => inspectBody(this.url, body), () => {});
                        return pending;
                    };
                    const originalFetch = w.fetch;
                    w.fetch = function(input, init) {
                        const pending = originalFetch.apply(this, arguments);
                        if (String((init && init.method) || (input && input.method) || 'GET').toUpperCase() === 'GET') {
                            pending.then(response => {
                                if (!response.ok) return;
                                getResponses.add(response);
                                inspectType(response.url, response.headers.get('Content-Type'));
                            }, () => {});
                        }
                        return pending;
                    };
                }
                if (w.XMLHttpRequest) {
                    const originalOpen = w.XMLHttpRequest.prototype.open;
                    const requests = new w.WeakMap();
                    w.XMLHttpRequest.prototype.open = function(method) {
                        const result = originalOpen.apply(this, arguments);
                        const observed = requests.has(this);
                        requests.set(this, /^GET$/i.test(String(method)));
                        if (!observed) this.addEventListener('load', () => {
                            if (!requests.get(this) || this.status < 200 || this.status >= 300) return;
                            inspectType(this.responseURL, this.getResponseHeader('Content-Type'));
                            if (this.responseType === '' || this.responseType === 'text') {
                                inspectBody(this.responseURL, this.responseText);
                            } else if (this.responseType === 'arraybuffer' && this.response && w.TextDecoder) {
                                inspectBody(this.responseURL, new w.TextDecoder().decode(this.response.slice(0, 48)));
                            }
                        });
                        return result;
                    };
                }
                if (d.readyState === 'loading') d.addEventListener('DOMContentLoaded', inspectPage, {once: true});
                else inspectPage();
            }
            """;
    private static final String MEDIA_BRIDGE = "anonToolVideoMedia";
    private static final String FIND_VIDEO = "var q=[window],n=0,v=null;"
            + "while(q.length&&n++<12){try{var w=q.shift();v=w.document.querySelector('video');"
            + "if(v)break;for(var i=0;i<Math.min(w.frames.length,8);i++)q.push(w.frames[i]);}catch(e){}}";
    private static final long PROBE_INTERVAL = 500;
    private static final long CANDIDATE_WAIT = 600;

    private final Handler main;
    private final Callback callback;
    private final boolean sniff;
    private final boolean legacy;
    private final WebView view;
    private final Runnable probe = this::scan;
    private final Runnable selectCandidate = this::publishCandidate;
    private boolean closed, ready, paused, candidateSent, evaluating, restored;
    private boolean documentStartProbe;
    private long resume;
    private String pageUrl;
    private Candidate candidate;

    private record Candidate(String url, String referer, int strength) {}

    AnimeWebPlayer(Activity host, Handler main, Callback callback, boolean sniff, long resume, boolean legacy) {
        this.main = main;
        this.callback = callback;
        this.sniff = sniff;
        this.legacy = legacy;
        this.resume = resume;
        view = new WebView(host);
        view.setBackgroundColor(0xff000000);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false);
        view.setWebChromeClient(new WebChromeClient() {
            @Override public void onShowCustomView(View video, CustomViewCallback finished) {
                if (closed || AnimeWebPlayer.this.sniff) finished.onCustomViewHidden();
                else callback.fullscreen(video, finished);
            }

            @Override public void onHideCustomView() {
                if (!closed) callback.exitFullscreen();
            }
        });
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView web, WebResourceRequest request) {
                return !webAddress(request.getUrl().toString());
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView web, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (!webAddress(url)) return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                if (sniff && "GET".equalsIgnoreCase(request.getMethod())) {
                    Map<String, String> headers = request.getRequestHeaders();
                    String referer = header(headers, "Referer");
                    int strength = networkStrength(url, header(headers, "Sec-Fetch-Dest"));
                    if (strength > 0) main.post(() -> offerCandidate(url, referer, strength));
                    else if (legacy && !request.isForMainFrame() && "iframe".equals(header(headers, "Sec-Fetch-Dest"))) {
                        main.post(() -> offerEmbedded(url, referer));
                    }
                }
                return null;
            }

            @Override public void onPageStarted(WebView web, String url, Bitmap favicon) {
                if (!closed) {
                    ready = false;
                    pageUrl = url;
                    if (sniff && !documentStartProbe) scheduleProbe();
                }
            }

            @Override public void onPageFinished(WebView web, String url) {
                if (closed || !webAddress(url)) return;
                pageUrl = url;
                ready = true;
                callback.pageReady();
                scheduleProbe();
            }

            @Override public void onReceivedError(WebView web, WebResourceRequest request, WebResourceError error) {
                if (!closed && request.isForMainFrame()) callback.failed("播放页面加载失败，请检查线路或网络");
            }

            @Override public void onReceivedHttpError(WebView web, WebResourceRequest request, WebResourceResponse response) {
                if (!closed && request.isForMainFrame()) callback.failed("播放站点返回 HTTP " + response.getStatusCode());
            }

            @Override public boolean onRenderProcessGone(WebView web, RenderProcessGoneDetail detail) {
                if (!closed) callback.failed("系统网页播放器已停止，可重试或换一集");
                return true;
            }
        });
        if (sniff) installDocumentProbe();
    }

    WebView view() { return view; }

    void load(String url, Map<String, String> headers) {
        if (!webAddress(url)) throw new IllegalArgumentException("Unsupported playback address");
        Map<String, String> allowed = new LinkedHashMap<>();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (header.getKey().equalsIgnoreCase("User-Agent")) view.getSettings().setUserAgentString(header.getValue());
            if (header.getKey().equalsIgnoreCase("Referer") && webAddress(header.getValue())) {
                Uri referer = Uri.parse(header.getValue());
                allowed.put("Referer", referer.buildUpon().clearQuery().fragment(null).toString());
            }
        }
        pageUrl = url;
        view.loadUrl(url, allowed);
    }

    void pause() {
        if (closed || paused) return;
        paused = true;
        main.removeCallbacks(probe);
        main.removeCallbacks(selectCandidate);
        if (ready) view.evaluateJavascript("(function(){" + FIND_VIDEO + "if(v)v.pause();})()", null);
        view.onPause();
    }

    void resume() {
        if (closed || !paused) return;
        paused = false;
        view.onResume();
        if (sniff) {
            if (candidate != null && !candidateSent) main.postDelayed(selectCandidate, CANDIDATE_WAIT);
            scheduleProbe();
        }
    }

    private void installDocumentProbe() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return;
        // The listener only accepts a media candidate. It exposes no native operations to the page.
        Set<String> origins = Collections.singleton("*");
        WebViewCompat.addWebMessageListener(view, MEDIA_BRIDGE, origins,
                (web, message, sourceOrigin, isMainFrame, reply) -> {
                    if (!closed && !candidateSent && message.getType() == WebMessageCompat.TYPE_STRING) {
                        acceptEvidence(json(message.getData()), sourceOrigin.toString());
                    }
                });
        WebViewCompat.addDocumentStartJavaScript(view, "(" + MEDIA_PROBE + ")(window," + legacy + ")", origins);
        documentStartProbe = true;
    }

    private void scheduleProbe() {
        main.removeCallbacks(probe);
        if (!closed && !paused && sniff && !candidateSent) main.postDelayed(probe, PROBE_INTERVAL);
    }

    private void scan() {
        if (closed || paused || candidateSent) return;
        String scan = String.format(Locale.ROOT, """
                (function() {
                    const install = %s;
                    const frames = [window];
                    const strength = {resource: 1, embedded: 2, video: 3, playlist: 4};
                    let best;
                    for (let i = 0; i < frames.length && i < 12; i++) {
                        try {
                            const frame = frames[i];
                            install(frame, %s);
                            const detected = frame.__anonToolVideoProbe();
                            if (detected && (!best || strength[best.kind] < strength[detected.kind])) best = detected;
                            for (let j = 0; j < Math.min(frame.frames.length, 8); j++) frames.push(frame.frames[j]);
                        } catch (_) {}
                    }
                    return best || null;
                })()
                """, MEDIA_PROBE, legacy);
        view.evaluateJavascript(scan, raw -> {
            if (closed || paused) return;
            acceptEvidence(json(raw), null);
            scheduleProbe();
        });
    }

    void samplePosition() {
        if (closed || paused || sniff || !ready || evaluating) return;
        evaluating = true;
        view.evaluateJavascript("(function(){" + FIND_VIDEO
                + "return v&&v.readyState>0?{ms:v.currentTime*1000,end:v.ended,duration:v.duration*1000,"
                + "playing:!v.paused&&!v.ended&&!v.seeking&&v.readyState>=3,rate:v.playbackRate}:null;})()", raw -> {
            evaluating = false;
            if (closed || paused) return;
            JSONObject result = json(raw);
            if (result == null) return;
            long position = result.optLong("ms", -1);
            long duration = result.optLong("duration", 0);
            if (!restored && resume > 0 && duration > resume) {
                restored = true;
                view.evaluateJavascript("(function(){" + FIND_VIDEO + "if(v)v.currentTime=" + resume / 1000.0 + ";})()", null);
                return;
            }
            if (position >= 0) {
                callback.position(position, result.optBoolean("end"), result.optBoolean("playing"),
                        result.optDouble("rate", 1));
            }
        });
    }

    private void acceptEvidence(JSONObject result, String origin) {
        if (result == null) return;
        String referer = result.optString("ref");
        if (origin != null) {
            if (!webAddress(origin) || adAddress(origin)) return;
            if (!sameOrigin(referer, origin)) referer = origin + "/";
        }
        int strength = switch (result.optString("kind")) {
            case "playlist" -> 4;
            case "video" -> 3;
            case "embedded" -> legacy ? 2 : 0;
            case "resource" -> 1;
            default -> 0;
        };
        offerCandidate(result.optString("url"), referer, strength);
    }

    private static boolean sameOrigin(String address, String origin) {
        if (!webAddress(address)) return false;
        URI page = URI.create(address);
        URI source = URI.create(origin);
        return page.getScheme().equalsIgnoreCase(source.getScheme())
                && page.getHost().equalsIgnoreCase(source.getHost()) && originPort(page) == originPort(source);
    }

    private static int originPort(URI uri) {
        return uri.getPort() != -1 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private void offerEmbedded(String address, String referer) {
        String query = URI.create(address).getRawQuery();
        if (query == null) return;
        for (String entry : query.split("&")) {
            int separator = entry.indexOf('=');
            if (separator < 0) continue;
            String url = Uri.decode(entry.substring(separator + 1));
            if (networkStrength(url, null) > 0 && !adAddress(url)) {
                offerCandidate(url, referer, 2);
                return;
            }
        }
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers != null) for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        }
        return null;
    }

    private static int networkStrength(String address, String destination) {
        if (!webAddress(address)) return 0;
        String path = URI.create(address).getPath().toLowerCase(Locale.ROOT);
        if (path.endsWith(".m3u8") || path.endsWith(".m3u") || path.endsWith(".mpd")) return 2;
        if (path.endsWith(".ts") || path.endsWith(".m4s") || path.endsWith(".cmfv") || path.endsWith(".cmfa")) return 0;
        return "video".equalsIgnoreCase(destination) || AnimeEngine.isDirectMedia(address) ? 1 : 0;
    }

    private static boolean adAddress(String url) {
        URI uri = URI.create(url);
        String domain = uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath().toLowerCase(Locale.ROOT);
        return domain.contains("googleads") || domain.contains("googlesyndication") || domain.contains("adtrafficquality")
                || domain.contains("doubleclick") || path.contains("prestrain.html");
    }

    private void offerCandidate(String url, String referer, int strength) {
        if (closed || candidateSent || !sniff || strength == 0 || !webAddress(url) || adAddress(url)) return;
        if (candidate != null && candidate.strength() >= strength) return;
        if (candidate == null && !paused) main.postDelayed(selectCandidate, CANDIDATE_WAIT);
        candidate = new Candidate(url, webAddress(referer) ? referer : pageUrl, strength);
    }

    private void publishCandidate() {
        if (closed || paused || candidateSent || candidate == null) return;
        candidateSent = true;
        main.removeCallbacks(probe);
        callback.media(candidate.url(), candidate.referer());
    }

    static boolean webAddress(String value) {
        if (value == null || value.length() > 16_384) return false;
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            String scheme = uri.getScheme();
            if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) || host == null
                    || uri.getRawUserInfo() != null || uri.getPort() == 0 || uri.getPort() > 65_535) return false;
            String lower = host.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".")) lower = lower.substring(0, lower.length() - 1);
            if (lower.startsWith("[")) {
                // Accept public IPv6 literals; reject mapped, scoped, private and link-local forms.
                return !lower.contains("%") && lower.matches("\\[[23][0-9a-f]{3}:[0-9a-f:]+]");
            }
            if (!lower.contains(".") || lower.endsWith(".localhost") || lower.endsWith(".local")
                    || lower.matches("[0-9]+(?:\\.[0-9]+){0,2}")) return false;
            if (!lower.matches("[0-9.]+")) return true;
            String[] parts = lower.split("\\.");
            if (parts.length != 4) return false;
            int a = Integer.parseInt(parts[0]), b = Integer.parseInt(parts[1]);
            for (String part : parts) if (Integer.parseInt(part) > 255 || (part.length() > 1 && part.startsWith("0"))) return false;
            return a > 0 && a < 224 && a != 10 && a != 127 && !(a == 169 && b == 254)
                    && !(a == 172 && b >= 16 && b <= 31) && !(a == 192 && b == 168)
                    && !(a == 100 && b >= 64 && b <= 127);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static JSONObject json(String raw) {
        if (raw == null || raw.length() > 20_000) return null;
        try {
            Object value = new JSONTokener(raw).nextValue();
            return value instanceof JSONObject ? (JSONObject) value : null;
        } catch (JSONException invalid) {
            return null;
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        main.removeCallbacks(probe);
        main.removeCallbacks(selectCandidate);
        view.stopLoading();
        view.setWebChromeClient(null);
        view.setWebViewClient(new WebViewClient());
        if (view.getParent() instanceof android.view.ViewGroup parent) parent.removeView(view);
        view.destroy();
    }
}
