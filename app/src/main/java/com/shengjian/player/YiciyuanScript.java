package com.shengjian.player;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.json.JsonParser;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Executes source transformations with JavaScript values and a narrow, read-only HTML API. */
final class YiciyuanScript {
    private static final int MAX_RULE = 240_000;
    private static final int MAX_INPUT = 3_000_000;
    private static final int MAX_RETURN = 3_000_000;
    private static final int MAX_NODES = 18_000;
    private static final int MAX_ELEMENTS = 12_000;
    private static final int MAX_DOM_CALLS = 1_200;
    private static final int MAX_DEPTH = 40;
    private static final int MAX_REQUESTS = 4;
    private static final int MAX_INSTRUCTIONS = 2_000_000;
    private static final long MAX_TIME = TimeUnit.MILLISECONDS.toNanos(900);
    private static final Object BUDGET = new Object();
    private static final ContextFactory ENGINE = new ContextFactory() {
        @Override protected Context makeContext() {
            Context context = super.makeContext();
            context.setOptimizationLevel(-1);
            context.setLanguageVersion(Context.VERSION_ES6);
            context.setClassShutter(name -> false);
            context.setMaximumInterpreterStackDepth(64);
            context.setInstructionObserverThreshold(2_000);
            context.putThreadLocal(BUDGET, new Limits());
            return context;
        }

        @Override protected void observeInstructionCount(Context context, int count) {
            Limits limits = limits(context);
            limits.instructions += count;
            limits.check();
        }
    };

    private YiciyuanScript() {}

    static boolean matches(String rule) {
        String lower = rule.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("@js:") || lower.startsWith("js:") || lower.startsWith("<js>");
    }

    static Object evaluate(String rule, Object input, String baseUrl) throws IOException {
        return evaluate(rule, input, baseUrl, null, 0, null, null, null);
    }

    static Object evaluateUrl(String rule, String input, String baseUrl, String key, int page,
                              LegadoScope source) throws IOException {
        return evaluate(matches(rule) ? rule : "@js:" + rule, input, baseUrl, key, page, source, null, null);
    }

    static Object evaluateLegado(String rule, Object input, String baseUrl, LegadoScope source,
                                 FieldLookup lookup) throws IOException {
        return evaluate(rule, input, baseUrl, null, 0, source, null, lookup);
    }

    static Object evaluateYiciyuan(String rule, Object input, String baseUrl, MangaVariables variables) throws IOException {
        return evaluate(rule, input, baseUrl, null, 0, null, variables, null);
    }

    static Object evaluateYiciyuanUrl(String rule, String baseUrl, String searchKey, int searchPage) throws IOException {
        return evaluate(rule, "", baseUrl, searchKey, searchPage, null, null, null);
    }

