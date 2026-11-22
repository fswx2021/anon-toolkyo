package com.shengjian.player;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** MusicFree subscription metadata and the corresponding native source adapters. */
final class MusicSourceCatalog {
    static final String OFFICIAL_REPOSITORY =
            "https://raw.githubusercontent.com/maotoumao/MusicFreePlugins/master/plugins.json";
    private static final String OFFICIAL_GITCODE =
            "https://raw.gitcode.com/maotoumao/MusicFreePlugins/raw/master/plugins.json";
    private static final String OFFICIAL_GITEE =
            "https://gitee.com/maotoumao/MusicFreePlugins/raw/master/plugins.json";
    private static final List<String> OFFICIAL_ADDRESSES = List.of(OFFICIAL_REPOSITORY, OFFICIAL_GITCODE, OFFICIAL_GITEE);
    private static final String OFFICIAL_PROJECT = "/maotoumao/musicfreeplugins/";
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_SOURCES = 256;
    private static final int MAX_REPOSITORIES = 24;
    private static final int MAX_ADDRESS = 2_048;
    private static final long FETCH_TIMEOUT_MILLIS = 9_000;
    private static final Pattern EXPORT = Pattern.compile("\\b(?:module\\s*\\.\\s*exports|exports\\s*\\.\\s*default)\\s*=\\s*\\{");
    private static final Pattern NUMBERED_VERSION = Pattern.compile("[0-9]{1,8}(?:\\.[0-9]{1,8}){0,7}");

    enum Adapter {
        BILIBILI("bilibili", "B 站", "搜索 · 公开音轨", true),
        MAOERFM("maoerfm", "猫耳FM", "搜索 · 受保护的音频需在来源网站播放", true),
        AUDIUS("audius", "Audius", "搜索 · 公开音乐", true),
        ARCHIVE("archive", "Internet Archive", "搜索 · Creative Commons 音频", true),
        NAVIDROME("navidrome", "Navidrome", "搜索 · 串流 · 歌词（由服务器提供）", false),
        UNSUPPORTED("", "", "已收录 · 暂未兼容", false);

        private final String provider, label, capabilities;
        private final boolean enabledByDefault;

        Adapter(String provider, String label, String capabilities, boolean enabledByDefault) {
            this.provider = provider;
            this.label = label;
            this.capabilities = capabilities;
            this.enabledByDefault = enabledByDefault;
        }

        String provider() { return provider; }

        Source source(String url, String version) {
            return new Source(provider, label, url, version, enabledByDefault, this);
        }
    }

    record Source(String id, String name, String url, String version, boolean enabled, Adapter adapter) {
        boolean searchable() { return adapter != Adapter.UNSUPPORTED; }
        boolean builtIn() { return searchable(); }

        String capabilities() {
            if (adapter != Adapter.UNSUPPORTED) return adapter.capabilities;
            String path = url.toLowerCase(Locale.ROOT);
            if (path.endsWith("/dist/geciwang/index.js") || path.endsWith("/dist/geciqianxun/index.js")) return "仅提供歌词 · 无歌曲播放";
            if (path.endsWith("/dist/webdav/index.js")) return "私人曲库 · 此协议暂未兼容";
            if (path.endsWith("/dist/audiomack/index.js")) return "官方接口需应用授权";
            if (path.endsWith("/dist/yinyuetai/index.js")) return "示例搜索接口不可用";
            return adapter.capabilities;
        }

        Source withEnabled(boolean value) { return new Source(id, name, url, version, value, adapter); }
    }

    record Batch(List<Source> sources, int skipped, boolean repository, String address) {
        Batch { sources = List.copyOf(sources); }
    }

    record ImportResult(int added, int updated, int unchanged, int skipped) {}

    private MusicSourceCatalog() {}

    static final class Store {
        private final File file;
        private Map<String, Source> items = builtIns();
        private Set<String> subscriptions = new LinkedHashSet<>();
        private IOException loadFailure;

