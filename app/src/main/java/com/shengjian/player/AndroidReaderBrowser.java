package com.shengjian.player;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

final class AndroidReaderBrowser implements ReaderSources.BrowserLoader {
    private static final int MAX_BODY = 8 * 1024 * 1024;
    private static final int MAX_SCRIPT = 240_000;
    private static final Semaphore SLOTS = new Semaphore(2, true);
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());

    AndroidReaderBrowser(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public ReaderSources.Response load(String url, Map<String, String> headers, String pageScript,
                                       long timeoutMillis, long settleMillis) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IOException("请在后台读取浏览器书源");
        if (!allowed(Uri.parse(url))) throw new IOException("浏览器书源需要有效的 HTTP 或 HTTPS 地址");
        if (pageScript != null && pageScript.length() > MAX_SCRIPT) throw new IOException("来源网页脚本过长");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, timeoutMillis));
        try {
            if (!SLOTS.tryAcquire(remaining(deadline), TimeUnit.NANOSECONDS)) throw timeout();
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw cancelled();
        }
        Job job = new Job(url, headers, pageScript, deadline, settleMillis);
        if (!main.post(job::start)) {
            SLOTS.release();
            throw new IOException("设备浏览器暂时不可用");
        }
        try {
            if (!job.done.await(remaining(deadline), TimeUnit.NANOSECONDS)) {
                job.cancel();
                throw timeout();
            }
        } catch (InterruptedException interrupted) {
            job.cancel();
            Thread.currentThread().interrupt();
            throw cancelled();
        }
        if (job.failure != null) throw job.failure;
        return job.result;
    }

    private static long remaining(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }

    private static IOException timeout() {
        return new SocketTimeoutException("浏览器书源加载超时");
    }

    private static IOException cancelled() {
        return new InterruptedIOException("浏览器书源加载已取消");
    }

    private static boolean allowed(Uri url) {
        String scheme = url.getScheme();
        return ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                && url.getHost() != null && url.getUserInfo() == null;
    }

    private static String extraction(String script) {
        String scriptLiteral = JSONObject.quote(script);
        return "(function(){"
                + "var body,mime=document.contentType||'text/html',custom;"
                + "var script=" + scriptLiteral + ";try{if(script){custom=(0,eval)(script);}"
                + "}catch(e){return {error:'script'};}"
                + "if(custom!=null&&typeof custom.then==='function')return {error:'async'};"
                + "if(custom!=null){body=typeof custom==='string'?custom:JSON.stringify(custom);"
                + "if(/^\\s*[\\[{]/.test(body))mime='application/json';}"
                + "else{var b=document.body,p=b&&b.childElementCount===1&&b.querySelector('pre');"
                + "if(p&&(/json|text\\/plain/i.test(mime)||/^\\s*[\\[{]/.test(p.textContent))){body=p.textContent;"
                + "if(/^\\s*[\\[{]/.test(body))mime='application/json';}"
                + "else{body=document.documentElement?document.documentElement.outerHTML:'';}}"
                + "if(!body)return {error:'empty'};"
                + "if(body.length>" + MAX_BODY + ")return {error:'size'};"
                + "return {body:body,mime:mime};"
                + "})()";
    }

    private final class Job {
        final CountDownLatch done = new CountDownLatch(1);
        final String url;
        final Map<String, String> headers;
        final String script;
        final long deadline, settleMillis;
        volatile boolean cancelled;
        private WebView browser;
        private Runnable captureTask;
        private int navigation;
        private boolean completed;
        ReaderSources.Response result;
        IOException failure;

        Job(String url, Map<String, String> headers, String script, long deadline, long settleMillis) {
            this.url = url;
            this.headers = headers == null ? Map.of() : new LinkedHashMap<>(headers);
            this.script = script == null ? "" : script;
            this.deadline = deadline;
            this.settleMillis = settleMillis;
        }

        void cancel() {
            cancelled = true;
            main.post(() -> finish(null, cancelled()));
        }

        void start() {
            if (cancelled) {
                finish(null, cancelled());
                return;
            }
            try {
                browser = new WebView(context);
                configure(browser);
                browser.loadUrl(url, safeHeaders(browser));
            } catch (RuntimeException error) {
                finish(null, new IOException("设备浏览器无法启动书源", error));
            }
        }

        private Map<String, String> safeHeaders(WebView view) {
            Map<String, String> result = new LinkedHashMap<>();
            for (Map.Entry<String, String> header : headers.entrySet()) {
                String name = header.getKey().toLowerCase(Locale.ROOT);
                if (name.equals("user-agent")) {
                    view.getSettings().setUserAgentString(header.getValue());
                } else if (name.equals("referer")) {
                    Uri referer = Uri.parse(header.getValue());
                    if (allowed(referer)) {
                        result.put(header.getKey(), referer.buildUpon().clearQuery().fragment(null).toString());
                    }
                } else if (name.equals("accept") || name.equals("accept-language")) {
                    result.put(header.getKey(), header.getValue());
                }
            }
            return result;
        }

        private void configure(WebView view) {
            WebSettings settings = view.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setJavaScriptCanOpenWindowsAutomatically(false);
            settings.setSupportMultipleWindows(false);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            settings.setDomStorageEnabled(true);
            settings.setBlockNetworkImage(true);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, false);
            view.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView web, WebResourceRequest request) {
                    return !allowed(request.getUrl());
                }

                @Override public WebResourceResponse shouldInterceptRequest(WebView web, WebResourceRequest request) {
                    return allowed(request.getUrl()) ? null : new WebResourceResponse(
                            "text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                }

                @Override public void onPageStarted(WebView web, String address, android.graphics.Bitmap icon) {
                    discardCapture();
                    navigation++;
                }

                @Override public void onPageFinished(WebView web, String address) {
                    if (completed || cancelled || address == null || !allowed(Uri.parse(address))) return;
                    if (TimeUnit.MILLISECONDS.toNanos(settleMillis) >= remaining(deadline)) {
                        finish(null, timeout());
                        return;
                    }
                    discardCapture();
                    int generation = ++navigation;
                    captureTask = () -> {
                        captureTask = null;
                        capture(web, generation);
                    };
                    main.postDelayed(captureTask, settleMillis);
                }

                @Override public void onReceivedError(WebView web, WebResourceRequest request, WebResourceError error) {
                    if (request.isForMainFrame()) {
                        finish(null, new IOException("来源网页加载失败（" + error.getErrorCode() + "）"));
                    }
                }

                @Override public void onReceivedHttpError(WebView web, WebResourceRequest request,
                                                          WebResourceResponse response) {
                    if (request.isForMainFrame()) {
                        finish(null, new ReaderSources.HttpFailure(response.getStatusCode(), null));
                    }
                }

                @Override public boolean onRenderProcessGone(WebView web, RenderProcessGoneDetail detail) {
                    finish(null, new IOException("设备浏览器已停止加载来源网页"));
                    return true;
                }
            });
        }

        private void capture(WebView view, int generation) {
            if (completed || cancelled || generation != navigation) return;
            if (remaining(deadline) == 0) {
                finish(null, timeout());
                return;
            }
            String finalUrl = view.getUrl();
            if (finalUrl == null || !allowed(Uri.parse(finalUrl))) {
                finish(null, new IOException("来源网页返回了无效地址"));
                return;
            }
            try {
                view.evaluateJavascript(extraction(script), value -> {
                    if (completed || cancelled || generation != navigation) return;
                    if (remaining(deadline) == 0) {
                        finish(null, timeout());
                        return;
                    }
                    try {
                        Object decoded = new JSONTokener(value == null ? "null" : value).nextValue();
                        if (!(decoded instanceof JSONObject)) throw new IOException("无法读取来源网页内容");
                        JSONObject response = (JSONObject) decoded;
                        String error = response.optString("error");
                        if (!error.isEmpty()) throw extractionError(error);
                        finish(new ReaderSources.Response(finalUrl, response.getString("body"),
                                response.optString("mime", "text/html")), null);
                    } catch (IOException error) {
                        finish(null, error);
                    } catch (JSONException error) {
                        finish(null, new IOException("无法读取来源网页内容", error));
                    }
                });
            } catch (RuntimeException error) {
                finish(null, new IOException("设备浏览器无法读取来源网页", error));
            }
        }

        private IOException extractionError(String error) {
            switch (error) {
                case "script": return new IOException("来源网页脚本执行失败");
                case "async": return new IOException("来源网页依赖异步返回脚本");
                case "size": return new IOException("来源网页内容超过 8 MB");
                default: return new IOException("来源网页没有可读内容");
            }
        }

        private void finish(ReaderSources.Response response, IOException error) {
            if (completed) return;
            completed = true;
            discardCapture();
            result = response;
            failure = error;
            try {
                if (browser != null) {
                    try {
                        browser.stopLoading();
                        browser.setWebViewClient(new WebViewClient());
                    } finally {
                        browser.destroy();
                    }
                }
            } catch (RuntimeException ignored) {
                // The renderer can already be gone.
            } finally {
                browser = null;
                SLOTS.release();
                done.countDown();
            }
        }

        private void discardCapture() {
            if (captureTask == null) return;
            main.removeCallbacks(captureTask);
            captureTask = null;
        }
    }
}