    private static Object evaluate(String rule, Object input, String baseUrl, String key, int page,
                                   LegadoScope state, MangaVariables variables, FieldLookup lookup)
            throws IOException {
        String trimmed = rule.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        String code;
        if (lower.startsWith("<js>") && lower.endsWith("</js>")) {
            code = trimmed.substring(4, trimmed.length() - 5);
        } else if (lower.startsWith("@js:")) {
            code = trimmed.substring(4);
        } else if (lower.startsWith("js:")) {
            code = trimmed.substring(3);
        } else {
            throw new IOException("无法识别来源脚本规则");
        }
        String library = state == null ? "" : state.library();
        if (library.length() > MAX_RULE || code.length() > MAX_RULE - library.length()) {
            throw new IOException("来源脚本规则过长");
        }
        boolean htmlList = state != null && input instanceof Elements;
        String serialized = input == null || htmlList ? "" : input.toString();
        if (serialized.length() > MAX_INPUT) throw new IOException("网页数据太大，无法运行图源脚本");
        try {
            return ENGINE.call(context -> {
                ScriptableObject scope = context.initSafeStandardObjects();
                ScriptableObject.putProperty(scope, "baseUrl", baseUrl);
                if (key != null) {
                    ScriptableObject.putProperty(scope, "key", key);
                    ScriptableObject.putProperty(scope, "page", page);
                    if (state == null) {
                        ScriptableObject.putProperty(scope, "searchKey", key);
                        ScriptableObject.putProperty(scope, "searchPage", page);
                    }
                }
                Object result = serialized;
                if (htmlList) {
                    result = elements(context, scope, (Elements) input);
                } else if (input instanceof JSONObject || input instanceof JSONArray) {
                    try {
                        result = new JsonParser(context, scope).parseValue(serialized);
                    } catch (JsonParser.ParseException invalid) {
                        throw Context.reportRuntimeError("无效的图源输入数据");
                    }
                }
                ScriptableObject.putProperty(scope, "result", result);
                installDom(context, scope, baseUrl);
                if (variables != null) {
                    installYiciyuanVariables(context, scope, variables);
                } else if (state != null) {
                    installLegado(context, scope, state, baseUrl, lookup);
                    if (!library.isEmpty()) {
                        Script shared;
                        try {
                            shared = context.compileString(library, "来源脚本库", 1, null);
                        } catch (EvaluatorException invalid) {
                            throw syntaxFailure("来源脚本库语法有误", invalid);
                        }
                        shared.exec(context, scope);
                    }
                }
                Script compiled;
                try {
                    compiled = context.compileString(code, "图源脚本", 1, null);
                } catch (EvaluatorException syntax) {
                    // Some rules are written as function bodies with explicit return.
                    try {
                        compiled = context.compileString("(function(){\n" + code + "\n})()", "图源脚本", 1, null);
                    } catch (EvaluatorException invalid) {
                        throw syntaxFailure("来源脚本语法有误", invalid);
                    }
                }
                Object value = compiled.exec(context, scope);
                return unwrap(value, limits(context), 0);
            });
        } catch (ScriptIo unavailable) {
            throw unavailable.failure;
        } catch (LimitReached timeout) {
            throw new IOException("来源脚本超过运行或结果大小限制");
        } catch (RuntimeException | StackOverflowError invalid) {
            if (invalid.getCause() instanceof IOException failure) throw failure;
            throw new IOException("来源脚本无法解析，可能需要该阅读器的专用接口", invalid);
        }
    }

    private static ScriptIo syntaxFailure(String message, EvaluatorException invalid) {
        ReaderSources.RuleFailure failure = new ReaderSources.RuleFailure(message);
        failure.initCause(invalid);
        return new ScriptIo(failure);
    }

    private static void installYiciyuanVariables(Context context, ScriptableObject scope, MangaVariables variables) {
        Scriptable java = context.newObject(scope);
        function(scope, java, "get", args -> {
            if (args.length != 1) throw new IOException("图源脚本的变量读取需要一个名称");
            return variables.values.getOrDefault(argument(args, 0), "");
        });
        function(scope, java, "put", args -> {
            if (args.length != 2) throw new IOException("图源脚本的变量写入需要名称和值");
            return variables.put(argument(args, 0), argument(args, 1));
        });
        ScriptableObject.putProperty(scope, "java", java);
    }

    static final class MangaVariables {
        private final Map<String, String> values = new HashMap<>();

        private String put(String key, String value) {
            if (key.length() > 128 || value.length() > 4096
                    || (!values.containsKey(key) && values.size() >= 64)) throw new LimitReached();
            values.put(key, value);
            return value;
        }
    }

    static final class LegadoScope {
        private final ReaderSources.Source source;
        private final String query;
        private final int page;
        private final Map<String, String> values = new HashMap<>();
        private Map<String, String> rowDefaults;
        private int defaultCharacters;
        private int characters, requests, responseCharacters;
        private long networkTime;
        private String library;

        LegadoScope(ReaderSources.Source source, String query, int page) {
            this.source = source;
            this.query = query;
            this.page = page;
        }

        String library() {
            if (library == null) {
                library = source.definition.optString("jsLib", "").trim();
                // Legado also accepts maps of external script downloads. Standalone rules still work.
                if (library.length() <= MAX_RULE && library.startsWith("{")) {
                    try {
                        JSONTokener candidate = new JSONTokener(library);
                        if (candidate.nextValue() instanceof JSONObject && candidate.nextClean() == 0) library = "";
                    } catch (JSONException | StackOverflowError inlineCode) {
                        // A JavaScript library may start with a block rather than a JSON object.
                    }
                }
            }
            return library;
        }

