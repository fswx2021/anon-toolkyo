package com.shengjian.player;

import org.json.JSONObject;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Map;

final class ReaderDiagnosticsVerification {
    private ReaderDiagnosticsVerification() {}

    static void verify(Path catalog) throws Exception {
        ReaderSources sources = new ReaderSources(catalog.toFile(), null);
        JSONObject goodRules = definition("公用小说源", "https://one.invalid/source");
        JSONObject privateRules = definition("AuthoriZation: very-private", "https://two.invalid/private-path")
                .put("header", new JSONObject().put("Cookie", "private-session-value"));
        ReaderSources.Source good = importSource(sources, goodRules);
        ReaderSources.Source privateSource = importSource(sources, privateRules);
        ReaderSourceDiagnostics data = new ReaderSourceDiagnostics("");
        data.put(new ReaderSourceDiagnostics.Record(good, ReaderSourceDiagnostics.Stage.CONTENT,
                ReaderSourceDiagnostics.Status.PASSED, ""));
        String secretMessage = "raw-error-key";
        data.put(new ReaderSourceDiagnostics.Record(privateSource, ReaderSourceDiagnostics.Stage.CATALOGUE,
                ReaderSourceDiagnostics.Status.FAILED, secretMessage));
        ReaderSourceDiagnostics reloaded = new ReaderSourceDiagnostics(data.serialize());
        require(reloaded.get(privateSource).label().equals("目录失败 · 读取失败"), "persist checked phase with allowlisted reason");
        require(ReaderSourceDiagnostics.reason(new IOException(secretMessage,
                new ReaderSources.HttpFailure(403, null))).equals("HTTP 403"), "typed cause preserves HTTP category");
        StringWriter writer = new StringWriter();
        reloaded.writeReport(writer, sources.list(ReaderSources.NOVEL));
        String report = writer.toString();
        require(report.contains("公用小说源") && report.contains("失败\t目录\t读取失败")
                && report.contains(ReaderSourceDiagnostics.reference(privateSource))
                && !report.contains("https://") && !report.contains("very-private")
                && !report.contains("private-session-value") && !report.contains(secretMessage),
                "report exports phases and opaque identifiers without raw secrets");
        ReaderSources.Source revised = importSource(sources, privateRules.put("ruleToc", new JSONObject()
                .put("chapterList", "revised")));
        require(reloaded.get(revised) == null, "last result invalidated on catalogue rule change");
        require(sources.disableChecked(ReaderSources.NOVEL,
                Map.of(privateSource.id, privateSource.diagnosticRevision)) == 0, "stale result cannot disable updated rules");
        require(sources.disableChecked(ReaderSources.COMIC,
                Map.of(revised.id, revised.diagnosticRevision)) == 0, "a selected action cannot disable another category");
        require(sources.disableChecked(ReaderSources.NOVEL,
                Map.of(revised.id, revised.diagnosticRevision)) == 1, "only explicitly selected revision disabled");
        ReaderSources restored = new ReaderSources(catalog.toFile(), null);
        require(restored.find(good.id).enabled && !restored.find(revised.id).enabled
                && restored.find(revised.id).definition.toString().contains("revised"),
                "atomic disable preserves unrelated sources and the selected source definition");
        System.out.println("PASS source diagnostics report sanitation, revision invalidation and selected disable");
    }

    private static JSONObject definition(String name, String url) {
        return new JSONObject().put("bookSourceName", name).put("bookSourceUrl", url).put("searchUrl", "/search?query={{key}}");
    }

    private static ReaderSources.Source importSource(ReaderSources sources, JSONObject definition) throws Exception {
        return sources.importText(definition.toString(), ReaderSources.NOVEL).sources.get(0);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
