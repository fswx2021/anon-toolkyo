package com.shengjian.player;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Public Bangumi routing with the same ECH bootstrap and CDN route as Kazumi 2.4.4. */
final class KazumiBangumiTransport {
    private static final Set<String> ECH_HOSTS = Set.of("api.bgm.tv", "next.bgm.tv", "lain.bgm.tv");
    private static final List<String> CDN_IPS = List.of("172.67.134.140", "104.21.6.61", "172.67.73.67");
    private static final String CONFIG_URL = "https://dns.alidns.com/resolve?name=crypto.cloudflare.com&type=65";
    private static final Pattern CONFIG_FIELD = Pattern.compile("(?:^|\\s)ech=\"?([A-Za-z0-9+/=]{16,8192})\"?(?:$|\\s)");
    private static final int PAGE_BYTES = 8 * 1024 * 1024;
    private static final AtomicInteger NEXT_IP = new AtomicInteger();
    private static String cachedConfig = "";
    private static long configExpiry;

    private record Response(URI url, String mime, byte[] bytes) {}

    private KazumiBangumiTransport() {}

    static ReaderSources.Response fetch(String url, Map<String, String> headers, String postBody, int timeoutMillis)
            throws IOException {
        if (eligible(url)) {
            try {
                Response response = encrypted(URI.create(url), headers, postBody, timeoutMillis, PAGE_BYTES);
                return new ReaderSources.Response(response.url.toString(),
                        new String(response.bytes, StandardCharsets.UTF_8), response.mime);
            } catch (IOException problem) {
                allowDirectFallback(problem);
                try {
                    return ReaderSources.fetchAnime(url, headers, postBody, timeoutMillis);
                } catch (IOException direct) {
                    direct.addSuppressed(problem);
                    throw direct;
                }
            }
        }
        return ReaderSources.fetchAnime(url, headers, postBody, timeoutMillis);
    }

    static byte[] fetchImage(String url, Map<String, String> headers, int timeoutMillis, int maxBytes) throws IOException {
        if (eligible(url)) {
            try {
                return encrypted(URI.create(url), headers, null, timeoutMillis, maxBytes).bytes;
            } catch (IOException problem) {
                allowDirectFallback(problem);
                try {
                    return ReaderSources.fetchImage(url, headers, timeoutMillis, maxBytes);
                } catch (IOException direct) {
                    direct.addSuppressed(problem);
                    throw direct;
                }
            }
        }
        return ReaderSources.fetchImage(url, headers, timeoutMillis, maxBytes);
    }