        String get(String key) { return values.getOrDefault(key, ""); }

        void beginRow() {
            if (rowDefaults == null) {
                rowDefaults = new HashMap<>(values);
                defaultCharacters = characters;
            } else {
                values.clear();
                values.putAll(rowDefaults);
                characters = defaultCharacters;
            }
        }

        String put(String key, String value) throws IOException {
            if (key.length() > 256 || (!values.containsKey(key) && values.size() >= 128)) {
                throw new IOException("来源脚本变量数量或名称过长");
            }
            String previous = values.getOrDefault(key, "");
            int count = characters - previous.length() + value.length();
            if (count > 192_000) throw new IOException("来源脚本变量总大小过长");
            characters = count;
            values.put(key, value);
            return value;
        }

        long networkTime() { return networkTime; }

        ReaderSources.Response request(HttpRequest operation) throws IOException {
            if (++requests > MAX_REQUESTS) throw new LimitReached();
            long started = System.nanoTime();
            try {
                ReaderSources.Response response = operation.run();
                responseCharacters += response.body.length();
                if (responseCharacters > MAX_INPUT) throw new LimitReached();
                return response;
            } finally {
                long duration = System.nanoTime() - started;
                networkTime += duration;
                limits(Context.getCurrentContext()).networkTime += duration;
            }
        }
    }

    private static void installLegado(Context context, ScriptableObject scope, LegadoScope state, String base,
                                      FieldLookup lookup) {
        Scriptable source = context.newObject(scope);
        ScriptableObject.putProperty(source, "key", state.source.url);
        ScriptableObject.putProperty(source, "bookSourceName", state.source.name);
        ScriptableObject.putProperty(source, "bookSourceUrl", state.source.url);
        ScriptableObject.defineProperty(source, "bookSourceComment", state.source.definition.optString("bookSourceComment", ""),
                ScriptableObject.READONLY | ScriptableObject.PERMANENT);
        function(scope, source, "getKey", args -> state.source.url);
        function(scope, source, "getTag", args -> state.source.name);
        function(scope, source, "getVariable", args -> state.source.definition.optString("sourceVariable", ""));
        ScriptableObject.putProperty(scope, "source", source);
        if (lookup != null) {
            Scriptable book = context.newObject(scope);
            ScriptableObject.putProperty(book, "origin", state.source.url);
            ScriptableObject.putProperty(book, "originName", state.source.name);
            ScriptableObject.putProperty(scope, "book", book);
        }

        Scriptable java = context.newObject(scope);
        function(scope, java, "get", args -> {
            if (args.length <= 1) return state.get(argument(args, 0));
            String url = argument(args, 0);
            if (url.length() > 16_384) throw new LimitReached();
            Map<String, String> headers = requestHeaders(args[1]);
            ReaderSources.Response response = state.request(() -> ReaderEngine.scriptGet(state.source, base, url, headers));
            return responseView(context, scope, response, false);
        });
        function(scope, java, "put", args -> state.put(argument(args, 0), argument(args, 1)));
        if (lookup != null) function(scope, java, "getString", args -> lookup.read(argument(args, 0)));
        function(scope, java, "ajax", args -> {
            Object address = args.length == 0 ? "" : args[0];
            if (address instanceof NativeArray) address = ScriptableObject.getProperty((NativeArray) address, 0);
            String declaration = Context.toString(address);
            return state.request(() -> ReaderEngine.scriptAjax(state.source, base, declaration,
                    state.query, state.page)).body;
        });
        function(scope, java, "connect", args -> {
            String declaration = argument(args, 0);
            Map<String, String> headers = args.length > 1 ? requestHeaders(args[1]) : new LinkedHashMap<>();
            ReaderSources.Response response = state.request(() -> ReaderEngine.scriptConnect(state.source, base,
                    declaration, state.query, state.page, headers));
            return responseView(context, scope, response, true);
        });
        function(scope, java, "post", args -> {
            String url = argument(args, 0);
            String body = argument(args, 1);
            if (url.length() > 16_384 || body.length() > 65_536) throw new LimitReached();
            Map<String, String> headers = args.length > 2 ? requestHeaders(args[2]) : new LinkedHashMap<>();
            ReaderSources.Response response = state.request(() -> ReaderEngine.scriptPost(state.source, base,
                    url, body, headers));
            return responseView(context, scope, response, false);
        });
        function(scope, java, "encodeURI", args -> {
            try {
                return URLEncoder.encode(argument(args, 0), charset(args, 1).name());
            } catch (IOException | IllegalArgumentException invalid) {
                return "";
            }
        });
        function(scope, java, "md5Encode", args -> md5(argument(args, 0)));
        function(scope, java, "md5Encode16", args -> md5(argument(args, 0)).substring(8, 24));
        function(scope, java, "base64Encode", args -> Base64.getEncoder()
                .encodeToString(argument(args, 0).getBytes(StandardCharsets.UTF_8)));
        function(scope, java, "base64Decode", args -> {
            try {
                return new String(Base64.getMimeDecoder().decode(argument(args, 0)), charset(args, 1));
            } catch (IllegalArgumentException invalid) {
                return "";
            }
        });
        function(scope, java, "log", args -> Undefined.instance);
        function(scope, java, "toast", args -> Undefined.instance);
        function(scope, java, "longToast", args -> Undefined.instance);
        function(scope, java, "startBrowserAwait", args -> verificationRequired());
        function(scope, java, "getVerificationCode", args -> verificationRequired());
        ScriptableObject.putProperty(scope, "java", java);
        Scriptable cookie = context.newObject(scope);
        function(scope, cookie, "removeCookie", args -> {
            String url = argument(args, 0);
            if (url.length() > 16_384) throw new LimitReached();
            try {
                ReaderEngine.clearScriptSession(state.source, base, url);
            } catch (IOException rejected) {
                RuntimeException scriptError = ScriptRuntime.typeError(rejected.getMessage());
                scriptError.initCause(rejected);
                throw scriptError;
            }
            return Undefined.instance;
        });
        ScriptableObject.putProperty(scope, "cookie", cookie);
    }

