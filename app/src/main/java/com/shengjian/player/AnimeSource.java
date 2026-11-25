package com.shengjian.player;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** A user-provided Kazumi-compatible animation source. */
final class AnimeSource {
    static final String OFFICIAL_REPOSITORY = "https://raw.githubusercontent.com/Predidit/KazumiRules/main/index.json";
    private static final String OFFICIAL_ARCHIVE = "https://codeload.github.com/Predidit/KazumiRules/zip/refs/heads/main";
    private static final String OFFICIAL_MIRROR = "https://raw.gitcode.com/gh_mirrors/ka/KazumiRules/raw/main/index.json";
    private static final int MAX_SOURCE_BYTES = 128 * 1024;
    private static final int MAX_IMPORT_BYTES = 4 * 1024 * 1024;
    private static final int MAX_CATALOG_BYTES = 8 * 1024 * 1024;
    private static final int MAX_SOURCES = 256;
    private static final int MAX_REPOSITORY_RULES = 64;
    private static final int REPOSITORY_WORKERS = 4;
    private static final int MAX_ARCHIVE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_ARCHIVE_EXPANDED = 8 * 1024 * 1024;
    private static final long PER_RULE_TIMEOUT_MILLIS = 12_000;
    private static final long REPOSITORY_TIMEOUT_SECONDS = 60;
    private static final Pattern SHARE_LINK = Pattern.compile("(?i)kazumi:(?://)?");
    private static final Pattern REPOSITORY_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}");
    private static final Pattern NUMERIC_VERSION = Pattern.compile("[0-9]{1,8}(?:\\.[0-9]{1,8}){0,7}");

    final String id, name, version, baseUrl, userAgent, referer;
    final String searchUrl, searchList, searchName, searchResult, chapterRoads, chapterResult;
    final boolean enabled, useWebview, useNativePlayer, useLegacyParser, usePost, antiCrawler;
    final boolean apiSearch, apiChapter;
    final JSONObject searchApi, chapterApi;
    private final JSONObject definition;
    private final String repository;
    private final boolean startupEnabled;

    private AnimeSource(JSONObject rule, boolean enabled) throws IOException {
        this(rule, enabled, "");
    }

    private AnimeSource(JSONObject rule, boolean enabled, String repository) throws IOException {
        this(rule, enabled, repository, false);
    }

    private AnimeSource(JSONObject rule, boolean enabled, String repository, boolean startupEnabled) throws IOException {
        this.enabled = enabled;
        definition = rule;
        this.repository = repository;
        this.startupEnabled = startupEnabled;
        name = field(rule, "name");
        if (name.isEmpty() || name.length() > 80 || name.chars().anyMatch(Character::isISOControl)) {
            throw new IOException("规则名称须为 1 到 80 个字符");
        }
        id = Normalizer.normalize(name, Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT);
        version = field(rule, "version");
        baseUrl = web(field(rule, "baseURL")).toASCIIString();
        String kind = field(rule, "type");
        if (!kind.isEmpty() && !kind.equalsIgnoreCase("anime")) throw new IOException("此源不属于动画");
        String api = field(rule, "api");
        if (!api.isEmpty()) {
            try {
                if (Integer.parseInt(api) > 9) throw new IOException("此源需要更新版本的应用");
            } catch (NumberFormatException invalid) { throw new IOException("此源的兼容性版本无效"); }
        }
        useWebview = rule.optBoolean("useWebview", true);
        useNativePlayer = rule.optBoolean("useNativePlayer", true);
        useLegacyParser = rule.optBoolean("useLegacyParser");
        usePost = rule.optBoolean("usePost");
        antiCrawler = object(rule, "antiCrawlerConfig").optBoolean("enabled");
        userAgent = headerValue(field(rule, "userAgent"));
        String configuredRef = field(rule, "referer");
        referer = configuredRef.isEmpty() ? "" : web(configuredRef).toASCIIString();
        apiSearch = "api".equalsIgnoreCase(field(rule, "searchMode"));
        apiChapter = "api".equalsIgnoreCase(field(rule, "chapterMode"));
        searchApi = object(rule, "searchApiConfig");
        chapterApi = object(rule, "chapterApiConfig");
        searchUrl = field(rule, "searchURL");
        searchList = field(rule, "searchList");
        searchName = field(rule, "searchName");
        searchResult = field(rule, "searchResult");
        chapterRoads = field(rule, "chapterRoads");
        chapterResult = field(rule, "chapterResult");
        if (apiSearch ? field(object(searchApi, "request"), "url").isEmpty()
                : searchUrl.isEmpty() || searchList.isEmpty() || searchName.isEmpty() || searchResult.isEmpty()) {
            throw new IOException("规则缺少搜索地址或结果选择器");
        }
        if (apiChapter ? field(object(chapterApi, "request"), "url").isEmpty() : chapterResult.isEmpty()) {
            throw new IOException("规则缺少选集地址或剧集选择器");
        }
    }

    static String field(JSONObject object, String key) { return field(object, key, ""); }

    static String field(JSONObject object, String key, String fallback) {
        Object value = object.opt(key);
        return value instanceof String || value instanceof Number ? value.toString().trim() : fallback;
    }

    static JSONObject object(JSONObject object, String key) {
        JSONObject result = object.optJSONObject(key);
        return result == null ? new JSONObject() : result;
    }

    static URI web(String address) throws IOException {
        try {
            URI url = new URI(address.replace(" ", "%20"));
            String scheme = url.getScheme();
            if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && url.getHost() != null && url.getRawUserInfo() == null
                    && url.getPort() <= 65535 && address.length() <= 16_384) return url;
        } catch (java.net.URISyntaxException ignored) { }
        throw new IOException("动画源地址须为不含账号或密码的 HTTP 或 HTTPS 链接");
    }

    static String headerValue(String value) throws IOException {
        if (value.length() > 4096 || value.chars().anyMatch(ch -> ch < 32 || ch == 127)) {
            throw new IOException("动画源请求头格式无效");
        }
        return value;
    }

    static final class ImportResult {
        final int added, updated, skipped;
        final List<String> diagnostics;

        ImportResult(int added, int updated, int skipped, List<String> diagnostics) {
            this.added = added;
            this.updated = updated;
            this.skipped = skipped;
            this.diagnostics = Collections.unmodifiableList(new ArrayList<>(diagnostics));
        }
    }

    static final class AggregationResult {
        final int listed, fetched, failed, skippedEntries;
        final ImportResult imported;
        final List<String> diagnostics;

        AggregationResult(RepositoryBatch batch, ImportResult imported) {
            this.listed = batch.listed;
            this.fetched = batch.rules.size();
            this.failed = batch.failed;
            this.skippedEntries = batch.skipped;
            this.imported = imported;
            List<String> details = new ArrayList<>(batch.diagnostics);
            details.addAll(imported.diagnostics);
            diagnostics = Collections.unmodifiableList(details);
        }
    }

    static final class Store {
        private final File file;
        private Map<String, AnimeSource> items = Collections.emptyMap();
        private IOException loadFailure;

        Store(File file) {
            this.file = file.getAbsoluteFile();
            if (!this.file.isFile()) return;
            try (FileInputStream input = new FileInputStream(this.file)) {
                Object stored = parse(decode(read(input, MAX_CATALOG_BYTES)));
                if (!(stored instanceof JSONArray)) throw new IOException("动画源清单格式错误");
                JSONArray rows = (JSONArray) stored;
                if (rows.length() > MAX_SOURCES) throw new IOException("动画源清单超出容量");
                Map<String, AnimeSource> loaded = new LinkedHashMap<>();
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row == null || row.optJSONObject("rule") == null) throw new IOException("动画源清单损坏");
                    AnimeSource item = new AnimeSource(row.optJSONObject("rule"), row.optBoolean("enabled", true),
                            field(row, "repository"), row.optBoolean("startupEnabled"));
                    loaded.put(item.id, item);
                }
                items = loaded;
            } catch (IOException failed) {
                loadFailure = failed;
            }
        }

        synchronized List<AnimeSource> list() { return new ArrayList<>(items.values()); }

        synchronized boolean awaitingInitialImport() {
            return !new File(file.getParentFile(), "anon-initial-anime-rules.json").isFile()
                    && items.values().stream().anyMatch(source -> source.enabled && source.startupEnabled);
        }

        synchronized byte[] exportRules() throws IOException {
            if (loadFailure != null) throw new IOException("动画源清单读取失败", loadFailure);
            JSONArray rules = new JSONArray();
            try {
                for (AnimeSource source : items.values()) {
                    JSONObject rule = new JSONObject(source.definition.toString());
                    rules.put(rule.put("enabled", source.enabled));
                }
            } catch (JSONException invalid) {
                throw new IOException("无法导出动画源", invalid);
            }
            return rules.toString().getBytes(StandardCharsets.UTF_8);
        }

        synchronized AnimeSource find(String id) { return items.get(id); }

        String loadIssue() { return loadFailure == null ? "" : "动画源清单读取失败"; }

        ImportResult importStream(InputStream input) throws IOException {
            return importText(decode(read(input, MAX_IMPORT_BYTES)));
        }

        ImportResult importText(String input) throws IOException {
            if (input == null || input.length() > MAX_IMPORT_BYTES) throw new IOException("动画源文件超过大小限制");
            ImportBatch batch = new ImportBatch();
            String value = stripBom(input).trim();
            if (value.startsWith("{") || value.startsWith("[")) {
                addJson(batch, parse(value));
            } else {
                Matcher links = SHARE_LINK.matcher(value);
                int start = -1;
                while (links.find()) {
                    if (start >= 0) addShare(batch, value.substring(start, links.start()));
                    start = links.end();
                }
                if (start < 0) throw new IOException("请选择源的 JSON 文件或粘贴源的分享链接");
                addShare(batch, value.substring(start));
            }
            return apply(batch);
        }

        ImportResult importUrl(String address) throws IOException {
            String value = address == null ? "" : address.trim();
            if (SHARE_LINK.matcher(value).lookingAt()) return importText(value);
            URI url = rawAddress(web(value));
            ReaderSources.Response result = ReaderSources.fetch(url.toASCIIString(), Collections.emptyMap());
            Object data = parse(result.body);
            if (isRepositoryIndex(web(result.url), data)) {
                try (ReaderSearchQueue queue = new ReaderSearchQueue(REPOSITORY_WORKERS, PER_RULE_TIMEOUT_MILLIS)) {
                    RepositoryBatch repository = readRepository(queue, web(result.url), (JSONArray) data, ignored -> {});
                    ImportBatch batch = repository.toImportBatch();
                    batch.skipped = repository.failed + repository.skipped;
                    batch.diagnostics.addAll(repository.diagnostics);
                    return apply(batch);
                }
            }
            ImportBatch batch = new ImportBatch();
            addJson(batch, data);
            return apply(batch);
        }

        AggregationResult aggregateOfficial(Consumer<String> progress) throws IOException {
            return aggregateKnownRepository(web(OFFICIAL_REPOSITORY), web(OFFICIAL_MIRROR),
                    web(OFFICIAL_ARCHIVE), "kazumi-official", progress);
        }

        AggregationResult aggregateAndEnableOfficial(Consumer<String> progress) throws IOException {
            return aggregateKnownRepository(web(OFFICIAL_REPOSITORY), web(OFFICIAL_MIRROR),
                    web(OFFICIAL_ARCHIVE), "kazumi-official", progress, true);
        }

        AggregationResult aggregateRepository(String address, Consumer<String> progress) throws IOException {
            URI index = repositoryIndex(address);
            if (OFFICIAL_REPOSITORY.equals(index.toASCIIString())) return aggregateOfficial(progress);
            if (OFFICIAL_MIRROR.equals(index.toASCIIString())) {
                return aggregateKnownRepository(index, null, null, "kazumi-official", progress);
            }
            return aggregateKnownRepository(index, null, null, repositoryKey(index), progress);
        }

        AggregationResult aggregateKnownRepository(URI primary, URI mirror, URI archive, String identity,
                                                   Consumer<String> progress) throws IOException {
            return aggregateKnownRepository(primary, mirror, archive, identity, progress, false);
        }

        private AggregationResult aggregateKnownRepository(URI primary, URI mirror, URI archive, String identity,
                                                           Consumer<String> progress, boolean enableForStartup)
                throws IOException {
            Consumer<String> updates = progress == null ? ignored -> {} : progress;
            boolean pinned = "kazumi-official".equals(identity);
            try (ReaderSearchQueue queue = new ReaderSearchQueue(REPOSITORY_WORKERS, PER_RULE_TIMEOUT_MILLIS)) {
                ArchiveRules bundled = null;
                String archiveIssue = "";
                if (archive != null) {
                    updates.accept("正在读取源合集…");
                    try { bundled = boundedRequest(queue, archive, AnimeSource::readArchive, "源合集"); }
                    catch (IOException unavailable) {
                        checkImportCancellation();
                        archiveIssue = "源合集暂未读到，正在读取源目录";
                    }
                }

                URI resolved = primary;
                JSONArray metadata;
                boolean mirrored = false;
                if (bundled != null) metadata = bundled.metadata;
                else {
                    updates.accept("正在读取源目录…");
                    IndexDocument index;
                    try { index = indexDocument(queue, primary, pinned, mirror != null); }
                    catch (IOException unavailable) {
                        checkImportCancellation();
                        if (mirror == null) throw unavailable;
                        updates.accept("主站目录暂未读到，正在尝试源镜像…");
                        index = indexDocument(queue, mirror, pinned, true);
                        mirrored = true;
                    }
                    metadata = index.metadata;
                    resolved = index.url;
                }
                RepositoryBatch downloaded = readRepository(queue, resolved, metadata, updates,
                        bundled, mirrored ? null : mirror, pinned);
                if (bundled != null) downloaded.note("已从源合集读取目录");
                if (!archiveIssue.isEmpty()) downloaded.note(archiveIssue);
                if (mirrored) downloaded.note("已从镜像读取源目录");
                checkImportCancellation();
                updates.accept("正在合并已读取的动画源…");
                ImportResult merged = apply(downloaded.toImportBatch(), identity, true, enableForStartup);
                return new AggregationResult(downloaded, merged);
            }
        }

        synchronized void setEnabled(String id, boolean enabled) throws IOException {
            requireWritable();
            AnimeSource item = items.get(id);
            if (item == null || item.enabled == enabled) return;
            Map<String, AnimeSource> next = new LinkedHashMap<>(items);
            next.put(id, new AnimeSource(item.definition, enabled, item.repository, item.startupEnabled));
            save(next);
        }

        synchronized void remove(String id) throws IOException {
            requireWritable();
            if (!items.containsKey(id)) return;
            Map<String, AnimeSource> next = new LinkedHashMap<>(items);
            next.remove(id);
            save(next);
        }

        private synchronized ImportResult apply(ImportBatch batch) throws IOException {
            return apply(batch, "", false, false);
        }

        private synchronized ImportResult apply(ImportBatch batch, String repository, boolean allowEmpty,
                                                boolean enableForStartup) throws IOException {
            requireWritable();
            if (!allowEmpty && batch.valid.isEmpty()) throw new IOException(batch.diagnostics.isEmpty() ? "没有找到可导入的动画源"
                    : "没有找到可导入的动画源。" + batch.diagnostics.get(0));
            Map<String, AnimeSource> next = new LinkedHashMap<>(items);
            int added = 0, updated = 0;
            for (AnimeSource proposed : batch.valid.values()) {
                AnimeSource old = next.get(proposed.id);
                if (old == null && next.size() >= MAX_SOURCES) {
                    batch.reject(1, "动画源数量已达到 " + MAX_SOURCES + " 条");
                } else if (old == null) {
                    next.put(proposed.id, new AnimeSource(proposed.definition, proposed.enabled, repository));
                    added++;
                } else if (!repository.isEmpty() && !repository.equals(old.repository)) {
                    batch.reject(1, proposed.name + " · 保留已有同名来源");
                } else if (!repository.isEmpty() && versionBefore(proposed.version, old.version)) {
                    batch.reject(1, proposed.name + " · 保留已安装的较新版本");
                } else if (old.repository.equals(repository) && old.definition.toString().equals(proposed.definition.toString())) {
                    batch.skipped++;
                } else {
                    next.put(proposed.id, new AnimeSource(proposed.definition, old.enabled, repository, old.startupEnabled));
                    updated++;
                }
            }
            boolean startupChanged = false;
            if (enableForStartup) {
                for (Map.Entry<String, AnimeSource> entry : next.entrySet()) {
                    AnimeSource source = entry.getValue();
                    if (!source.enabled || !source.startupEnabled) {
                        entry.setValue(new AnimeSource(source.definition, true, source.repository, true));
                        startupChanged = true;
                    }
                }
            }
            if (added != 0 || updated != 0 || startupChanged) save(next);
            return new ImportResult(added, updated, batch.skipped, batch.diagnostics);
        }

        private void requireWritable() throws IOException {
            if (loadFailure != null) throw new IOException("现有动画源清单读取失败，保留了原文件", loadFailure);
        }

        private void save(Map<String, AnimeSource> next) throws IOException {
            byte[] bytes;
            try {
                JSONArray rows = new JSONArray();
                for (AnimeSource source : next.values()) {
                    JSONObject row = new JSONObject().put("enabled", source.enabled).put("rule", source.definition);
                    if (!source.repository.isEmpty()) row.put("repository", source.repository);
                    if (source.startupEnabled) row.put("startupEnabled", true);
                    rows.put(row);
                }
                bytes = rows.toString().getBytes(StandardCharsets.UTF_8);
            } catch (JSONException invalid) { throw new IOException("无法保存动画源", invalid); }
            if (bytes.length > MAX_CATALOG_BYTES) throw new IOException("动画源清单超过容量限制");
            File parent = file.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法创建动画源存储目录");
            File temporary = File.createTempFile("anime-sources-", ".tmp", parent);
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
            } finally { Files.deleteIfExists(temporary.toPath()); }
        }
    }

    private static final class RepositoryBatch {
        final int listed;
        final Map<String, AnimeSource> rules = new LinkedHashMap<>();
        final List<String> diagnostics = new ArrayList<>();
        int failed, skipped;

        RepositoryBatch(int listed) { this.listed = listed; }

        void note(String reason) {
            if (diagnostics.size() < 32) diagnostics.add(reason);
        }

        ImportBatch toImportBatch() {
            ImportBatch batch = new ImportBatch();
            batch.valid.putAll(rules);
            return batch;
        }
    }

    private static final class RuleDownload {
        final String name;
        final AnimeSource source;
        final String problem;

        RuleDownload(String name, AnimeSource source, String problem) {
            this.name = name;
            this.source = source;
            this.problem = problem;
        }
    }

    private static URI repositoryIndex(String address) throws IOException {
        URI input = rawAddress(web(address == null ? "" : address.trim()));
        String path = input.getRawPath();
        String host = input.getHost();
        if ("github.com".equalsIgnoreCase(host)
                && ("/Predidit/KazumiRules".equalsIgnoreCase(path) || "/Predidit/KazumiRules/".equalsIgnoreCase(path))) {
            return web(OFFICIAL_REPOSITORY);
        }
        path = path == null ? "" : path;
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        String last = path.substring(path.lastIndexOf('/') + 1);
        if (!last.equalsIgnoreCase("index.json")) {
            if (last.toLowerCase(Locale.ROOT).endsWith(".json") || last.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                throw new IOException("请填写包含 index.json 的仓库目录或索引链接");
            }
            path += "/index.json";
        }
        return web(input.getScheme().toLowerCase(Locale.ROOT) + "://" + input.getRawAuthority() + path);
    }

    private static String repositoryKey(URI index) throws IOException {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(index.toASCIIString().getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException impossible) { throw new IOException("无法识别规则仓库", impossible); }
    }

    private static <T> T boundedRequest(ReaderSearchQueue workers, URI address, ReaderSearchQueue.Work<URI, T> work,
                                        String label) throws IOException {
        CompletableFuture<T> completed = new CompletableFuture<>();
        ReaderSearchQueue.Run run = workers.start(Collections.singletonList(address), work, (url, result, error) -> {
                    if (error == null) completed.complete(result);
                    else completed.completeExceptionally(error);
                });
        try {
            return completed.get(PER_RULE_TIMEOUT_MILLIS + 1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) { throw importCancelled(); }
        catch (TimeoutException expired) { throw new IOException(label + "读取超时", expired); }
        catch (ExecutionException failed) {
            throw new IOException(label + " · " + fetchProblem(failed.getCause()), failed.getCause());
        } finally { run.cancel(); }
    }

    private static final class IndexDocument {
        final URI url;
        final JSONArray metadata;

        IndexDocument(URI url, JSONArray metadata) {
            this.url = url;
            this.metadata = metadata;
        }
    }

    private static IndexDocument indexDocument(ReaderSearchQueue workers, URI address, boolean pinned,
                                               boolean requireEntries) throws IOException {
        ReaderSources.Response response = boundedRequest(workers, address,
                url -> ReaderSources.fetch(url.toASCIIString(), Collections.emptyMap()), "源目录");
        URI resolved = web(response.url);
        if (pinned && !sameEndpoint(address, resolved)) throw new IOException("源目录重定向到了其他地址");
        Object content = parse(response.body);
        if (!(content instanceof JSONArray)) throw new IOException("源目录应为 JSON 数组");
        JSONArray metadata = (JSONArray) content;
        if (requireEntries && !hasRepositoryEntry(metadata)) throw new IOException("源目录没有有效条目");
        return new IndexDocument(resolved, metadata);
    }

    private static final class ArchiveRules {
        final JSONArray metadata;
        final Map<String, byte[]> entries;

        ArchiveRules(JSONArray metadata, Map<String, byte[]> entries) {
            this.metadata = metadata;
            this.entries = entries;
        }
    }

    private static ArchiveRules readArchive(URI address) throws IOException {
        HttpURLConnection request = (HttpURLConnection) address.toURL().openConnection();
        ReaderSearchQueue.RequestBudget budget = ReaderSearchQueue.RequestBudget.current();
        byte[] bytes;
        try {
            request.setInstanceFollowRedirects(false);
            request.setConnectTimeout(budget == null ? 6_000 : budget.connectTimeoutMillis());
            request.setReadTimeout(budget == null ? 12_000 : (int) budget.remainingMillis());
            request.setRequestProperty("Accept", "application/zip");
            request.setRequestProperty("User-Agent", "ANON-TOOL Kazumi rule importer");
            if (budget != null) budget.attach(request);
            int status = request.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new ReaderSources.HttpFailure(status, null);
            try (InputStream raw = request.getInputStream(); InputStream monitored = budget == null ? raw : budget.monitor(raw)) {
                bytes = read(monitored, MAX_ARCHIVE_BYTES);
            }
        } finally {
            if (budget != null) budget.detach(request);
            request.disconnect();
        }

        Map<String, byte[]> entries = new LinkedHashMap<>();
        int expanded = 0;
        int entryCount = 0;
        String root = "KazumiRules-main/";
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            byte[] buffer = new byte[8192];
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entryCount > 512) throw new IOException("规则包项目过多");
                checkImportCancellation();
                String name = entry.getName().startsWith(root) ? entry.getName().substring(root.length()) : "";
                boolean eligible = !entry.isDirectory() && name.endsWith(".json")
                        && REPOSITORY_NAME.matcher(name.substring(0, name.length() - 5)).matches();
                ByteArrayOutputStream contents = eligible ? new ByteArrayOutputStream() : null;
                int count;
                while ((count = zip.read(buffer)) >= 0) {
                    if (budget != null) budget.check();
                    expanded += count;
                    if (expanded > MAX_ARCHIVE_EXPANDED) throw new IOException("规则包解压后超过容量限制");
                    if (contents != null && count > MAX_SOURCE_BYTES - contents.size()) contents = null;
                    if (contents != null) contents.write(buffer, 0, count);
                }
                if (contents != null && entries.putIfAbsent(name, contents.toByteArray()) != null) {
                    throw new IOException("规则包包含同名文件");
                }
            }
        }
        byte[] published = entries.remove("index.json");
        Object data = published == null ? null : parse(decode(published));
        if (!(data instanceof JSONArray) || !hasRepositoryEntry((JSONArray) data)) {
            throw new IOException("规则包中没有有效的发布目录");
        }
        return new ArchiveRules((JSONArray) data, entries);
    }

    private static boolean sameEndpoint(URI expected, URI received) {
        return ReaderEngine.sameOrigin(expected.toASCIIString(), received.toASCIIString())
                && expected.getRawPath().equals(received.getRawPath());
    }

    private static String repositoryName(JSONObject item) {
        String name = item == null ? "" : field(item, "name");
        return (item != null && item.has("baseURL")) || !REPOSITORY_NAME.matcher(name).matches()
                || name.equalsIgnoreCase("index") ? "" : name;
    }

    private static boolean hasRepositoryEntry(JSONArray metadata) {
        for (int i = 0; i < Math.min(metadata.length(), MAX_REPOSITORY_RULES); i++) {
            if (!repositoryName(metadata.optJSONObject(i)).isEmpty()) return true;
        }
        return false;
    }

    private static RepositoryBatch readRepository(ReaderSearchQueue workers, URI index, JSONArray metadata,
                                                  Consumer<String> progress) throws IOException {
        return readRepository(workers, index, metadata, progress, null, null, false);
    }

    private static RepositoryBatch readRepository(ReaderSearchQueue workers, URI index, JSONArray metadata,
                                                  Consumer<String> progress, ArchiveRules archive, URI mirror,
                                                  boolean pinned) throws IOException {
        RepositoryBatch result = new RepositoryBatch(metadata.length());
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, String> versions = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(metadata.length(), MAX_REPOSITORY_RULES); i++) {
            JSONObject item = metadata.optJSONObject(i);
            String name = repositoryName(item);
            if (name.isEmpty()) {
                result.skipped++;
                result.note("第 " + (i + 1) + " 项没有有效规则文件名");
            } else if (names.putIfAbsent(name.toLowerCase(Locale.ROOT), name) != null) {
                result.skipped++;
                result.note(name + " · 重复的目录条目");
            } else versions.put(name, field(item, "version"));
        }
        if (metadata.length() > MAX_REPOSITORY_RULES) {
            result.skipped += metadata.length() - MAX_REPOSITORY_RULES;
            result.note("一次最多读取前 " + MAX_REPOSITORY_RULES + " 个目录条目");
        }
        if (names.isEmpty()) return result;
        List<String> requested = new ArrayList<>(names.values());
        int total = requested.size();
        progress.accept("目录列出 " + result.listed + " 条，正在读取 " + total + " 个规则…");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(REPOSITORY_TIMEOUT_SECONDS);
        Map<String, RuleDownload> completed = new LinkedHashMap<>();
        if (archive != null) {
            for (String name : requested) {
                byte[] bytes = archive.entries.get(name + ".json");
                if (bytes == null) continue;
                try { completed.put(name, decodeRepositoryRule(name, decode(bytes), versions.get(name))); }
                catch (IOException invalid) { completed.put(name, new RuleDownload(name, null, "规则文件编码无效")); }
            }
        }
        List<String> missing = missingRules(requested, completed);
        if (!missing.isEmpty()) fetchRepositoryRules(workers, index, missing, versions, completed,
                mirror == null ? deadline : deadline - TimeUnit.SECONDS.toNanos(18), pinned, progress);
        if (mirror != null && System.nanoTime() < deadline) {
            missing = missingRules(requested, completed);
            if (!missing.isEmpty()) {
                progress.accept("正在从镜像重试 " + missing.size() + " 个源…");
                fetchRepositoryRules(workers, mirror, missing, versions, completed, deadline, pinned, progress);
                int recovered = missing.size() - missingRules(requested, completed).size();
                if (recovered > 0) result.note("官方镜像补读 " + recovered + " 条规则");
            }
        }
        for (String name : requested) {
            RuleDownload item = completed.get(name);
            if (item != null && item.source != null) result.rules.put(item.source.id, item.source);
            else {
                result.failed++;
                result.note(name + " · " + (item == null ? "超过仓库读取时限" : item.problem));
            }
        }
        progress.accept("已完成 " + total + " / " + total + " · 成功 " + result.rules.size() + " · 失败 " + result.failed);
        return result;
    }

    private static List<String> missingRules(List<String> requested, Map<String, RuleDownload> completed) {
        List<String> missing = new ArrayList<>();
        for (String name : requested) {
            RuleDownload item = completed.get(name);
            if (item == null || item.source == null) missing.add(name);
        }
        return missing;
    }

    private static void fetchRepositoryRules(ReaderSearchQueue workers, URI index, List<String> requested,
                                             Map<String, String> versions, Map<String, RuleDownload> completed,
                                             long deadline, boolean pinned, Consumer<String> progress) throws IOException {
        BlockingQueue<RuleDownload> ready = new LinkedBlockingQueue<>();
        ReaderSearchQueue.Run run = workers.start(requested, name -> readRepositoryRule(index, name, versions.get(name), pinned),
                (name, value, error) -> {
            ready.offer(error == null ? value : new RuleDownload(name, null, fetchProblem(error)));
        });
        int total = requested.size(), successful = 0, done = 0;
        try {
            while (done < total) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                RuleDownload downloaded = ready.poll(remaining, TimeUnit.NANOSECONDS);
                if (downloaded == null) break;
                completed.put(downloaded.name, downloaded);
                if (downloaded.source != null) successful++;
                done++;
                progress.accept("已读取 " + done + " / " + total + " · 成功 " + successful
                        + " · 失败 " + (done - successful));
            }
        } catch (InterruptedException interrupted) { throw importCancelled(); }
        finally { run.cancel(); }
    }

    private static RuleDownload readRepositoryRule(URI index, String name, String version, boolean pinned) throws IOException {
        String target = index.toASCIIString();
        int boundary = target.lastIndexOf('/');
        URI rule = web(target.substring(0, boundary + 1) + name + ".json");
        ReaderSources.Response response = ReaderSources.fetch(rule.toASCIIString(), Collections.emptyMap());
        if (pinned && !sameEndpoint(rule, web(response.url))) {
            return new RuleDownload(name, null, "官方规则重定向到了其他地址");
        }
        if (response.body.length() > MAX_SOURCE_BYTES || response.body.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) {
            return new RuleDownload(name, null, "规则文件超过大小限制");
        }
        return decodeRepositoryRule(name, response.body, version);
    }

    private static RuleDownload decodeRepositoryRule(String name, String body, String version) {
        Object downloaded;
        try { downloaded = parse(body); }
        catch (IOException invalid) { return new RuleDownload(name, null, "规则 JSON 无法解析"); }
        if (!(downloaded instanceof JSONObject)) return new RuleDownload(name, null, "不是单条 JSON 规则");
        JSONObject definition = (JSONObject) downloaded;
        if (!name.equals(field(definition, "name"))) return new RuleDownload(name, null, "规则名称与目录不一致");
        String currentVersion = field(definition, "version");
        if (!version.isEmpty() && (currentVersion.isEmpty() || versionBefore(currentVersion, version))) {
            return new RuleDownload(name, null, "规则文件版本早于发布目录");
        }
        try {
            return new RuleDownload(name, new AnimeSource(definition, true), "");
        } catch (IOException invalid) { return new RuleDownload(name, null, invalid.getMessage()); }
    }

    private static String fetchProblem(Throwable error) {
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
            if (cause instanceof ReaderSources.HttpFailure) return "HTTP " + ((ReaderSources.HttpFailure) cause).status;
            if (cause instanceof SocketTimeoutException) return "读取超时";
        }
        return "无法读取规则文件";
    }

    private static void checkImportCancellation() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw importCancelled();
    }

    private static InterruptedIOException importCancelled() {
        Thread.currentThread().interrupt();
        return new InterruptedIOException("动画源聚合已取消");
    }

    private static boolean versionBefore(String candidate, String installed) {
        if (!NUMERIC_VERSION.matcher(candidate).matches() || !NUMERIC_VERSION.matcher(installed).matches()) return false;
        String[] proposed = candidate.split("\\."), current = installed.split("\\.");
        for (int i = 0; i < Math.max(proposed.length, current.length); i++) {
            int left = i < proposed.length ? Integer.parseInt(proposed[i]) : 0;
            int right = i < current.length ? Integer.parseInt(current[i]) : 0;
            if (left != right) return left < right;
        }
        return false;
    }

    private static final class ImportBatch {
        final Map<String, AnimeSource> valid = new LinkedHashMap<>();
        final List<String> diagnostics = new ArrayList<>();
        int skipped;

        void reject(int count, String reason) {
            skipped += count;
            if (diagnostics.size() < 32) diagnostics.add(reason);
        }
    }

    private static void addShare(ImportBatch batch, String body) {
        try {
            String decoded = URLDecoder.decode(body.replace("+", "%2B"), "UTF-8").replaceAll("\\s", "");
            byte[] bytes = Base64.getDecoder().decode(decoded.replace('-', '+').replace('_', '/'));
            addJson(batch, parse(decode(bytes)));
        } catch (IOException | IllegalArgumentException invalid) { batch.reject(1, "源的分享链接无法解码"); }
    }

    private static void addJson(ImportBatch batch, Object document) {
        JSONArray rows;
        if (document instanceof JSONArray) rows = (JSONArray) document;
        else if (document instanceof JSONObject) {
            rows = ((JSONObject) document).optJSONArray("rules");
            if (rows == null) rows = new JSONArray().put(document);
        } else { batch.reject(1, "动画源规则必须是 JSON 对象或数组"); return; }
        for (int i = 0; i < Math.min(rows.length(), MAX_SOURCES); i++) {
            JSONObject rule = rows.optJSONObject(i);
            try {
                if (rule == null) throw new IOException("不是 JSON 规则对象");
                if (rule.toString().getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) {
                    throw new IOException("规则长度超过限制");
                }
                AnimeSource source = new AnimeSource(rule, rule.optBoolean("enabled", true));
                if (batch.valid.putIfAbsent(source.id, source) != null) batch.reject(1, "第 " + (i + 1) + " 条名称重复");
            } catch (IOException invalid) { batch.reject(1, "第 " + (i + 1) + " 条 · " + invalid.getMessage()); }
        }
        if (rows.length() > MAX_SOURCES) batch.reject(rows.length() - MAX_SOURCES, "单批只处理前 " + MAX_SOURCES + " 条规则");
    }

    private static URI rawAddress(URI url) throws IOException {
        String path = url.getRawPath();
        if ("github.com".equalsIgnoreCase(url.getHost()) && path != null) {
            String[] parts = path.split("/", 5);
            if (parts.length == 5 && parts[3].equals("blob")) {
                return web("https://raw.githubusercontent.com/" + parts[1] + "/" + parts[2] + "/" + parts[4]);
            }
        }
        return url;
    }

    private static boolean isRepositoryIndex(URI address, Object data) {
        if (!(data instanceof JSONArray) || !address.getPath().endsWith("/index.json")) return false;
        JSONObject first = ((JSONArray) data).optJSONObject(0);
        return first != null && !field(first, "name").isEmpty() && !first.has("baseURL");
    }

    static Object parse(String text) throws IOException {
        if (text.length() > MAX_CATALOG_BYTES) throw new IOException("JSON 内容超过大小限制");
        try {
            JSONTokener input = new JSONTokener(stripBom(text));
            Object value = input.nextValue();
            if (input.nextClean() != 0) throw new IOException("JSON 后有多余内容");
            return value;
        } catch (JSONException | StackOverflowError invalid) { throw new IOException("JSON 格式无法读取", invalid); }
    }

    private static byte[] read(InputStream input, int max) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count > max - output.size()) throw new IOException("动画源文件超过大小限制");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String decode(byte[] bytes) throws IOException {
        try {
            if (bytes.length > 1 && ((bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe)
                    || (bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff))) {
                return StandardCharsets.UTF_16.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            }
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) { throw new IOException("动画源文件需要 UTF-8 或带 BOM 的 UTF-16 编码", invalid); }
    }

    private static String stripBom(String value) { return value.startsWith("\ufeff") ? value.substring(1) : value; }
}
