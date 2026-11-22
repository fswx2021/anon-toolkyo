package com.shengjian.player;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds public source definitions from the repository's existing directory links. */
final class ReaderRepositoryIndex {
    private static final String CANONICAL_REPOSITORY = "https://www.yckceo.com";
    private static final Pattern ADVERTISED_URL = Pattern.compile("(?i)https?://[^\\s<>\"'（）【】]+");

    private ReaderRepositoryIndex() {}

    static final class Result {
        final List<Entry> entries;
        final int pages;
        final boolean truncated;

        Result(List<Entry> entries, int pages, boolean truncated) {
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
            this.pages = pages;
            this.truncated = truncated;
        }
    }

    static final class Entry {
        final String detailUrl;
        final String address;

        Entry(String detailUrl, String address) {
            this.detailUrl = detailUrl;
            this.address = address;
        }
    }

    static final class Listing {
        final List<Entry> entries;
        final Map<Integer, String> nextPages;

        Listing(List<Entry> entries, Map<Integer, String> nextPages) {
            this.entries = entries;
            this.nextPages = nextPages;
        }
    }

    static Result scan(String indexUrl, String detailGroup, int pageLimit) throws IOException {
        if (pageLimit < 1) throw new IOException("目录读取页数无效");
        URI initial = directory(indexUrl, detailGroup);
        Map<String, Entry> entries = new LinkedHashMap<>();
        TreeMap<Integer, String> remaining = new TreeMap<>();
        Set<Integer> requested = new HashSet<>();
        remaining.put(pageNumber(initial), initial.toASCIIString());
        int pages = 0;
        boolean partial = false;

        while (!remaining.isEmpty() && requested.size() < pageLimit) {
            Map.Entry<Integer, String> next = remaining.pollFirstEntry();
            if (next == null || !requested.add(next.getKey())) continue;
            if (Thread.currentThread().isInterrupted()) throw new IOException("目录读取已取消");
            try {
                ReaderSources.Response response = ReaderSources.fetch(next.getValue(), Collections.emptyMap());
                URI returned = directory(response.url, detailGroup);
                if (pageNumber(returned) != next.getKey()) throw new IOException("源仓库返回了不同的目录页");
                Listing listing = parseListing(response.body, returned, detailGroup);
                if (listing.entries.isEmpty()) throw new IOException("源仓库没有返回可识别的目录条目");
                pages++;
                for (Entry entry : listing.entries) {
                    Entry previous = entries.get(entry.detailUrl);
                    if (previous == null || (previous.address.isEmpty() && !entry.address.isEmpty())) {
                        entries.put(entry.detailUrl, entry);
                    }
                }
                for (Map.Entry<Integer, String> page : listing.nextPages.entrySet()) {
                    if (!requested.contains(page.getKey())) remaining.putIfAbsent(page.getKey(), page.getValue());
                }
            } catch (IOException error) {
                if (Thread.currentThread().isInterrupted() || pages == 0) throw error;
                partial = true;
            }
        }
        return new Result(new ArrayList<>(entries.values()), pages, partial || !remaining.isEmpty());
    }

    static Listing parseListing(String html, URI indexUrl, String detailGroup) throws IOException {
        URI directory = directory(indexUrl.toString(), detailGroup);
        String root = "/" + detailGroup;
        Pattern detail = Pattern.compile(Pattern.quote(root) + "/content/id/([0-9]{1,10})\\.html");
        Map<String, Entry> entries = new LinkedHashMap<>();
        Map<Integer, String> nextPages = new TreeMap<>();
        int currentPage = pageNumber(directory);
        for (Element anchor : Jsoup.parse(html, directory.toString()).select("a[href]")) {
            URI target = resolve(directory, anchor.attr("href"));
            if (target == null) continue;
            Matcher matching = detail.matcher(target.getPath());
            if (matching.matches()) {
                String detailUrl = CANONICAL_REPOSITORY + root + "/content/id/" + matching.group(1) + ".html";
                String address = advertisedAddress(anchor.text());
                Entry previous = entries.get(detailUrl);
                if (previous == null || (previous.address.isEmpty() && !address.isEmpty())) {
                    entries.put(detailUrl, new Entry(detailUrl, address));
                }
            } else if (isDirectoryPath(target.getPath(), root)) {
                int page = pageNumber(target);
                if (page > currentPage) {
                    String url = authority(directory) + target.getRawPath() + "?" + target.getRawQuery();
                    nextPages.putIfAbsent(page, url);
                }
            }
        }
        return new Listing(new ArrayList<>(entries.values()), nextPages);
    }

    private static URI directory(String value, String detailGroup) throws IOException {
        if (!"yiciyuan/tuyuan".equals(detailGroup) && !"yiciyuan/tuyuans".equals(detailGroup)
                && !"yuedu/shuyuan".equals(detailGroup) && !"yuedu/shuyuans".equals(detailGroup)) {
            throw new IOException("不支持这个源仓库目录");
        }
        try {
            URI url = URI.create(value);
            if (!isRepository(url) || !isDirectoryPath(url.getPath(), "/" + detailGroup)
                    || pageNumber(url) < 1) throw new IllegalArgumentException("invalid repository directory");
            return url;
        } catch (IllegalArgumentException invalid) {
            throw new IOException("源仓库目录地址不正确", invalid);
        }
    }

    private static boolean isDirectoryPath(String path, String root) {
        return root.equals(path) || (root + "/").equals(path) || (root + "/index.html").equals(path);
    }

    private static boolean isRepository(URI uri) {
        String host = uri.getHost();
        if (host == null || uri.getUserInfo() != null) return false;
        host = host.toLowerCase(Locale.ROOT);
        return "https".equalsIgnoreCase(uri.getScheme()) && (uri.getPort() < 0 || uri.getPort() == 443)
                && ("www.yckceo.com".equals(host) || "yckceo.com".equals(host));
    }

    private static URI resolve(URI directory, String href) {
        if (href.isEmpty() || href.startsWith("#")) return null;
        try {
            String reference = href.startsWith("?") ? directory.getRawPath() + href : href;
            URI target = directory.resolve(reference);
            return isRepository(target) ? target : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static String authority(URI url) {
        return "https://" + url.getHost().toLowerCase(Locale.ROOT);
    }

    private static int pageNumber(URI url) {
        String query = url.getRawQuery();
        if (query == null) return 1;
        for (String parameter : query.split("&")) {
            if (!parameter.startsWith("page=")) continue;
            String number = parameter.substring(5);
            return number.matches("[0-9]{1,8}") ? Integer.parseInt(number) : 0;
        }
        return 1;
    }

    private static String advertisedAddress(String label) {
        Matcher match = ADVERTISED_URL.matcher(label);
        while (match.find()) {
            String candidate = match.group();
            if (candidate.length() > 1024) continue;
            try {
                URI uri = URI.create(candidate);
                if (uri.getHost() != null && uri.getUserInfo() == null) return candidate;
            } catch (IllegalArgumentException ignored) {
                // Labels can contain a display-only address.
            }
        }
        return "";
    }
}