    private static boolean eligible(String url) {
        try {
            return allowed(URI.create(url)) && KazumiEchClient.available();
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static boolean allowed(URI url) {
        return "https".equals(url.getScheme()) && url.getHost() != null && ECH_HOSTS.contains(url.getHost())
                && url.getRawUserInfo() == null && (url.getPort() == -1 || url.getPort() == 443);
    }

    private static Response encrypted(URI url, Map<String, String> headers, String postBody,
                                      int timeoutMillis, int maxBytes) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(7_000, timeoutMillis));
        String config = configuration(Math.min(3_500, remaining(deadline)));
        String headerText = headerText(headers);
        int start = Math.floorMod(NEXT_IP.getAndIncrement(), CDN_IPS.size());
        for (int redirects = 0; redirects <= 2; redirects++) {
            KazumiEchClient.Result raw = tryAddresses(url, headerText, postBody, config, start, deadline, maxBytes);
            int code = raw.status();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = header(raw.headers(), "Location");
                if (location.isEmpty() || postBody != null) throw new IOException("Bangumi 响应重定向不可用");
                try {
                    url = url.resolve(location);
                } catch (IllegalArgumentException malformed) {
                    throw new IOException("Bangumi 响应重定向无效", malformed);
                }
                if (!allowed(url)) throw new IOException("Bangumi 返回了异站跳转");
            } else if (code < 200 || code >= 300) {
                throw new ReaderSources.HttpFailure(code, header(raw.headers(), "Retry-After"));
            } else {
                return new Response(url, header(raw.headers(), "Content-Type"), raw.body());
            }
        }
        throw new IOException("Bangumi 响应重定向次数过多");
    }

    private static KazumiEchClient.Result tryAddresses(URI url, String headers, String postBody, String config,
                                                       int first, long deadline, int maxBytes) throws IOException {
        IOException failure = null;
        for (int i = 0; i < CDN_IPS.size(); i++) {
            int timeout = remaining(deadline);
            if (failure != null && timeout < 500) throw failure;
            String ip = CDN_IPS.get((first + i) % CDN_IPS.size());
            try {
                return KazumiEchClient.fetch(url.toASCIIString(), headers, postBody, config, ip, timeout, maxBytes);
            } catch (IOException unreachable) {
                checkCancellation();
                failure = unreachable;
            }
        }
        throw failure == null ? new IOException("Bangumi CDN 不可用") : failure;
    }

    private static synchronized String configuration(int timeoutMillis) throws IOException {
        checkCancellation();
        long now = System.nanoTime();
        if (configExpiry != 0 && configExpiry - now > 0) {
            if (cachedConfig.isEmpty()) throw new IOException("ECH 配置暂不可用");
            return cachedConfig;
        }
        try {
            ReaderSources.Response dns = ReaderSources.fetchAnime(CONFIG_URL,
                    Map.of("Accept", "application/dns-json"), null, timeoutMillis);
            cachedConfig = parseConfig(dns.body);
            configExpiry = now + TimeUnit.MINUTES.toNanos(2);
            return cachedConfig;
        } catch (IOException unavailable) {
            checkCancellation();
            cachedConfig = "";
            configExpiry = now + TimeUnit.SECONDS.toNanos(15);
            throw unavailable;
        }
    }

    static String parseConfig(String body) throws IOException {
        try {
            JSONObject result = new JSONObject(body);
            JSONArray answers = result.optJSONArray("Answer");
            if (result.optInt("Status", -1) == 0 && answers != null) {
                for (int i = 0; i < answers.length(); i++) {
                    JSONObject row = answers.optJSONObject(i);
                    if (row == null || row.optInt("type") != 65) continue;
                    Matcher key = CONFIG_FIELD.matcher(row.optString("data"));
                    if (key.find()) return key.group(1);
                }
            }
        } catch (JSONException malformed) {
            throw new IOException("ECH 配置格式无效", malformed);
        }
        throw new IOException("Cloudflare ECH 配置暂不可用");
    }

    private static String headerText(Map<String, String> headers) {
        StringBuilder result = new StringBuilder();
        headers.forEach((name, value) -> result.append(name).append(": ").append(value).append("\r\n"));
        if (headers.keySet().stream().noneMatch("Accept-Encoding"::equalsIgnoreCase)) {
            result.append("Accept-Encoding: gzip\r\n");
        }
        return result.toString();
    }

    private static String header(String headers, String key) {
        for (String line : headers.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon == key.length() && line.regionMatches(true, 0, key, 0, colon)) {
                return line.substring(colon + 1).trim();
            }
        }
        return "";
    }

    private static int remaining(long deadline) throws IOException {
        checkCancellation();
        long limit = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        ReaderSearchQueue.RequestBudget budget = ReaderSearchQueue.RequestBudget.current();
        if (budget != null) limit = Math.min(limit, budget.remainingMillis());
        if (limit <= 0) throw new java.net.SocketTimeoutException("Bangumi ECH 连接超时");
        return (int) limit;
    }

    private static void allowDirectFallback(IOException problem) throws IOException {
        checkCancellation();
        if (problem instanceof ReaderSources.HttpFailure) throw problem;
    }

    private static void checkCancellation() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Bangumi 请求已取消");
        ReaderSearchQueue.RequestBudget budget = ReaderSearchQueue.RequestBudget.current();
        if (budget != null) budget.check();
    }
}