        Store(File file) {
            this.file = file.getAbsoluteFile();
            if (!this.file.isFile()) return;
            try (InputStream input = new FileInputStream(this.file)) {
                Object content = json(read(input));
                if (!(content instanceof JSONObject stored)) throw new IOException("音乐源清单格式无效");
                JSONArray sources = stored.optJSONArray("sources");
                JSONArray repositories = stored.optJSONArray("repositories");
                if (sources == null || repositories == null || sources.length() > MAX_SOURCES
                        || repositories.length() > MAX_REPOSITORIES) throw new IOException("音乐源清单损坏");

                Map<String, Source> loaded = builtIns();
                Set<String> loadedRepositories = new LinkedHashSet<>();
                for (int index = 0; index < sources.length(); index++) {
                    JSONObject row = sources.optJSONObject(index);
                    if (row == null) throw new IOException("音乐源条目损坏");
                    String id = checkedLabel(string(row, "id"), 100, false);
                    Source source = source(string(row, "name"), string(row, "url"), string(row, "version"));
                    Source builtIn = loaded.get(id);
                    boolean newlySupported = !row.optBoolean("supported", false)
                            && source.adapter() == Adapter.MAOERFM;
                    // Older catalogues saved newly supported sources as disabled; preserve explicit choices from this version.
                    if (source.searchable()) {
                        id = source.id();
                        builtIn = loaded.get(id);
                    }
                    if (builtIn != null && builtIn.builtIn()) {
                        String url = source.adapter() == builtIn.adapter() ? source.url() : "";
                        String version = url.isEmpty() ? "" : source.version();
                        boolean enabled = newlySupported ? builtIn.enabled() : row.optBoolean("enabled", builtIn.enabled());
                        source = new Source(id, builtIn.name(), url, version, enabled,
                                builtIn.adapter());
                    } else {
                        source = new Source(id, source.name(), source.url(), source.version(), false, source.adapter());
                    }
                    loaded.put(id, source);
                }
                for (int index = 0; index < repositories.length(); index++) {
                    String address = importAddress(repositories.optString(index, "")).toASCIIString();
                    if (!isOfficial(address)) loadedRepositories.add(address);
                }
                items = loaded;
                subscriptions = loadedRepositories;
            } catch (IOException failed) {
                loadFailure = failed;
            }
        }

        synchronized List<Source> sources() { return new ArrayList<>(items.values()); }

        synchronized List<String> repositories() { return new ArrayList<>(subscriptions); }

        String loadIssue() { return loadFailure == null ? "" : "音乐源清单读取失败，原文件已保留"; }

        synchronized ImportResult merge(Batch batch) throws IOException {
            requireWritable();
            Map<String, Source> next = new LinkedHashMap<>(items);
            Set<String> nextSubscriptions = new LinkedHashSet<>(subscriptions);
            if (batch.repository() && !batch.address().isEmpty()) register(nextSubscriptions, batch.address());

            int added = 0, updated = 0, unchanged = 0, skipped = batch.skipped();
            for (Source proposed : batch.sources()) {
                Source old = find(next, proposed);
                if (old == null) {
                    if (next.size() >= MAX_SOURCES) skipped++;
                    else {
                        next.put(proposed.id(), proposed);
                        added++;
                    }
                    continue;
                }
                if (old.adapter() != proposed.adapter() || before(proposed.version(), old.version())) {
                    skipped++;
                    continue;
                }
                String name = old.builtIn() ? old.name() : proposed.name();
                String url = proposed.url().isEmpty() ? old.url() : proposed.url();
                String version = proposed.version().isEmpty() ? old.version() : proposed.version();
                Source replacement = new Source(old.id(), name, url, version, old.enabled(), old.adapter());
                if (old.equals(replacement)) unchanged++;
                else {
                    next.put(old.id(), replacement);
                    updated++;
                }
            }
            if (added != 0 || updated != 0 || !nextSubscriptions.equals(subscriptions)) save(next, nextSubscriptions);
            return new ImportResult(added, updated, unchanged, skipped);
        }

        synchronized void setEnabled(String id, boolean enabled) throws IOException {
            requireWritable();
            Source source = items.get(id);
            if (source == null || source.enabled() == enabled) return;
            if (enabled && !source.searchable()) throw new IOException("这个插件暂未适配");
            Map<String, Source> next = new LinkedHashMap<>(items);
            next.put(id, source.withEnabled(enabled));
            save(next, subscriptions);
        }

        synchronized void remove(String id) throws IOException {
            requireWritable();
            Source source = items.get(id);
            if (source == null || source.builtIn()) return;
            Map<String, Source> next = new LinkedHashMap<>(items);
            next.remove(id);
            save(next, subscriptions);
        }

