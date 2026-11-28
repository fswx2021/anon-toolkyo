package com.shengjian.player;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

final class ReaderSources {
    static final int ALL = -1;
    static final int NOVEL = 0;
    static final int COMIC = 2;
    private static final int SEARCH_ENGINE_REVISION = 5;
    private static final int SOURCE_LIMIT = 12 * 1024 * 1024;
    private static final int AGGREGATION_FEED_LIMIT = 24 * 1024 * 1024;
    private static final int CATALOG_LIMIT = 32 * 1024 * 1024;
    private static final int ARCHIVE_EXPANDED_LIMIT = 48 * 1024 * 1024;
    private static final int MAX_ARCHIVE_ENTRIES = 10_000;
    private static final int MAX_DIAGNOSTICS = 25;
    private static final int PAGE_LIMIT = 8 * 1024 * 1024;
    private static final int IMAGE_LIMIT = 18 * 1024 * 1024;
    private static final int MAX_SOURCES = 8000;
    private static final long DEFAULT_BROWSER_SETTLE_MILLIS = 400;
    private static final long MAX_BROWSER_SETTLE_MILLIS = 20_000;
    private static final String SAMPLE_HOST = "reader-demo.shengjian.invalid";
    private static final Pattern SAMPLE_PATH = Pattern.compile(
            "/(?:novels|comics)/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+\\.(?:json|html|png|jpg|jpeg|webp|txt)");
    private static final Pattern REPO_DETAIL = Pattern.compile(
            "/(yiciyuan/(?:tuyuan|tuyuans)|yuedu/(?:shuyuan|shuyuans))/content/id/(\\d+)\\.html");
    private static final Pattern REPO_INDEX = Pattern.compile(
            "/(?:yiciyuan/(?:tuyuan|tuyuans)|yuedu/(?:shuyuan|shuyuans))(?:/(?:index\\.html)?)?");
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
    private static final Pattern CHARSET = Pattern.compile(
            "(?i)\\bcharset\\s*=\\s*[\"']?([A-Za-z0-9_:-]{2,40})");
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_\u0060|~0-9A-Za-z-]+");
    private static final Pattern NAME_SPACE = Pattern.compile("[\\p{Z}\\s]+");
    private static final Set<String> MANAGED_HEADERS = new HashSet<>();
    private static final Set<String> REDIRECT_HEADERS = new HashSet<>();
    static {
        Collections.addAll(MANAGED_HEADERS,
                "host", "connection", "content-length", "transfer-encoding", "proxy-authorization",
                "proxy-connection", "upgrade", "accept-encoding");
        Collections.addAll(REDIRECT_HEADERS,
                "accept", "accept-language", "user-agent", "content-type", "referer");
    }
    private static volatile SampleOpener samples;
    private static volatile BrowserLoader browserLoader;
    private static final int MAX_COOKIE_SESSIONS = 256;
    private static final Map<String, CookieManager> cookies = new LinkedHashMap<>(16, 0.75f, true);

    @FunctionalInterface
    interface SampleOpener {
        InputStream open(String relativeAsset) throws IOException;
    }

    @FunctionalInterface
    interface BrowserLoader {
        Response load(String url, Map<String, String> headers, String pageScript, long timeoutMillis,
                      long settleMillis)
                throws IOException;
    }

    static final class HttpFailure extends IOException {
        final int status;
        private final boolean deferRetry;

        HttpFailure(int status, String retryAfter) {
            super("网站请求失败（HTTP " + status + "）");
            this.status = status;
            deferRetry = retryAfter != null && !retryAfter.trim().isEmpty();
        }
    }

    static final class SiteFailure extends IOException {
        SiteFailure(String message) {
            super(message);
        }
    }

    static final class RuleFailure extends IOException {
        RuleFailure(String message) {
            super(message);
        }
    }

    static void setBrowserLoader(BrowserLoader loader) {
        browserLoader = loader;
    }

    static final class Source {
        final String id;
        final String name;
        final String url;
        final String searchUrl;
        final int searchRevision;
        final int diagnosticRevision;
        final String exploreUrl;
        final int kind;
        final boolean enabled;
        final JSONObject definition;
        private final Map<String, String> requestHeaders;
        private final String nameKey;
        private final String addressKey;

        private Source(JSONObject definition, int kind, boolean enabled, String url) {
            this.definition = YiciyuanRules.withPublicCompatibility(definition);
            this.kind = kind;
            this.enabled = enabled;
            this.url = url;
            id = kind + ":" + url;
            String display = text(this.definition, "bookSourceName");
            String normalized = NAME_SPACE.matcher(Normalizer.normalize(display, Normalizer.Form.NFKC))
                    .replaceAll(" ").trim();
            name = normalized.isEmpty() ? url : display;
            nameKey = kind + ":" + (normalized.isEmpty() ? url : normalized).toLowerCase(Locale.ROOT);
            addressKey = aggregationKey(kind, url);
            String search = text(this.definition, "searchUrl");
            searchUrl = this.definition.optBoolean("_yiciyuan") && YiciyuanRules.searchDisabled(search) ? "" : search;
            exploreUrl = this.definition.optBoolean("enabledExplore", true)
                    ? text(this.definition, "exploreUrl") : "";
            requestHeaders = parseHeaders(this.definition.opt("header"));
            int revision = java.util.Objects.hash(SEARCH_ENGINE_REVISION, searchUrl, requestHeaders,
                    this.definition.optString("ruleSearch"), this.definition.optString("jsLib"),
                    this.definition.optString("bookSourceComment"), this.definition.optString("sourceVariable"));
            searchRevision = ppcatModern() ? java.util.Objects.hash(revision, this.definition.optString("_ppcatRule"))
                    : revision;
            diagnosticRevision = java.util.Objects.hash(SEARCH_ENGINE_REVISION, this.definition.toString());
        }

        Map<String, String> headers() {
            return new LinkedHashMap<>(requestHeaders);
        }

        boolean ppcat() {
            return kind == COMIC && definition.optBoolean("_ppcat");
        }

        boolean ppcatModern() {
            return ppcat() && !definition.optBoolean("_yiciyuan");
        }
    }

    static Source offlineSource(int kind, String id, String title) {
        String prefix = kind + ":";
        if (!id.startsWith(prefix) || id.length() <= prefix.length()) {
            throw new IllegalArgumentException("Offline source identity does not match its kind");
        }
        String origin = id.substring(prefix.length());
        try {
            return new Source(new JSONObject().put("bookSourceName", title), kind, false, origin);
        } catch (JSONException invalid) {
            throw new IllegalArgumentException("Invalid offline source", invalid);
        }
    }

    private static final class SourceKeys {
        private final Set<String> names = new HashSet<>();
        private final Set<String> addresses = new HashSet<>();

        SourceKeys() {}

        SourceKeys(Iterable<Source> sources) {
            for (Source source : sources) add(source);
        }

        boolean contains(Source source) {
            return names.contains(source.nameKey) || addresses.contains(source.addressKey);
        }

        void add(Source source) {
            names.add(source.nameKey);
            addresses.add(source.addressKey);
        }
    }

    private static final class CatalogView {
        final Map<String, Source> byId;
        final Set<String> listed = new HashSet<>();
        final Set<String> representatives = new HashSet<>();

        CatalogView(Map<String, Source> byId, Set<String> preferred) {
            this.byId = byId;
            SourceKeys keys = new SourceKeys();
            // Persisted representatives retain their position even when turned off.
            for (Source source : byId.values()) {
                if (preferred.contains(source.id)) select(source, keys);
            }
            for (Source source : byId.values()) {
                if (source.enabled) select(source, keys);
            }
            for (Source source : byId.values()) select(source, keys);

            SourceKeys aliases = new SourceKeys();
            for (Source source : byId.values()) {
                if (!listed.contains(source.id)) aliases.add(source);
            }
            for (Source source : byId.values()) {
                if (listed.contains(source.id) && aliases.contains(source)) {
                    representatives.add(source.id);
                }
            }
        }

        private void select(Source source, SourceKeys keys) {
            if (!keys.contains(source)) {
                keys.add(source);
                listed.add(source.id);
            }
        }
    }

    static class ImportResult {
        final int added;
        final int updated;
        final int skipped;
        final List<Source> sources;
        final List<String> diagnostics;

        ImportResult(int added, int updated, int skipped, List<Source> sources, List<String> diagnostics) {
            this.added = added;
            this.updated = updated;
            this.skipped = skipped;
            this.sources = Collections.unmodifiableList(new ArrayList<>(sources));
            this.diagnostics = Collections.unmodifiableList(new ArrayList<>(diagnostics));
        }
    }

    private enum MangaArchive { NONE, PACKED, BUNDLED }

    static final class ImportPlan extends ImportResult {
        final int encrypted;
        final boolean newMangaArchive;
        final boolean bundledMangaArchive;

        private ImportPlan(int added, int updated, int skipped, List<Source> sources, List<String> diagnostics,
                           int encrypted, MangaArchive archive) {
            super(added, updated, skipped, sources, diagnostics);
            this.encrypted = encrypted;
            newMangaArchive = archive != MangaArchive.NONE;
            bundledMangaArchive = archive == MangaArchive.BUNDLED;
        }
    }

    static final class Response {
        final String url;
        final String body;
        final String mime;
        final String location;

        Response(String url, String body, String mime) {
            this(url, body, mime, "");
        }

        private Response(String url, String body, String mime, String location) {
            this.url = url;
            this.body = body;
            this.mime = mime;
            this.location = location;
        }
    }

    private static final class Download {
        final String url;
        final String type;
        final byte[] bytes;
        final String location;

        Download(String url, String type, byte[] bytes) {
            this(url, type, bytes, "");
        }

        Download(String url, String type, byte[] bytes, String location) {
            this.url = url;
            this.type = type;
            this.bytes = bytes;
            this.location = location;
        }
    }

    private final File catalog;
    private final Path newMangaMarker;
    private final SampleOpener assetOpener;
    private final boolean readable;
    private volatile CatalogView view;
    private volatile boolean newMangaAggregated;

    ReaderSources(File catalogFile, SampleOpener sampleOpener) {
        catalog = catalogFile.getAbsoluteFile();
        assetOpener = sampleOpener;
        newMangaMarker = catalog.toPath().resolveSibling(catalog.getName() + ".new-manga-source");
        try {
            newMangaAggregated = Files.isRegularFile(newMangaMarker);
        } catch (SecurityException ignored) {
            // Marker access does not affect catalog readability.
        }
        if (sampleOpener != null) {
            samples = sampleOpener;
        }
        CatalogView loaded = new CatalogView(Collections.emptyMap(), Collections.emptySet());
        boolean ok;
        try {
            loaded = load();
            ok = true;
        } catch (IOException | JSONException | SecurityException corrupt) {
            ok = false;
        }
        view = loaded;
        readable = ok;
        if (!newMangaAggregated && hasRepositorySources(loaded)) {
            try {
                storeNewMangaMarker();
            } catch (IOException | SecurityException ignored) {
                // Older imports are still recognized from the catalog when the marker cannot be stored.
            }
        }
    }

    List<Source> list(int kind) {
        CatalogView snapshot = view;
        List<Source> result = new ArrayList<>();
        for (Source source : snapshot.byId.values()) {
            if (source.kind == kind && snapshot.listed.contains(source.id)) {
                result.add(source);
            }
        }
        return result;
    }

    Source find(String id) {
        return view.byId.get(id);
    }

    boolean hasNewMangaSource() {
        return newMangaAggregated || hasRepositorySources(view);
    }

    private static boolean hasRepositorySources(CatalogView snapshot) {
        for (Source source : snapshot.byId.values()) {
            if (source.ppcat() && source.definition.optBoolean("_yiciyuan") && snapshot.listed.contains(source.id)) {
                return true;
            }
        }
        return false;
    }

    ImportResult importFile(File file, int kind) throws IOException {
        return apply(prepareFile(file, kind));
    }

    ImportPlan prepareFile(File file, int kind) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            return prepareStream(input, kind);
        }
    }

    ImportResult importStream(InputStream input, int kind) throws IOException {
        return apply(prepareStream(input, kind));
    }

    ImportPlan prepareStream(InputStream input, int kind) throws IOException {
        return preparePayload(read(input, SOURCE_LIMIT), "", kind);
    }

    ImportResult importUrl(String link, int kind) throws IOException {
        return apply(prepareUrl(link, kind));
    }

    ImportPlan prepareUrl(String link, int kind) throws IOException {
        return prepareRemote(link, kind, SOURCE_LIMIT, true);
    }

    ImportPlan prepareAggregateUrl(String link, int kind) throws IOException {
        return prepareRemote(link, kind, AGGREGATION_FEED_LIMIT, false);
    }

    private ImportPlan prepareRemote(String link, int kind, int limit, boolean manual) throws IOException {
        String address = importLink(link);
        boolean ppcat = PpcatStore.FEED_URL.equals(address);
        if (ppcat && kind == NOVEL) throw new IOException("此仓库仅包含漫画源");
        try {
            Download data = download(address, Collections.emptyMap(), null, limit);
            ImportPlan plan = preparePayload(data.bytes, data.type, kind, limit, manual);
            return ppcat ? requireMangaArchive(plan) : plan;
        } catch (IOException primary) {
            if (!ppcat || Thread.currentThread().isInterrupted()) throw primary;
            if (assetOpener != null) {
                try (InputStream snapshot = assetOpener.open(PpcatStore.BUNDLED_ASSET)) {
                    if (snapshot != null) {
                        String json = decode(read(snapshot, limit), "", "");
                        return requireMangaArchive(prepareRows(sourceArray(json, kind, limit),
                                kind, manual, MangaArchive.BUNDLED));
                    }
                } catch (IOException unavailable) {
                    if (Thread.currentThread().isInterrupted()) throw unavailable;
                    primary.addSuppressed(unavailable);
                }
            }
            try {
                Download mirror = download(PpcatStore.MIRROR_URL, Collections.emptyMap(), null, limit);
                PpcatStore.verifyMirror(mirror.bytes);
                return requireMangaArchive(preparePayload(mirror.bytes, mirror.type, kind, limit, manual));
            } catch (IOException failed) {
                failed.addSuppressed(primary);
                throw failed;
            }
        }
    }

    private static ImportPlan requireMangaArchive(ImportPlan plan) throws IOException {
        if (!plan.newMangaArchive) throw new IOException("源仓库返回了不受支持的格式");
        if (plan.sources.isEmpty()) throw new IOException("源规则包没有可导入的漫画源");
        return plan;
    }

    ImportResult importText(String json, int kind) throws IOException {
        return apply(prepareText(json, kind));
    }

    ImportPlan prepareText(String json, int kind) throws IOException {
        return prepareRows(sourceArray(json, kind, SOURCE_LIMIT), kind, true, MangaArchive.NONE);
    }

    private ImportPlan prepareRows(JSONArray input, int kind, boolean manual, MangaArchive archive) throws IOException {
        checkWritable();
        if (kind != NOVEL && kind != COMIC && kind != ALL) {
            throw new IOException("未知书源类型");
        }
        Map<String, Source> existing = view.byId;
        SourceKeys existingKeys = manual ? new SourceKeys(existing.values()) : new SourceKeys();
        SourceKeys importedKeys = new SourceKeys();
        Map<String, Source> imported = new LinkedHashMap<>();
        List<String> diagnostics = new ArrayList<>();
        int added = 0;
        int encrypted = 0;
        boolean ppcatArchive = archive != MangaArchive.NONE;
        for (int i = 0; i < input.length(); i++) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("导入已取消");
            JSONObject definition = input.optJSONObject(i);
            if (definition == null) {
                describe(diagnostics, i, "需要 JSON 对象");
                continue;
            }
            boolean legacy = YiciyuanRules.matches(definition);
            boolean ppcat = !(ppcatArchive && legacy) && PpcatRules.matches(definition);
            boolean yiciyuan = !ppcat && legacy;
            int actualKind = ppcat || yiciyuan ? COMIC : sourceKind(definition);
            if (actualKind != NOVEL && actualKind != COMIC) {
                describe(diagnostics, i, "暂不支持此书源类型");
                continue;
            }
            if (kind != ALL && kind != actualKind) {
                describe(diagnostics, i, actualKind == COMIC ? "属于漫画源" : "属于小说源");
                continue;
            }
            if (ppcat || yiciyuan) {
                try {
                    definition = ppcat ? PpcatRules.normalize(definition) : YiciyuanRules.normalize(definition);
                    if (ppcatArchive) definition.put("_ppcat", true);
                } catch (IOException invalid) {
                    describe(diagnostics, i, ppcat ? invalid.getMessage() : "无法转换源规则");
                    continue;
                } catch (JSONException invalid) {
                    describe(diagnostics, i, "无法保存源信息");
                    continue;
                }
            }
            boolean mangaRules = definition.optBoolean("_yiciyuan");
            if (mangaRules && !manual) {
                String issue = YiciyuanRules.readingIssue(definition);
                if (!issue.isEmpty()) {
                    encrypted++;
                    describe(diagnostics, i, issue);
                    continue;
                }
            }
            String url;
            try {
                url = sourceAddress(text(definition, "bookSourceUrl"));
            } catch (IOException invalid) {
                describe(diagnostics, i, "书源地址无效");
                continue;
            }
            boolean canExplore = definition.optBoolean("enabledExplore", true)
                    && !text(definition, "exploreUrl").isEmpty();
            String search = text(definition, "searchUrl");
            boolean disclosedSearch = mangaRules ? !YiciyuanRules.searchDisabled(search) : !search.isEmpty();
            if (!disclosedSearch && !canExplore && (!manual || !mangaRules)) {
                describe(diagnostics, i, "缺少可用的搜索或发现地址");
                continue;
            }
            String id = actualKind + ":" + url;
            if (imported.containsKey(id)) {
                describe(diagnostics, i, "本次导入已有相同书源");
                continue;
            }
            Source current = existing.get(id);
            Source source = new Source(definition, actualKind,
                    current == null ? definition.optBoolean("enabled", true) : current.enabled, url);
            if ((!manual || current == null) && (existingKeys.contains(source) || importedKeys.contains(source))) {
                describe(diagnostics, i, "已有同名或同地址书源");
                continue;
            }
            imported.put(source.id, source);
            importedKeys.add(source);
            if (current == null) {
                added++;
            }
        }
        if (manual && imported.isEmpty()) {
            String label = kind == ALL ? "小说或漫画源"
                    : kind == COMIC ? "漫画源" : "小说源";
            String detail = diagnostics.isEmpty() ? "" : "\n" + diagnostics.get(0);
            throw new IOException("没有找到可导入的" + label + "。" + detail);
        }
        if (manual && existing.size() + added > MAX_SOURCES) {
            throw new IOException("已达到书源数量上限，请先移除不用的书源");
        }
        return new ImportPlan(added, imported.size() - added, input.length() - imported.size(),
                new ArrayList<>(imported.values()), diagnostics, encrypted, archive);
    }

    synchronized ImportResult apply(ImportPlan plan) throws IOException {
        checkWritable();
        Map<String, Source> updatedSources = new LinkedHashMap<>(view.byId);
        SourceKeys keys = new SourceKeys(updatedSources.values());
        List<Source> applied = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>(plan.diagnostics);
        int skipped = plan.skipped;
        int added = 0;
        for (Source proposed : plan.sources) {
            Source current = updatedSources.get(proposed.id);
            if (current == null && keys.contains(proposed)) {
                skipped++;
                if (diagnostics.size() < MAX_DIAGNOSTICS) {
                    diagnostics.add("已有同名或同地址书源，已跳过重复项");
                }
                continue;
            }
            Source next = proposed;
            boolean enabled = current == null ? proposed.definition.optBoolean("enabled", true) : current.enabled;
            if (current == null) {
                added++;
            }
            if (enabled != proposed.enabled) {
                next = new Source(proposed.definition, proposed.kind, enabled, proposed.url);
            }
            updatedSources.put(next.id, next);
            keys.add(next);
            applied.add(next);
        }
        if (updatedSources.size() > MAX_SOURCES) {
            throw new IOException("已达到书源数量上限，请先移除不用的书源");
        }
        if (!applied.isEmpty()) save(updatedSources);
        if (plan.newMangaArchive) rememberNewMangaSource(plan.sources);
        return new ImportResult(added, applied.size() - added, skipped, applied, diagnostics);
    }

    static final class AggregateChanges {
        final ImportResult changes;
        final int newlyEnabled;
        final boolean limited;

        AggregateChanges(ImportResult changes, int newlyEnabled, boolean limited) {
            this.changes = changes;
            this.newlyEnabled = newlyEnabled;
            this.limited = limited;
        }
    }

    synchronized AggregateChanges addMissing(List<Source> candidates, int kind, int skipped) throws IOException {
        return addMissing(candidates, kind, skipped, Collections.emptyList());
    }

    synchronized AggregateChanges addMissing(List<Source> candidates, int kind, int skipped,
                                             List<Source> repositorySources) throws IOException {
        checkWritable();
        if (candidates.isEmpty()) {
            rememberNewMangaSource(repositorySources);
            return new AggregateChanges(new ImportResult(0, 0, skipped,
                    Collections.emptyList(), Collections.emptyList()), 0, false);
        }
        CatalogView snapshot = view;
        Map<String, Source> merged = new LinkedHashMap<>(snapshot.byId);
        SourceKeys keys = new SourceKeys(merged.values());
        int size = 2;
        for (Source existing : merged.values()) {
            size += catalogRecord(existing, snapshot.representatives.contains(existing.id)).length + 1;
        }
        List<Source> added = new ArrayList<>();
        boolean limited = false;
        for (Source candidate : candidates) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("聚合已取消");
            if (candidate.kind != kind || keys.contains(candidate)) {
                skipped++;
                continue;
            }
            Source proposed = new Source(candidate.definition, kind, true, candidate.url);
            int recordLength = catalogRecord(proposed, false).length + 1;
            if (merged.size() >= MAX_SOURCES || recordLength > CATALOG_LIMIT - size) {
                limited = true;
                skipped++;
                continue;
            }
            size += recordLength;
            keys.add(proposed);
            merged.put(proposed.id, proposed);
            added.add(proposed);
        }
        if (!added.isEmpty()) save(merged);
        rememberNewMangaSource(repositorySources);
        return new AggregateChanges(new ImportResult(added.size(), 0, skipped, added,
                Collections.emptyList()), added.size(), limited);
    }

    private void rememberNewMangaSource(List<Source> repositorySources) throws IOException {
        if (newMangaAggregated || repositorySources.isEmpty()) return;
        CatalogView snapshot = view;
        if (!hasRepositorySources(snapshot)) {
            SourceKeys known = new SourceKeys(snapshot.byId.values());
            if (!repositorySources.stream().allMatch(known::contains)) return;
        }
        try {
            storeNewMangaMarker();
        } catch (IOException failure) {
            // Import has already committed and can be recognized from the catalog if the marker cannot be stored.
            if (!hasRepositorySources(snapshot)) throw failure;
        }
    }

    private void storeNewMangaMarker() throws IOException {
        try {
            Files.createFile(newMangaMarker);
        } catch (FileAlreadyExistsException exists) {
            if (!Files.isRegularFile(newMangaMarker)) throw exists;
        }
        newMangaAggregated = true;
    }

    static String aggregationKey(int kind, String address) {
        try {
            URI uri = webUri(address).normalize();
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            boolean standardPort = port < 0 || port == (scheme.equals("https") ? 443 : 80);
            String path = uri.getRawPath();
            String query = uri.getRawQuery();
            String fragment = uri.getRawFragment();
            return kind + ":" + scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT)
                    + (standardPort ? "" : ":" + port) + (path == null || path.isEmpty() ? "/" : path)
                    + (query == null || query.isEmpty() ? "" : "?" + query)
                    + (fragment == null || fragment.isEmpty() ? "" : "#" + fragment);
        } catch (IOException invalid) {
            return kind + ":" + address;
        }
    }

    private static void describe(List<String> diagnostics, int index, String message) {
        if (diagnostics.size() < MAX_DIAGNOSTICS) {
            diagnostics.add("第 " + (index + 1) + " 条：" + message);
        }
    }

    private ImportPlan preparePayload(byte[] data, String type, int kind) throws IOException {
        return preparePayload(data, type, kind, SOURCE_LIMIT, true);
    }

    private ImportPlan preparePayload(byte[] data, String type, int kind, int limit, boolean manual)
            throws IOException {
        if (kind != NOVEL) {
            byte[] ppcat = PpcatStore.unpack(data, limit);
            if (ppcat != null) {
                return prepareRows(sourceArray(decode(ppcat, "", ""), kind, limit), kind, manual, MangaArchive.PACKED);
            }
        }
        if (data.length >= 2 && (data[0] & 0xff) == 0x1f && (data[1] & 0xff) == 0x8b) {
            try (InputStream stream = new GZIPInputStream(new ByteArrayInputStream(data))) {
                data = read(stream, limit);
                type = "";
            } catch (EOFException | ZipException invalid) {
                throw new IOException("GZIP 书源无法解压，请重新下载", invalid);
            }
        }
        if (isZip(data)) {
            JSONArray rows = new JSONArray();
            for (byte[] payload : readBackup(data)) {
                JSONArray entry = sourceArray(decode(payload, "", ""), kind, limit);
                for (int i = 0; i < entry.length(); i++) {
                    rows.put(entry.opt(i));
                }
            }
            return prepareRows(rows, kind, manual, MangaArchive.NONE);
        }
        return prepareRows(sourceArray(decode(data, type, ""), kind, limit), kind, manual, MangaArchive.NONE);
    }

    private static boolean isZip(byte[] data) {
        return data.length >= 4 && data[0] == 'P' && data[1] == 'K'
                && ((data[2] == 3 && data[3] == 4) || (data[2] == 5 && data[3] == 6));
    }

    private static List<byte[]> readBackup(byte[] data) throws IOException {
        List<byte[]> sources = new ArrayList<>();
        byte[] fallback = null;
        byte[] buffer = new byte[16 * 1024];
        int expanded = 0;
        int sourcesSize = 0;
        int entries = 0;
        int possibleSources = 0;
        try (ZipInputStream archive = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                if (++entries > MAX_ARCHIVE_ENTRIES) {
                    throw new IOException("ZIP 中的文件数量过多");
                }
                String path = entry.getName().replace('\\', '/');
                String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
                boolean named = !entry.isDirectory()
                        && (name.equals("booksource.json") || name.equals("mybooksource.json"));
                boolean candidate = !entry.isDirectory()
                        && (name.endsWith(".json") || name.endsWith(".txt"));
                if (candidate) {
                    possibleSources++;
                    if (possibleSources > 1 || named) {
                        fallback = null;
                    }
                }
                boolean keepFallback = !named && candidate && possibleSources == 1;
                ByteArrayOutputStream content = named || keepFallback ? new ByteArrayOutputStream() : null;
                int count;
                while ((count = archive.read(buffer)) != -1) {
                    expanded += count;
                    if (expanded > ARCHIVE_EXPANDED_LIMIT) {
                        throw new IOException("ZIP 解压后超过 48 MB");
                    }
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IOException("导入已取消");
                    }
                    if (content != null && count > SOURCE_LIMIT - sourcesSize - content.size()) {
                        if (named) {
                            throw new IOException("ZIP 中的书源文件总大小超过 12 MB");
                        }
                        content = null;
                    }
                    if (content != null) {
                        content.write(buffer, 0, count);
                    }
                }
                if (named) {
                    sources.add(content.toByteArray());
                    sourcesSize += content.size();
                } else if (content != null) {
                    fallback = content.toByteArray();
                }
            }
        } catch (EOFException | ZipException | IllegalArgumentException invalid) {
            throw new IOException("ZIP 书源无法解压，请重新下载", invalid);
        }
        if (!sources.isEmpty()) {
            return sources;
        }
        if (fallback != null) {
            return Collections.singletonList(fallback);
        }
        throw new IOException("ZIP 中没有可识别的书源文件，请选择 bookSource.json、myBookSource.json 或只含一份书源的 ZIP。");
    }

    synchronized Source setEnabled(Source source, boolean enabled) throws IOException {
        checkWritable();
        Source current = view.byId.get(source.id);
        if (current == null) {
            throw new IOException("书源已移除");
        }
        if (current.enabled == enabled) {
            return current;
        }
        Source updated = new Source(current.definition, current.kind, enabled, current.url);
        Map<String, Source> result = new LinkedHashMap<>(view.byId);
        result.put(updated.id, updated);
        save(result);
        return updated;
    }

    synchronized int setAllEnabled(int kind, boolean enabled) throws IOException {
        checkWritable();
        if (kind != NOVEL && kind != COMIC) {
            throw new IOException("未知书源类型");
        }
        CatalogView snapshot = view;
        Map<String, Source> updated = new LinkedHashMap<>(snapshot.byId);
        int changed = 0;
        for (Source source : snapshot.byId.values()) {
            if (source.kind == kind && snapshot.listed.contains(source.id) && source.enabled != enabled) {
                updated.put(source.id, new Source(source.definition, kind, enabled, source.url));
                changed++;
            }
        }
        if (changed > 0) {
            save(updated);
        }
        return changed;
    }

    synchronized int disableChecked(int kind, Map<String, Integer> checkedRevisions) throws IOException {
        checkWritable();
        CatalogView snapshot = view;
        Map<String, Source> updated = new LinkedHashMap<>(snapshot.byId);
        int changed = 0;
        for (Map.Entry<String, Integer> checked : checkedRevisions.entrySet()) {
            Source source = snapshot.byId.get(checked.getKey());
            if (source == null || source.kind != kind || !source.enabled || !snapshot.listed.contains(source.id)
                    || source.diagnosticRevision != checked.getValue()) continue;
            updated.put(source.id, new Source(source.definition, kind, false, source.url));
            changed++;
        }
        if (changed > 0) save(updated);
        return changed;
    }

    synchronized void remove(Source source) throws IOException {
        checkWritable();
        if (view.byId.containsKey(source.id)) {
            Map<String, Source> updated = new LinkedHashMap<>(view.byId);
            updated.remove(source.id);
            save(updated);
        }
    }

    private void checkWritable() throws IOException {
        if (!readable) {
            throw new IOException("本地书源记录无法读取");
        }
    }

    private CatalogView load() throws IOException, JSONException {
        Path file = catalog.toPath();
        Path legacyBackup = file.resolveSibling(file.getFileName() + ".bak");
        if (Files.exists(legacyBackup)) {
            // Android AtomicFile previously kept the last committed catalog in this sibling.
            Files.move(legacyBackup, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        JSONArray items;
        try (InputStream input = new FileInputStream(catalog)) {
            items = new JSONArray(decode(read(input, CATALOG_LIMIT), "", ""));
        } catch (FileNotFoundException missing) {
            if (catalog.exists()) {
                throw missing;
            }
            return new CatalogView(Collections.emptyMap(), Collections.emptySet());
        }
        Map<String, Source> result = new LinkedHashMap<>();
        Set<String> listed = new HashSet<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject record = items.getJSONObject(i);
            JSONObject definition = record.getJSONObject("definition");
            int kind = record.getInt("kind");
            Source source = new Source(definition, kind, record.optBoolean("enabled", true),
                    sourceAddress(text(definition, "bookSourceUrl")));
            result.put(source.id, source);
            if (record.optInt("listed", 0) == 1) {
                listed.add(source.id);
            }
        }
        return new CatalogView(result, listed);
    }

    private static byte[] catalogRecord(Source source, boolean representative) throws IOException {
        try {
            JSONObject record = new JSONObject();
            record.put("kind", source.kind);
            record.put("enabled", source.enabled);
            if (representative) {
                record.put("listed", 1);
            }
            record.put("definition", source.definition);
            return record.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException invalid) {
            throw new IOException("无法保存书源记录", invalid);
        }
    }

    private void save(Map<String, Source> updated) throws IOException {
        CatalogView next = new CatalogView(updated, view.listed);
        Path file = catalog.toPath();
        Files.createDirectories(file.getParent());
        Path pending = Files.createTempFile(file.getParent(), "reader-sources-", ".tmp");
        try {
            try (FileOutputStream sink = new FileOutputStream(pending.toFile());
                 BufferedOutputStream output = new BufferedOutputStream(sink)) {
                int size = 2;
                output.write('[');
                boolean first = true;
                for (Source source : updated.values()) {
                    byte[] data = catalogRecord(source, next.representatives.contains(source.id));
                    size += data.length + (first ? 0 : 1);
                    if (size > CATALOG_LIMIT) throw new IOException("书源记录超过 32 MB，请先移除不用的书源");
                    if (!first) output.write(',');
                    output.write(data);
                    first = false;
                }
                output.write(']');
                output.flush();
                sink.getFD().sync();
            }
            Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(pending);
        }
        view = next;
    }

    private static JSONArray sourceArray(String json, int kind, int limit) throws IOException {
        if (json == null || json.trim().isEmpty()) {
            throw new IOException("书源内容为空");
        }
        if (json.length() > limit) {
            throw new IOException("单次导入的书源不能超过 " + limit / (1024 * 1024) + " MB");
        }
        String content = stripBom(json.trim()).trim();
        if (kind != NOVEL && !content.startsWith("[") && !content.startsWith("{")) {
            content = unpackComicSources(content, limit);
        }
        try {
            JSONTokener tokenizer = new JSONTokener(content);
            Object value = tokenizer.nextValue();
            if (tokenizer.nextClean() != 0) {
                throw new JSONException("Trailing content");
            }
            if (value instanceof JSONArray) {
                return (JSONArray) value;
            }
            if (value instanceof JSONObject) {
                return wrappedSources((JSONObject) value);
            }
        } catch (JSONException | StackOverflowError invalid) {
            throw new IOException("书源文件应为 JSON 对象或数组", invalid);
        }
        throw new IOException("书源文件应为 JSON 对象或数组");
    }

    private static JSONArray wrappedSources(JSONObject object) {
        if (object.has("bookSourceUrl")) {
            return new JSONArray().put(object);
        }
        for (String key : new String[]{"bookSources", "sources", "bookSource", "myBookSource", "data", "rules"}) {
            Object value = object.opt(key);
            if (value instanceof JSONArray) {
                return (JSONArray) value;
            }
            if (value instanceof JSONObject && ((JSONObject) value).has("bookSourceUrl")) {
                return new JSONArray().put(value);
            }
        }
        JSONObject data = object.optJSONObject("data");
        if (data != null) {
            for (String key : new String[]{"bookSources", "sources", "bookSource", "myBookSource"}) {
                JSONArray wrapped = data.optJSONArray(key);
                if (wrapped != null) {
                    return wrapped;
                }
            }
        }
        return new JSONArray().put(object);
    }

    private static String unpackComicSources(String content, int limit) throws IOException {
        // Some 异次元 collections use Base64-wrapped zlib instead of plain JSON.
        StringBuilder encoded = new StringBuilder(content.length());
        for (int i = 0; i < content.length(); i++) {
            char character = content.charAt(i);
            if (character == ' ' || character == '\n' || character == '\r' || character == '\t'
                    || character == '\f') {
                continue;
            }
            encoded.append(character);
        }
        byte[] compressed;
        try {
            compressed = Base64.getDecoder().decode(encoded.toString());
        } catch (IllegalArgumentException invalid) {
            return content;
        }
        if (compressed.length < 2) {
            return content;
        }
        int method = compressed[0] & 0xff;
        int flags = compressed[1] & 0xff;
        if ((method & 0x0f) != 8 || (method >>> 4) > 7 || ((method << 8) | flags) % 31 != 0) {
            return content;
        }
        Inflater inflater = new Inflater();
        try (InputStream stream = new InflaterInputStream(new ByteArrayInputStream(compressed), inflater)) {
            byte[] expanded = read(stream, limit);
            if (!inflater.finished() || inflater.getBytesRead() != compressed.length) {
                throw new IOException("压缩源文件不完整或包含多余数据");
            }
            return decode(expanded, "", "");
        } catch (EOFException | ZipException invalid) {
            throw new IOException("压缩源文件无法解压，请重新下载", invalid);
        } finally {
            inflater.end();
        }
    }

    private static int sourceKind(JSONObject definition) {
        if (!definition.has("bookSourceType") || definition.isNull("bookSourceType")) {
            return NOVEL;
        }
        String type = definition.optString("bookSourceType").trim();
        if ("0".equals(type)) {
            return NOVEL;
        }
        return "2".equals(type) ? COMIC : Integer.MIN_VALUE;
    }

    private static String sourceAddress(String value) throws IOException {
        webUri(value);
        return value;
    }

    private static String importLink(String input) throws IOException {
        String value = input == null ? "" : input.trim();
        if (value.regionMatches(true, 0, "legado://", 0, 9)) {
            try {
                URI uri = URI.create(value);
                if ("import".equalsIgnoreCase(uri.getHost()) && "/bookSource".equalsIgnoreCase(uri.getPath())
                        && uri.getRawQuery() != null) {
                    for (String parameter : uri.getRawQuery().split("&")) {
                        if (parameter.startsWith("src=")) {
                            value = URLDecoder.decode(parameter.substring(4), "UTF-8");
                            break;
                        }
                    }
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        URI uri = webUri(value);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.equals("github.com") && "https".equalsIgnoreCase(uri.getScheme())) {
            String path = uri.getPath().toLowerCase(Locale.ROOT);
            if (path.equals("/acglibrary/ppcat_store") || path.equals("/acglibrary/ppcat_store/")
                    || path.equals("/acglibrary/ppcat_store/blob/master/store")
                    || path.equals("/acglibrary/ppcat_store/raw/master/store")) return PpcatStore.FEED_URL;
        }
        if ("www.yckceo.com".equals(host) || "yckceo.com".equals(host)) {
            Matcher detail = REPO_DETAIL.matcher(uri.getRawPath());
            if (detail.matches()) {
                return uri.resolve("/" + detail.group(1) + "/json/id/"
                        + detail.group(2) + ".json").toString();
            }
            if (REPO_INDEX.matcher(uri.getRawPath()).matches()) {
                throw new IOException("这是源仓库列表。请选择详情页、JSON 下载链接，或使用一键聚合。");
            }
        }
        return value;
    }

    static Response fetch(String url, Map<String, String> headers) throws IOException {
        return fetch(url, headers, null, "");
    }

    static Response fetch(String url, Map<String, String> headers, String postBody) throws IOException {
        return fetch(url, headers, postBody, "");
    }

    static Response fetchAnime(String url, Map<String, String> headers, String postBody) throws IOException {
        if (postBody == null) return fetch(url, headers);
        return response(downloadOnce(url, headers, postBody, PAGE_LIMIT, null,
                ReaderSearchQueue.RequestBudget.current(), false, true), "");
    }

    static Response fetchAnime(String url, Map<String, String> headers, String postBody, int timeoutMillis)
            throws IOException {
        return response(downloadOnce(url, headers, postBody, PAGE_LIMIT, null,
                ReaderSearchQueue.RequestBudget.current(), false, true, timeoutMillis), "");
    }

    static Response fetch(String url, Map<String, String> headers, String postBody, String responseCharset)
            throws IOException {
        return fetch(null, url, headers, postBody, responseCharset);
    }

    static Response fetch(Source source, String url, Map<String, String> headers, String postBody,
                          String responseCharset) throws IOException {
        Download result = download(url, headers, postBody, PAGE_LIMIT, source);
        return response(result, responseCharset);
    }

    static Response fetchScriptPost(Source source, String url, Map<String, String> headers, String body)
            throws IOException {
        if (!sameOrigin(webUri(source.url).toURL(), webUri(url).toURL())) {
            throw new IOException("来源脚本只能向同源网址提交数据");
        }
        Download result = downloadOnce(url, headers, body == null ? "" : body, PAGE_LIMIT, source,
                ReaderSearchQueue.RequestBudget.current(), true);
        return response(result, "");
    }

    static Response fetchScriptGet(Source source, String url, Map<String, String> headers) throws IOException {
        if (!sameOrigin(webUri(source.url).toURL(), webUri(url).toURL())) {
            throw new ReaderSources.RuleFailure("来源脚本的网络 GET 只支持同源请求");
        }
        return response(download(url, headers, null, PAGE_LIMIT, source, true), "");
    }

    static void clearScriptSession(Source source, String url) throws IOException {
        URI address = webUri(url);
        if (!sameOrigin(webUri(source.url).toURL(), address.toURL())) {
            throw new ReaderSources.RuleFailure("来源脚本只能清除自己的站点会话");
        }
        CookieManager session = cookieSession(source, address, false);
        if (session != null) session.getCookieStore().removeAll();
    }

    private static Response response(Download result, String responseCharset) throws IOException {
        return new Response(result.url, decode(result.bytes, result.type, responseCharset),
                result.type.split(";", 2)[0].trim().toLowerCase(Locale.ROOT), result.location);
    }

    static Response fetchBrowser(String url, Map<String, String> headers, String script, long declaredSettleMillis)
            throws IOException {
        BrowserLoader loader = browserLoader;
        if (loader == null) throw new IOException("此来源需要 Android WebView 渲染");
        if (script.length() > 64 * 1024) throw new IOException("网页渲染脚本过长");
        ReaderSearchQueue.RequestBudget budget = ReaderSearchQueue.RequestBudget.current();
        long timeout = budget == null ? 12_000 : budget.remainingMillis();
        long settle = Math.max(DEFAULT_BROWSER_SETTLE_MILLIS,
                Math.min(MAX_BROWSER_SETTLE_MILLIS, declaredSettleMillis));
        if (settle >= timeout) throw new SocketTimeoutException("来源的浏览器渲染等待时间超过查询时限");
        Response page = loader.load(webUri(url).toASCIIString(), cleanHeaders(headers), script, timeout, settle);
        webUri(page.url);
        if (page.body.length() > PAGE_LIMIT) throw new IOException("网页渲染结果过长");
        return page;
    }

    static byte[] fetchImage(String url, Map<String, String> headers) throws IOException {
        return imageBytes(download(url, headers, null, IMAGE_LIMIT));
    }

    static byte[] fetchImage(String url, Map<String, String> headers, int timeoutMillis, int limit)
            throws IOException {
        return imageBytes(downloadOnce(url, headers, null, limit, null,
                ReaderSearchQueue.RequestBudget.current(), false, false, timeoutMillis));
    }

    private static byte[] imageBytes(Download result) throws IOException {
        String type = result.type.trim().toLowerCase(Locale.ROOT);
        if (type.startsWith("text/") || type.startsWith("application/json")) {
            throw new IOException("图片地址返回了网页或文本");
        }
        return result.bytes;
    }

    private static Download download(String url, Map<String, String> headers, String postBody, int limit)
            throws IOException {
        return download(url, headers, postBody, limit, null);
    }

    private static Download download(String url, Map<String, String> headers, String postBody, int limit,
                                     Source source) throws IOException {
        return download(url, headers, postBody, limit, source, false);
    }

    private static Download download(String url, Map<String, String> headers, String postBody, int limit,
                                     Source source, boolean captureRedirect) throws IOException {
        ReaderSearchQueue.RequestBudget budget = ReaderSearchQueue.RequestBudget.current();
        try {
            return downloadOnce(url, headers, postBody, limit, source, budget, captureRedirect);
        } catch (IOException failed) {
            if (budget == null || source == null || postBody != null || !transientFailure(failed)
                    || !budget.claimRetry(failed instanceof SocketTimeoutException || failed instanceof SocketException)) {
                throw failed;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("搜索已取消");
            }
            budget.check();
            return downloadOnce(url, headers, null, limit, source, budget, captureRedirect);
        }
    }

    private static boolean transientFailure(IOException failed) {
        if (failed instanceof HttpFailure) {
            HttpFailure http = (HttpFailure) failed;
            return !http.deferRetry && (http.status == 408 || http.status == 500 || http.status == 502
                    || http.status == 503 || http.status == 504 || http.status == 520
                    || http.status == 521 || http.status == 522 || http.status == 523 || http.status == 524);
        }
        return failed instanceof SocketTimeoutException || failed instanceof SocketException;
    }

    private static Download downloadOnce(String url, Map<String, String> headers, String postBody, int limit,
                                         Source source, ReaderSearchQueue.RequestBudget budget, boolean captureRedirect)
            throws IOException {
        return downloadOnce(url, headers, postBody, limit, source, budget, captureRedirect, false);
    }

    private static Download downloadOnce(String url, Map<String, String> headers, String postBody, int limit,
                                         Source source, ReaderSearchQueue.RequestBudget budget,
                                         boolean captureRedirect, boolean originBoundPost) throws IOException {
        return downloadOnce(url, headers, postBody, limit, source, budget, captureRedirect, originBoundPost,
                Integer.MAX_VALUE);
    }

    private static Download downloadOnce(String url, Map<String, String> headers, String postBody, int limit,
                                         Source source, ReaderSearchQueue.RequestBudget budget,
                                         boolean captureRedirect, boolean originBoundPost, int timeoutMillis)
            throws IOException {
        URI uri = webUri(url);
        if (SAMPLE_HOST.equalsIgnoreCase(uri.getHost())) {
            return openSample(uri, postBody, limit);
        }
        URL current = new URL(uri.toASCIIString());
        byte[] body = postBody == null ? null : postBody.getBytes(StandardCharsets.UTF_8);
        Map<String, String> requestHeaders = cleanHeaders(headers);
        boolean usesCookies = source != null && source.definition.optBoolean("enabledCookieJar",
                !source.definition.optBoolean("_yiciyuan", false));
        for (int redirects = 0; redirects < 7; redirects++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("请求已取消");
            }
            URI address = URI.create(current.toExternalForm());
            HttpURLConnection request = (HttpURLConnection) current.openConnection();
            try {
                request.setInstanceFollowRedirects(false);
                request.setConnectTimeout(Math.min(timeoutMillis,
                        budget == null ? 10_000 : budget.connectTimeoutMillis()));
                request.setReadTimeout((int) Math.min(timeoutMillis,
                        budget == null ? 15_000 : budget.remainingMillis()));
                request.setRequestProperty("User-Agent", USER_AGENT);
                request.setRequestProperty("Accept", "*/*");
                request.setRequestProperty("Accept-Encoding", "gzip, deflate");
                for (Map.Entry<String, String> header : requestHeaders.entrySet()) {
                    request.setRequestProperty(header.getKey(), header.getValue());
                }
                if (usesCookies && request.getRequestProperty("Cookie") == null) {
                    CookieManager session = cookieSession(source, address, false);
                    if (session != null) {
                        List<String> values = session.get(address, Collections.emptyMap()).get("Cookie");
                        if (values != null && !values.isEmpty()) {
                            request.setRequestProperty("Cookie", String.join("; ", values));
                        }
                    }
                }
                if (budget != null) {
                    request.setRequestProperty("Connection", "close");
                    budget.attach(request);
                }
                if (body != null) {
                    request.setRequestMethod("POST");
                    request.setDoOutput(true);
                    if (request.getRequestProperty("Content-Type") == null) {
                        request.setRequestProperty("Content-Type",
                                "application/x-www-form-urlencoded; charset=UTF-8");
                    }
                    request.setFixedLengthStreamingMode(body.length);
                    try (java.io.OutputStream output = request.getOutputStream()) {
                        output.write(body);
                    }
                }
                if (budget != null) budget.check();
                int status = request.getResponseCode();
                if (budget != null) budget.check();
                if (usesCookies && (request.getHeaderField("Set-Cookie") != null
                        || request.getHeaderField("Set-Cookie2") != null)) {
                    cookieSession(source, address, true).put(address, request.getHeaderFields());
                }
                if (isRedirect(status)) {
                    String location = request.getHeaderField("Location");
                    if (location == null || location.trim().isEmpty()) {
                        throw new IOException("网站返回了无效跳转");
                    }
                    URI nextUri = webUri(new URL(current, location).toExternalForm());
                    URL next = new URL(nextUri.toASCIIString());
                    if (captureRedirect) {
                        if (!sameOrigin(current, next)) throw new IOException("来源脚本的请求返回了跨站跳转");
                        return new Download(current.toExternalForm(), "", new byte[0], location);
                    }
                    if (!sameOrigin(current, next)) {
                        if (originBoundPost && body != null && (status == 307 || status == 308)) {
                            throw new IOException("动画源 POST 请求不能把内容转发到异站");
                        }
                        requestHeaders.entrySet().removeIf(item -> !REDIRECT_HEADERS.contains(
                                item.getKey().toLowerCase(Locale.ROOT)));
                        if ("https".equals(current.getProtocol()) && "http".equals(next.getProtocol())) {
                            requestHeaders.entrySet().removeIf(item -> item.getKey().equalsIgnoreCase("Referer"));
                        }
                    }
                    current = next;
                    if (status == 301 || status == 302 || status == 303) {
                        body = null;
                    }
                    continue;
                }
                if (status < 200 || status >= 300) {
                    throw new HttpFailure(status, request.getHeaderField("Retry-After"));
                }
                String type = request.getContentType();
                InputStream raw = request.getInputStream();
                try (InputStream response = budget == null ? raw : budget.monitor(raw);
                     InputStream data = decompress(response, request.getContentEncoding())) {
                    return new Download(current.toExternalForm(), type == null ? "" : type,
                            read(data, limit));
                }
            } finally {
                if (budget == null || budget.detach(request)) request.disconnect();
            }
        }
        throw new IOException("网站跳转次数过多");
    }

    private static CookieManager cookieSession(Source source, URI address, boolean create) {
        String key = source.id + '\u001f' + address.getScheme().toLowerCase(Locale.ROOT)
                + "://" + address.getHost().toLowerCase(Locale.ROOT) + ':' + effectivePort(address);
        synchronized (cookies) {
            CookieManager session = cookies.get(key);
            if (session == null && create) {
                if (cookies.size() >= MAX_COOKIE_SESSIONS) cookies.remove(cookies.keySet().iterator().next());
                session = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
                cookies.put(key, session);
            }
            return session;
        }
    }

    private static int effectivePort(URI address) {
        return address.getPort() >= 0 ? address.getPort() : "https".equalsIgnoreCase(address.getScheme()) ? 443 : 80;
    }

    private static Download openSample(URI uri, String postBody, int limit) throws IOException {
        SampleOpener opener = samples;
        String path = uri.getRawPath();
        if (opener == null || !"https".equalsIgnoreCase(uri.getScheme())
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || postBody != null || path == null || !SAMPLE_PATH.matcher(path).matches()) {
            throw new IOException("示例书源地址不可用");
        }
        try (InputStream input = opener.open("reader-samples" + path)) {
            if (input == null) {
                throw new FileNotFoundException(path);
            }
            return new Download(uri.toASCIIString(), sampleType(path), read(input, limit));
        } catch (FileNotFoundException missing) {
            throw new IOException("没有找到此示例内容", missing);
        }
    }

    private static String sampleType(String path) {
        String extension = path.substring(path.lastIndexOf('.') + 1);
        switch (extension) {
            case "png": return "image/png";
            case "jpg":
            case "jpeg": return "image/jpeg";
            case "webp": return "image/webp";
            case "json": return "application/json; charset=UTF-8";
            case "html": return "text/html; charset=UTF-8";
            default: return "text/plain; charset=UTF-8";
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static boolean sameOrigin(URL first, URL second) {
        int firstPort = first.getPort() == -1 ? first.getDefaultPort() : first.getPort();
        int secondPort = second.getPort() == -1 ? second.getDefaultPort() : second.getPort();
        return first.getProtocol().equalsIgnoreCase(second.getProtocol())
                && first.getHost().equalsIgnoreCase(second.getHost()) && firstPort == secondPort;
    }

    private static URI webUri(String value) throws IOException {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    && uri.getHost() != null && uri.getRawUserInfo() == null) {
                return uri;
            }
        } catch (IllegalArgumentException ignored) {
        }
        throw new IOException("请输入有效的 HTTP 或 HTTPS 地址");
    }

    private static InputStream decompress(InputStream input, String encoding) throws IOException {
        if ("gzip".equalsIgnoreCase(encoding) || "x-gzip".equalsIgnoreCase(encoding)) {
            return new GZIPInputStream(input);
        }
        if ("deflate".equalsIgnoreCase(encoding)) {
            return new InflaterInputStream(input);
        }
        return input;
    }

    private static byte[] read(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] bytes = new byte[16 * 1024];
        int count;
        while ((count = input.read(bytes)) != -1) {
            if (count > limit - output.size()) {
                throw new IOException("文件超过 " + limit / (1024 * 1024) + " MB");
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("请求已取消");
            }
            output.write(bytes, 0, count);
        }
        return output.toByteArray();
    }

    private static String decode(byte[] bytes, String contentType, String fallbackCharset) {
        if (bytes.length >= 2 && ((bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe)
                || (bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff))) {
            return new String(bytes, StandardCharsets.UTF_16);
        }
        if (bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb
                && bytes[2] == (byte) 0xbf) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        Charset charset = findCharset(contentType);
        if (charset == null) charset = findHtmlCharset(bytes);
        if (charset != null) {
            return stripBom(new String(bytes, charset));
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            Charset fallback = charset(fallbackCharset);
            return stripBom(new String(bytes, fallback == null ? Charset.forName("GB18030") : fallback));
        }
    }

    private static Charset findHtmlCharset(byte[] bytes) {
        String prefix = new String(bytes, 0, Math.min(bytes.length, 8192), StandardCharsets.ISO_8859_1);
        if (!prefix.trim().startsWith("<") || !CHARSET.matcher(prefix).find()) return null;
        for (Element meta : Jsoup.parse(prefix).head().getElementsByTag("meta")) {
            Charset declared = charset(meta.attr("charset"));
            if (declared == null && "Content-Type".equalsIgnoreCase(meta.attr("http-equiv").trim())) {
                declared = findCharset(meta.attr("content"));
            }
            if (declared != null) return declared;
        }
        return null;
    }

    private static String stripBom(String value) {
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    private static Charset findCharset(String value) {
        Matcher matcher = CHARSET.matcher(value);
        return matcher.find() ? charset(matcher.group(1)) : null;
    }

    private static Charset charset(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String name = value.trim();
        if ("GB2312".equalsIgnoreCase(name) || "GBK".equalsIgnoreCase(name)) {
            name = "GB18030";
        }
        try {
            return Charset.forName(name);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static Map<String, String> parseHeaders(Object header) {
        JSONObject data = header instanceof JSONObject ? (JSONObject) header : null;
        if (data == null && header instanceof String && ((String) header).trim().startsWith("{")) {
            try {
                data = new JSONObject((String) header);
            } catch (JSONException invalid) {
                return Collections.emptyMap();
            }
        }
        if (data == null) {
            return Collections.emptyMap();
        }
        Map<String, String> requested = new LinkedHashMap<>();
        Iterator<String> names = data.keys();
        while (names.hasNext()) {
            String key = names.next();
            Object value = data.opt(key);
            if (value instanceof String || value instanceof Number) {
                requested.put(key, value.toString());
            }
        }
        return cleanHeaders(requested);
    }

    private static Map<String, String> cleanHeaders(Map<String, String> headers) {
        Map<String, String> result = new LinkedHashMap<>();
        if (headers == null) {
            return result;
        }
        for (Map.Entry<String, String> header : headers.entrySet()) {
            String key = header.getKey();
            String value = header.getValue();
            if (key != null && value != null && HEADER_NAME.matcher(key).matches()
                    && !MANAGED_HEADERS.contains(key.toLowerCase(Locale.ROOT)) && !hasControl(value)) {
                result.put(key, value);
            }
        }
        return result;
    }

    private static boolean hasControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            char code = value.charAt(i);
            if ((code < ' ' && code != '\t') || code == 0x7f) {
                return true;
            }
        }
        return false;
    }

    private static String text(JSONObject data, String key) {
        Object value = data.opt(key);
        return value instanceof String ? ((String) value).trim() : "";
    }
}