    private static Scriptable responseView(Context context, Scriptable scope, ReaderSources.Response response,
                                           boolean exposeRequest) {
        Scriptable result = context.newObject(scope);
        function(scope, result, "body", ignored -> response.body);
        function(scope, result, "url", ignored -> response.url);
        function(scope, result, "header", names -> responseHeader(response, argument(names, 0)));
        function(scope, result, "headers", names -> {
            String value = responseHeader(response, argument(names, 0));
            return context.newArray(scope, value == null ? new Object[0] : new Object[]{value});
        });
        if (exposeRequest) {
            Scriptable raw = context.newObject(scope);
            Scriptable request = context.newObject(scope);
            function(scope, request, "url", ignored -> response.url);
            function(scope, raw, "request", ignored -> request);
            function(scope, result, "raw", ignored -> raw);
        }
        return result;
    }

    private static String responseHeader(ReaderSources.Response response, String name) {
        if (name.equalsIgnoreCase("Location") && !response.location.isEmpty()) return response.location;
        if (name.equalsIgnoreCase("Content-Type") && !response.mime.isEmpty()) return response.mime;
        return null;
    }

    private static Object verificationRequired() throws IOException {
        throw new ReaderSources.SiteFailure("此来源需要在网站完成登录或人工验证");
    }