        synchronized void removeRepository(String address) throws IOException {
            requireWritable();
            Set<String> next = new LinkedHashSet<>(subscriptions);
            if (next.remove(importAddress(address).toASCIIString())) save(items, next);
        }

        private void requireWritable() throws IOException {
            if (loadFailure != null) throw new IOException(loadIssue(), loadFailure);
        }

        private void save(Map<String, Source> next, Set<String> nextSubscriptions) throws IOException {
            byte[] bytes;
            try {
                JSONArray sources = new JSONArray();
                for (Source source : next.values()) {
                    sources.put(new JSONObject().put("id", source.id()).put("name", source.name())
                            .put("url", source.url()).put("version", source.version()).put("enabled", source.enabled())
                            .put("supported", source.searchable()));
                }
                bytes = new JSONObject().put("sources", sources).put("repositories", new JSONArray(nextSubscriptions))
                        .toString().getBytes(StandardCharsets.UTF_8);
            } catch (JSONException invalid) { throw new IOException("无法保存音乐源清单", invalid); }
            if (bytes.length > MAX_BYTES) throw new IOException("音乐源清单超出容量");
            File parent = file.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException("无法创建音乐源存储目录");
            }
            File temporary = File.createTempFile("music-sources-", ".tmp", parent);
            try {
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    output.write(bytes);
                    output.getFD().sync();
                }
                try {
                    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                items = next;
                subscriptions = nextSubscriptions;
            } finally { Files.deleteIfExists(temporary.toPath()); }
        }
    }

    static Batch official() throws IOException {
        IOException failure = null;
        for (String address : OFFICIAL_ADDRESSES) {
            try {
                Batch batch = fetch(address);
                if (!batch.repository()) throw new IOException("MusicFree 官方地址没有返回订阅清单");
                return new Batch(batch.sources(), batch.skipped(), true, OFFICIAL_REPOSITORY);
            } catch (IOException failed) {
                if (Thread.currentThread().isInterrupted()) throw failed;
                if (failure == null) failure = failed;
                else failure.addSuppressed(failed);
            }
        }
        throw new IOException("无法更新 MusicFree 官方订阅，请检查网络后重试", failure);
    }

    static Batch fetch(String address) throws IOException {
        if (ReaderSearchQueue.RequestBudget.current() != null) return fetchOnce(address);
        CompletableFuture<Batch> result = new CompletableFuture<>();
        try (ReaderSearchQueue worker = new ReaderSearchQueue(1, FETCH_TIMEOUT_MILLIS)) {
            worker.start(List.of(address == null ? "" : address), MusicSourceCatalog::fetchOnce, (link, batch, error) -> {
                if (error == null) result.complete(batch);
                else result.completeExceptionally(error);
            });
            return result.get(FETCH_TIMEOUT_MILLIS + 1_000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("已取消音乐源导入");
        } catch (TimeoutException expired) {
            throw new SocketTimeoutException("读取音乐源超时");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof IOException cause) throw cause;
            throw new IOException("无法读取音乐源", failed.getCause());
        }
    }

    private static Batch fetchOnce(String address) throws IOException {
        URI requested = importAddress(address);
        ReaderSources.Response response = ReaderSources.fetch(requested.toASCIIString(), Collections.emptyMap());
        Batch parsed = parse(response.body, response.url);
        return new Batch(parsed.sources(), parsed.skipped(), parsed.repository(), requested.toASCIIString());
    }

    static Batch parse(InputStream input, String address) throws IOException { return parse(read(input), address); }

    static Batch parse(String input, String address) throws IOException {
        if (input == null || input.length() > MAX_BYTES) throw new IOException("音乐源文件超过 2 MB");
        String value = (input.startsWith("\ufeff") ? input.substring(1) : input).trim();
        URI base = address == null || address.trim().isEmpty() ? null : importAddress(address);
        String origin = base == null ? "" : base.toASCIIString();
        if (value.startsWith("{") || value.startsWith("[")) {
            Object document = json(value);
            JSONArray rows;
            if (document instanceof JSONArray array) rows = array;
            else if (document instanceof JSONObject object) {
                if (!object.has("plugins")) {
                    return new Batch(List.of(entry(object, base)), 0, false, origin);
                }
                rows = object.optJSONArray("plugins");
            } else rows = null;
            if (rows == null) throw new IOException("MusicFree 订阅须包含 plugins 数组");
            Map<String, Source> sources = new LinkedHashMap<>();
            int skipped = Math.max(0, rows.length() - MAX_SOURCES);
            for (int index = 0; index < Math.min(rows.length(), MAX_SOURCES); index++) {
                try {
                    JSONObject row = rows.optJSONObject(index);
                    if (row == null) throw new IOException("无效的插件条目");
                    Source candidate = entry(row, base);
                    Source duplicate = find(sources, candidate);
                    if (duplicate == null) sources.put(candidate.id(), candidate);
                    else if (duplicate.adapter() == Adapter.UNSUPPORTED && candidate.builtIn()
                            || duplicate.adapter() == candidate.adapter() && before(duplicate.version(), candidate.version())) {
                        sources.remove(duplicate.id());
                        sources.put(candidate.id(), candidate);
                        skipped++;
                    } else skipped++;
                } catch (IOException invalid) { skipped++; }
            }
            if (sources.isEmpty()) throw new IOException("MusicFree 清单中没有有效的音乐源");
            return new Batch(new ArrayList<>(sources.values()), skipped, true, origin);
        }
        return new Batch(List.of(script(value, origin)), 0, false, origin);
    }

    private static Source entry(JSONObject row, URI base) throws IOException {
        String name = string(row, "name");
        if (name.isEmpty()) name = string(row, "platform");
        String url = string(row, "url");
        if (url.isEmpty()) url = string(row, "srcUrl");
        if (url.isEmpty()) throw new IOException("插件缺少下载地址");
        try { if (base != null) url = base.resolve(url).toASCIIString(); }
        catch (IllegalArgumentException invalid) { throw new IOException("插件下载地址无效", invalid); }
        return source(name, importAddress(url).toASCIIString(), string(row, "version"));
    }

    private static Source script(String body, String address) throws IOException {
        Matcher export = EXPORT.matcher(body);
        if (!export.find()) throw new IOException("请选择 MusicFree 的订阅 JSON 或导出插件对象的 JS 文件");
        String fields = body.substring(export.end(), Math.min(body.length(), export.end() + 24_576));
        String name = scriptField(fields, "platform");
        String declared = scriptField(fields, "srcUrl");
        // The source URL is only metadata. The search engine uses its own built-in adapter.
        if (!address.isEmpty()) declared = address;
        else if (!declared.isEmpty()) {
            try { declared = importAddress(declared).toASCIIString(); }
            catch (IOException invalid) { declared = ""; }
        }
        return source(name, declared, scriptField(fields, "version"));
    }

    private static String scriptField(String body, String key) {
        Pattern pattern = Pattern.compile("(?:^|[\\s,{])(?:['\"]?" + key + "['\"]?)\\s*:\\s*(['\"])(.{1,2048}?)\\1");
        Matcher match = pattern.matcher(body);
        return match.find() ? match.group(2).trim() : "";
    }

    private static Source source(String name, String url, String version) throws IOException {
        name = checkedLabel(name, 96, false);
        version = checkedLabel(version, 64, true);
        Adapter adapter = adapter(url);
        return adapter != Adapter.UNSUPPORTED ? adapter.source(url, version)
                : new Source(nameKey(name), name, url, version, false, adapter);
    }

    private static Map<String, Source> builtIns() {
        Map<String, Source> result = new LinkedHashMap<>();
        for (Adapter adapter : Adapter.values()) {
            if (adapter != Adapter.UNSUPPORTED) result.put(adapter.provider(), adapter.source("", ""));
        }
        return result;
    }

    private static Adapter adapter(String address) {
        if (address.isEmpty()) return Adapter.UNSUPPORTED;
        try {
            URI url = importAddress(address);
            String path = url.getPath().toLowerCase(Locale.ROOT);
            String host = url.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equalsIgnoreCase(url.getScheme()) || url.getPort() != -1 || !path.startsWith(OFFICIAL_PROJECT)
                    || !Set.of("raw.githubusercontent.com", "gitee.com", "raw.gitcode.com").contains(host)) {
                return Adapter.UNSUPPORTED;
            }
            if (path.endsWith("/dist/bilibili/index.js")) return Adapter.BILIBILI;
            if (path.endsWith("/dist/navidrome/index.js")) return Adapter.NAVIDROME;
            if (path.endsWith("/dist/maoerfm/index.js")) return Adapter.MAOERFM;
        } catch (IOException invalid) { /* An unverified address cannot select a native adapter. */ }
        return Adapter.UNSUPPORTED;
    }

    private static Source find(Map<String, Source> items, Source target) {
        Source exact = items.get(target.id());
        if (exact != null) return exact;
        for (Source source : items.values()) {
            if (nameKey(source.name()).equals(nameKey(target.name()))
                    || !target.url().isEmpty() && source.url().equals(target.url())) return source;
        }
        return null;
    }

    private static String nameKey(String name) {
        return Normalizer.normalize(name.trim(), Normalizer.Form.NFKC).replaceAll("[\\p{Z}\\s]+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private static boolean before(String candidate, String current) {
        if (!NUMBERED_VERSION.matcher(candidate).matches() || !NUMBERED_VERSION.matcher(current).matches()) return false;
        String[] first = candidate.split("\\.");
        String[] second = current.split("\\.");
        for (int i = 0; i < Math.max(first.length, second.length); i++) {
            int left = i < first.length ? Integer.parseInt(first[i]) : 0;
            int right = i < second.length ? Integer.parseInt(second[i]) : 0;
            if (left != right) return left < right;
        }
        return false;
    }

    private static void register(Set<String> subscriptions, String address) throws IOException {
        String value = importAddress(address).toASCIIString();
        if (isOfficial(value) || subscriptions.contains(value)) return;
        if (subscriptions.size() >= MAX_REPOSITORIES) throw new IOException("音乐订阅达到 " + MAX_REPOSITORIES + " 个");
        subscriptions.add(value);
    }

    private static boolean isOfficial(String address) {
        for (String value : OFFICIAL_ADDRESSES) if (value.equalsIgnoreCase(address)) return true;
        return false;
    }

    private static URI importAddress(String value) throws IOException {
        try {
            if (value == null || value.length() > MAX_ADDRESS) throw new IllegalArgumentException();
            String raw = value.trim().split("#", 2)[0];
            URI uri = URI.create(raw).normalize();
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || host == null
                    || uri.getRawUserInfo() != null || uri.getPort() == 0 || uri.getPort() > 65_535) {
                throw new IllegalArgumentException();
            }
            if (host.equalsIgnoreCase("github.com") && "https".equalsIgnoreCase(scheme) && uri.getPort() == -1) {
                String path = uri.getRawPath();
                if (path.equalsIgnoreCase(OFFICIAL_PROJECT) || (path + '/').equalsIgnoreCase(OFFICIAL_PROJECT)) {
                    return URI.create(OFFICIAL_REPOSITORY);
                }
                String[] parts = path.split("/", 5);
                if (parts.length == 5 && (parts[3].equals("blob") || parts[3].equals("raw"))) {
                    return URI.create("https://raw.githubusercontent.com/" + parts[1] + '/' + parts[2] + '/' + parts[4]
                            + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
                }
            }
            return uri;
        } catch (IllegalArgumentException invalid) {
            throw new IOException("请使用不含账号或密码的 HTTP 或 HTTPS 音源链接", invalid);
        }
    }

    private static String checkedLabel(String value, int limit, boolean allowEmpty) throws IOException {
        if (value.length() > limit || !allowEmpty && value.isEmpty()
                || value.chars().anyMatch(Character::isISOControl)) throw new IOException("音乐源名称或版本格式无效");
        return value;
    }

    private static String string(JSONObject object, String field) {
        Object value = object.opt(field);
        return value instanceof String || value instanceof Number ? value.toString().trim() : "";
    }

    private static Object json(String value) throws IOException {
        try {
            JSONTokener tokenizer = new JSONTokener(value);
            Object result = tokenizer.nextValue();
            if (tokenizer.nextClean() != 0) throw new IOException("音乐源 JSON 后有多余内容");
            return result;
        } catch (JSONException | StackOverflowError invalid) { throw new IOException("音乐源 JSON 格式无效", invalid); }
    }

    private static String read(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消音乐源导入");
            if (count > MAX_BYTES - out.size()) throw new IOException("音乐源文件超过 2 MB");
            out.write(buffer, 0, count);
        }
        try { return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(out.toByteArray())).toString(); }
        catch (CharacterCodingException invalid) { throw new IOException("请选择 UTF-8 编码的 MusicFree 文件", invalid); }
    }
}
