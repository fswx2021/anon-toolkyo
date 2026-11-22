package com.shengjian.player;

import com.sun.jna.Callback;
import com.sun.jna.CallbackThreadInitializer;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Java binding for the MIT ech_http 0.2.4 native client used in Kazumi 2.4.4. */
final class KazumiEchClient {
    private static final int HEADERS = 1, BODY = 2, COMPLETE = 3, ERROR = 4;
    private static final int DART_INT32 = 2, DART_ARRAY = 6, DART_TYPED_DATA = 7;
    private static final int DART_VALUE_OFFSET = 8;
    private static final int MAX_EVENT_BYTES = 256 * 1024;
    private static final AtomicLong NEXT_PORT = new AtomicLong(1);
    private static final Map<Long, BlockingQueue<Event>> REQUESTS = new ConcurrentHashMap<>();
    // Kept alive for the process lifetime, including callbacks from cancelled workers.
    private static final PostEvent POST = KazumiEchClient::post;

    record Result(int status, String headers, byte[] body) {}
    private record Event(int type, int code, boolean encrypted, byte[] bytes) {}

    public interface PostEvent extends Callback {
        byte invoke(long port, Pointer object);
    }

    public interface EchLibrary extends Library {
        Pointer eh_client_create();
        Pointer eh_request_start(Pointer client, Options options, PostEvent callback, long port);
        void eh_request_acknowledge(Pointer request, long bytes);
        void eh_request_destroy(Pointer request);
    }

    @Structure.FieldOrder({"url", "method", "headers", "proxy", "echConfig", "connectIp",
            "caPem", "body", "bodyLength", "timeoutMs", "connectTimeoutMs", "maxResponseBytes", "autoUncompress"})
    public static final class Options extends Structure {
        public String url, method, headers, proxy, echConfig, connectIp, caPem;
        public Pointer body;
        public long bodyLength, timeoutMs, connectTimeoutMs, maxResponseBytes;
        public byte autoUncompress = 1;
    }

    private record Client(EchLibrary library, Pointer handle) {
        static Client load() {
            try {
                if (Native.POINTER_SIZE != 8 || new Options().size() != 104) return null;
                EchLibrary library = Native.load("ech_http", EchLibrary.class,
                        Map.of(Library.OPTION_STRING_ENCODING, "UTF-8"));
                Pointer handle = library.eh_client_create();
                if (handle == null) return null;
                Native.setCallbackThreadInitializer(POST, new CallbackThreadInitializer(true, true, "bangumi-ech"));
                return new Client(library, handle);
            } catch (RuntimeException | LinkageError unavailable) {
                return null;
            }
        }
    }

    private static final class NativeClient {
        static final Client INSTANCE = Client.load();
    }

    private KazumiEchClient() {}

    static boolean available() {
        return NativeClient.INSTANCE != null;
    }

    static Result fetch(String url, String headers, String postBody, String config, String ip,
                        int timeoutMs, int maxBytes) throws IOException {
        Client client = NativeClient.INSTANCE;
        if (client == null) throw new IOException("ECH 组件不可用");
        Options options = new Options();
        options.url = url;
        options.method = postBody == null ? "GET" : "POST";
        options.headers = headers;
        options.echConfig = config;
        options.connectIp = ip;
        options.timeoutMs = timeoutMs;
        options.connectTimeoutMs = Math.min(timeoutMs, 3_000);
        options.maxResponseBytes = maxBytes;
        byte[] payload = postBody == null ? new byte[0] : postBody.getBytes(StandardCharsets.UTF_8);
        try (Memory body = payload.length == 0 ? null : new Memory(payload.length)) {
            if (body != null) body.write(0, payload, 0, payload.length);
            options.body = body;
            options.bodyLength = payload.length;
            return exchange(client, options, maxBytes);
        }
    }

    private static Result exchange(Client client, Options options, int maxBytes) throws IOException {
        long port = NEXT_PORT.getAndIncrement();
        BlockingQueue<Event> events = new ArrayBlockingQueue<>(1024);
        REQUESTS.put(port, events);
        Pointer request = null;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(options.timeoutMs + 250);
        try {
            request = client.library.eh_request_start(client.handle, options, POST, port);
            if (request == null) throw new IOException("无法启动 ECH 请求");
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            int status = 0;
            String headers = "";
            while (true) {
                Event event = events.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (event == null) throw new SocketTimeoutException("Bangumi ECH 连接超时");
                switch (event.type) {
                    case HEADERS -> {
                        if (!event.encrypted) throw new IOException("服务器未接受 ECH");
                        status = event.code;
                        headers = new String(event.bytes, StandardCharsets.ISO_8859_1);
                    }
                    case BODY -> {
                        if (event.bytes.length > maxBytes - body.size()) throw new IOException("Bangumi 响应过长");
                        body.write(event.bytes, 0, event.bytes.length);
                        client.library.eh_request_acknowledge(request, event.bytes.length);
                    }
                    case COMPLETE -> {
                        if (status == 0) throw new IOException("Bangumi 响应缺少状态");
                        return new Result(status, headers, body.toByteArray());
                    }
                    case ERROR -> {
                        if (event.code == 28) throw new SocketTimeoutException("Bangumi ECH 连接超时");
                        throw new IOException("Bangumi ECH 连接失败（" + event.code + "）");
                    }
                    default -> throw new IOException("Bangumi ECH 返回未知事件");
                }
            }
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Bangumi 请求已取消");
        } finally {
            REQUESTS.remove(port);
            if (request != null) client.library.eh_request_destroy(request);
        }
    }

    private static byte post(long port, Pointer object) {
        BlockingQueue<Event> events = REQUESTS.get(port);
        if (events == null) return 0;
        // Dart_CObject's public LP64 ABI. Pointers and payloads belong to the native worker
        // and only remain valid until this callback returns. Acknowledgement happens off-callback.
        if (object == null || object.getInt(0) != DART_ARRAY || object.getLong(DART_VALUE_OFFSET) != 5) return 0;
        Pointer elements = object.getPointer(16);
        if (elements == null) return 0;
        int type = integerAt(elements, 0), code = integerAt(elements, 1), encrypted = integerAt(elements, 2);
        Pointer data = elements.getPointer(32);
        if (type < HEADERS || type > ERROR || data == null || data.getInt(0) != DART_TYPED_DATA) return 0;
        long size = data.getLong(16);
        if (size < 0 || size > MAX_EVENT_BYTES) return 0;
        Pointer bytes = data.getPointer(24);
        if (size > 0 && bytes == null) return 0;
        byte[] copied = size == 0 ? new byte[0] : bytes.getByteArray(0, (int) size);
        return (byte) (events.offer(new Event(type, code, encrypted == 1, copied)) ? 1 : 0);
    }

    private static int integerAt(Pointer elements, int index) {
        Pointer field = elements.getPointer(8L * index);
        return field != null && field.getInt(0) == DART_INT32 ? field.getInt(DART_VALUE_OFFSET) : -1;
    }
}