    private static Map<String, String> requestHeaders(Object value) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        if (value instanceof CharSequence) {
            String text = value.toString();
            if (text.length() > 64 * 1024) throw new LimitReached();
            if (text.trim().isEmpty()) return headers;
            try {
                JSONObject object = new JSONObject(text);
                if (object.length() > 32) throw new LimitReached();
                for (java.util.Iterator<String> keys = object.keys(); keys.hasNext(); ) {
                    String name = keys.next();
                    addScriptHeader(headers, name, object.optString(name, ""));
                }
                return headers;
            } catch (JSONException malformed) {
                throw new IOException("来源脚本的请求头格式错误", malformed);
            }
        }
        if (!(value instanceof Scriptable)) return headers;
        Scriptable object = (Scriptable) value;
        Object[] keys = object.getIds();
        if (keys.length > 32) throw new LimitReached();
        for (Object key : keys) {
            if (!(key instanceof String)) continue;
            String name = (String) key;
            String text = Context.toString(ScriptableObject.getProperty(object, name));
            addScriptHeader(headers, name, text);
        }
        return headers;
    }

    private static void addScriptHeader(Map<String, String> headers, String name, String value) {
        if (name.length() > 80 || value.length() > 8_192) throw new LimitReached();
        headers.put(name, value);
    }

    private static Charset charset(Object[] args, int index) {
        return index < args.length && args[index] instanceof CharSequence
                ? Charset.forName(argument(args, index)) : StandardCharsets.UTF_8;
    }

    private static String md5(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (byte item : digest) {
                hex.append(Character.forDigit((item >> 4) & 15, 16));
                hex.append(Character.forDigit(item & 15, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    private static void installDom(Context context, ScriptableObject scope, String baseUrl) {
        Scriptable org = context.newObject(scope);
        Scriptable jsoup = context.newObject(scope);
        Scriptable api = context.newObject(scope);
        function(scope, api, "parse", args -> {
            Limits limits = limits(Context.getCurrentContext());
            limits.domCall();
            String html = argument(args, 0);
            if (html.length() > MAX_INPUT) throw new LimitReached();
            return element(Context.getCurrentContext(), scope, Jsoup.parse(html, baseUrl));
        });
        ScriptableObject.putProperty(jsoup, "Jsoup", api);
        ScriptableObject.putProperty(org, "jsoup", jsoup);
        ScriptableObject.putProperty(scope, "org", org);
    }

    private static Scriptable element(Context context, Scriptable scope, Element value) {
        ScriptableObject wrapper = new ScriptableObject() {
            @Override public String getClassName() { return "HtmlElement"; }
            @Override public Object getDefaultValue(Class<?> hint) {
                limits(Context.getCurrentContext()).domCall();
                return value.outerHtml();
            }
        };
        wrapper.setParentScope(scope);
        wrapper.setPrototype(ScriptableObject.getObjectPrototype(scope));
        function(scope, wrapper, "select", args -> {
            String selector = selector(args);
            return elements(Context.getCurrentContext(), scope, value.select(selector));
        });
        function(scope, wrapper, "selectFirst", args -> {
            Element match = value.selectFirst(selector(args));
            return match == null ? null : element(Context.getCurrentContext(), scope, match);
        });
        function(scope, wrapper, "attr", args -> value.attr(argument(args, 0)));
        function(scope, wrapper, "text", args -> value.text());
        function(scope, wrapper, "html", args -> value.html());
        return wrapper;
    }

    private static NativeArray elements(Context context, Scriptable scope, Elements selected) {
        Limits limits = limits(context);
        limits.nodes += selected.size();
        limits.check();
        if (selected.size() > MAX_ELEMENTS) throw new LimitReached();
        Object[] values = new Object[selected.size()];
        for (int i = 0; i < values.length; i++) values[i] = element(context, scope, selected.get(i));
        NativeArray list = (NativeArray) context.newArray(scope, values);
        function(scope, list, "toArray", args -> list);
        function(scope, list, "size", args -> values.length);
        function(scope, list, "first", args -> values.length == 0 ? null : values[0]);
        function(scope, list, "last", args -> values.length == 0 ? null : values[values.length - 1]);
        function(scope, list, "get", args -> {
            int index = args.length == 0 ? -1 : (int) Context.toNumber(args[0]);
            return index < 0 || index >= values.length ? null : values[index];
        });
        function(scope, list, "text", args -> selected.text());
        function(scope, list, "attr", args -> selected.attr(argument(args, 0)));
        return list;
    }

    private static String argument(Object[] arguments, int index) {
        return index >= arguments.length ? "" : Context.toString(arguments[index]);
    }

    private static String selector(Object[] arguments) {
        String selector = argument(arguments, 0);
        if (selector.isEmpty() || selector.length() > 1_024) {
            throw Context.reportRuntimeError("无效的 HTML 选择器");
        }
        return selector;
    }

    private interface Operation { Object run(Object[] args) throws IOException; }

    interface FieldLookup { String read(String rule) throws IOException; }

    private interface HttpRequest { ReaderSources.Response run() throws IOException; }

    private static void function(Scriptable scope, Scriptable target, String name, Operation operation) {
        BaseFunction fn = new BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
            @Override public Object call(Context context, Scriptable receiverScope, Scriptable self, Object[] args) {
                limits(context).domCall();
                try {
                    return operation.run(args);
                } catch (IOException failure) {
                    throw new ScriptIo(failure);
                }
            }
        };
        ScriptableObject.putProperty(target, name, fn);
    }

    private static Object unwrap(Object value, Limits limits, int depth) {
        limits.nodes++;
        limits.check();
        if (depth > MAX_DEPTH) throw new LimitReached();
        if (value == null || value == Undefined.instance || value == Scriptable.NOT_FOUND) return null;
        if (value instanceof CharSequence) {
            String string = value.toString();
            limits.characters += string.length();
            limits.check();
            if (depth == 0 && (string.trim().startsWith("[") || string.trim().startsWith("{"))) {
                try {
                    JSONTokener json = new JSONTokener(string);
                    Object decoded = json.nextValue();
                    if (json.nextClean() == 0 && (decoded instanceof JSONObject || decoded instanceof JSONArray)) {
                        return decoded;
                    }
                } catch (JSONException | StackOverflowError ignored) {
                    // A plain string can start with a bracket.
                }
            }
            return string;
        }
        if (value instanceof Boolean) return value;
        if (value instanceof Number) return Double.isFinite(((Number) value).doubleValue()) ? value : null;
        try {
            if (value instanceof NativeArray) {
                NativeArray list = (NativeArray) value;
                if (list.getLength() > MAX_ELEMENTS) throw new LimitReached();
                JSONArray array = new JSONArray();
                for (int i = 0; i < list.getLength(); i++) {
                    array.put(unwrap(ScriptableObject.getProperty(list, i), limits, depth + 1));
                }
                return array;
            }
            if (value instanceof Scriptable && "HtmlElement".equals(((Scriptable) value).getClassName())) {
                return unwrap(Context.toString(value), limits, depth + 1);
            }
            if (value instanceof Scriptable && "Object".equals(((Scriptable) value).getClassName())) {
                Scriptable record = (Scriptable) value;
                Object[] keys = record.getIds();
                if (keys.length > MAX_ELEMENTS) throw new LimitReached();
                JSONObject object = new JSONObject();
                for (Object key : keys) {
                    String name = Context.toString(key);
                    Object item = key instanceof Number
                            ? ScriptableObject.getProperty(record, ((Number) key).intValue())
                            : ScriptableObject.getProperty(record, name);
                    object.put(name, unwrap(item, limits, depth + 1));
                }
                return object;
            }
        } catch (JSONException invalid) {
            throw Context.reportRuntimeError("图源脚本返回了无效数据");
        }
        return null;
    }

    private static Limits limits(Context context) {
        return (Limits) context.getThreadLocal(BUDGET);
    }

    private static final class Limits {
        final long start = System.nanoTime();
        int instructions, nodes, characters, calls;
        long networkTime;

        void domCall() {
            calls++;
            check();
        }

        void check() {
            if (instructions > MAX_INSTRUCTIONS || nodes > MAX_NODES || characters > MAX_RETURN
                    || calls > MAX_DOM_CALLS || Thread.currentThread().isInterrupted()
                    || System.nanoTime() - start - networkTime > MAX_TIME) {
                throw new LimitReached();
            }
        }
    }

    private static final class LimitReached extends Error {}

    private static final class ScriptIo extends RuntimeException {
        final IOException failure;

        ScriptIo(IOException failure) { this.failure = failure; }
    }
}
