package com.shengjian.player;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.Writer;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;

final class ReaderSourceDiagnostics {
    private static final int MAX_ROWS = 8000;
    private static final Pattern PRIVATE_NAME = Pattern.compile(
            "(?i)[:/?#=@&\\p{Cntrl}]|\\b(?:cookie|bearer|password|token|secret|authorization)\\b|[a-z0-9_+\\-]{28,}");

    enum Stage {
        SEARCH("搜索"), DISCOVERY("发现"), CATALOGUE("目录"), CONTENT("正文"), IMAGE("首图");
        final String label;
        Stage(String label) { this.label = label; }
    }

    enum Status {
        PASSED("通过"), NO_MATCH("未匹配"), NO_ENTRY("无搜索入口"), UNSUPPORTED("不兼容"), FAILED("失败");
        final String label;
        Status(String label) { this.label = label; }
    }

    static final class Record {
        final String sourceId;
        final int revision;
        final long time;
        final Stage stage;
        final Status status;
        final String reason;

        private Record(String sourceId, int revision, long time, Stage stage, Status status, String reason) {
            this.sourceId = sourceId;
            this.revision = revision;
            this.time = time;
            this.stage = stage;
            this.status = status;
            this.reason = allowedReason(reason);
        }

        Record(ReaderSources.Source source, Stage stage, Status status, String reason) {
            this(source.id, source.diagnosticRevision, System.currentTimeMillis(), stage, status, reason);
        }

        String label() {
            if (status != Status.FAILED) return status.label;
            return stage.label + "失败" + (reason.isEmpty() ? "" : " · " + reasonLabel(reason));
        }
    }

    private final Map<String, Record> records = new LinkedHashMap<>();

    ReaderSourceDiagnostics(String saved) {
        if (saved == null || saved.isEmpty()) return;
        try {
            JSONArray rows = new JSONArray(saved);
            for (int i = Math.max(0, rows.length() - MAX_ROWS); i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null || row.optString("source").isEmpty()) continue;
                try {
                    put(new Record(row.optString("source"), row.optInt("revision"), row.optLong("time"),
                            Stage.valueOf(row.optString("stage")), Status.valueOf(row.optString("status")),
                            row.optString("reason")));
                } catch (IllegalArgumentException invalid) {
                    // Ignore a damaged record and keep the rest of the report.
                }
            }
        } catch (JSONException invalid) {
            records.clear();
        }
    }

    Record get(ReaderSources.Source source) {
        Record record = records.get(source.id);
        return record != null && record.revision == source.diagnosticRevision ? record : null;
    }

    void put(Record record) {
        Record existing = records.get(record.sourceId);
        if (existing != null && existing.time > record.time) return;
        records.remove(record.sourceId);
        if (records.size() >= MAX_ROWS) records.remove(records.keySet().iterator().next());
        records.put(record.sourceId, record);
    }

    String serialize() {
        JSONArray rows = new JSONArray();
        try {
            for (Record record : records.values()) {
                rows.put(new JSONObject().put("source", record.sourceId).put("revision", record.revision)
                        .put("time", record.time).put("stage", record.stage.name()).put("status", record.status.name())
                        .put("reason", record.reason));
            }
        } catch (JSONException invalid) {
            throw new IllegalStateException("Could not save source check results", invalid);
        }
        return rows.toString();
    }

    int[] counts(List<ReaderSources.Source> sources) {
        int[] totals = new int[Status.values().length];
        for (ReaderSources.Source source : sources) {
            Record record = get(source);
            if (record != null) totals[record.status.ordinal()]++;
        }
        return totals;
    }

    void writeReport(Writer writer, List<ReaderSources.Source> sources) throws IOException {
        writer.write("来源编号\t来源名称\t类型\t已启用\t结果\t阶段\t原因\t检查时间 UTC\n");
        for (ReaderSources.Source source : sources) {
            Record record = get(source);
            writer.write(reference(source));
            writer.write('\t');
            writer.write(safeName(source));
            writer.write(source.kind == ReaderSources.NOVEL ? "\t小说\t" : "\t漫画\t");
            writer.write(source.enabled ? "是\t" : "否\t");
            if (record == null) {
                writer.write("未检查\t\t\t\n");
                continue;
            }
            writer.write(record.status.label + "\t" + record.stage.label + "\t" + reasonLabel(record.reason)
                    + "\t" + Instant.ofEpochMilli(record.time) + "\n");
        }
    }

    static String reference(ReaderSources.Source source) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.id.getBytes(StandardCharsets.UTF_8));
            StringBuilder label = new StringBuilder("S-");
            for (int i = 0; i < 6; i++) {
                int value = digest[i] & 255;
                label.append(Character.forDigit(value >>> 4, 16)).append(Character.forDigit(value & 15, 16));
            }
            return label.toString();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required", unavailable);
        }
    }

    private static String safeName(ReaderSources.Source source) {
        String name = source.name.trim();
        if (name.isEmpty() || name.length() > 80 || name.equals(source.url) || PRIVATE_NAME.matcher(name).find()) return "";
        return name.startsWith("+") || name.startsWith("-") ? "'" + name : name;
    }

    static String reason(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
            if (cause instanceof ReaderSources.HttpFailure) return allowedReason("HTTP " + ((ReaderSources.HttpFailure) cause).status);
            if (cause instanceof SocketTimeoutException) return "TIMEOUT";
            if (cause instanceof UnknownHostException) return "DNS";
            if (cause instanceof ConnectException || cause instanceof NoRouteToHostException) return "CONNECT";
            if (cause instanceof SSLException) return "TLS";
            if (cause instanceof ReaderSources.SiteFailure) return "SITE";
            if (cause instanceof ReaderSources.RuleFailure || cause instanceof LinkageError
                    || cause instanceof StackOverflowError || cause instanceof JSONException
                    || cause.getClass().getName().startsWith("org.mozilla.javascript.")) return "RULE";
        }
        return "READ";
    }

    private static String allowedReason(String reason) {
        switch (reason) {
            case "": case "TIMEOUT": case "DNS": case "CONNECT": case "TLS": case "SITE":
            case "RULE": case "READ": case "DECODE": return reason;
            default: return reason.matches("HTTP [1-5][0-9]{2}") ? reason : "READ";
        }
    }

    static String reasonLabel(String reason) {
        switch (reason) {
            case "TIMEOUT": return "超时";
            case "DNS": return "域名无法解析";
            case "CONNECT": return "无法连接";
            case "TLS": return "安全连接失败";
            case "SITE": return "站点限制";
            case "RULE": return "规则或脚本错误";
            case "READ": return "读取失败";
            case "DECODE": return "图片无法解码";
            default: return reason;
        }
    }
}
