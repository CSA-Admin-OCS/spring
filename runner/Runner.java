import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class Runner {
    private static final int PORT = 8591;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_REQUEST_BYTES = 1_000_000;

    private Runner() {
    }

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", PORT), 0);
        server.createContext("/python", Runner::handlePython);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.printf("** Server running: http://localhost:%d%n", PORT);
    }

    private static void handlePython(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                sendJson(exchange, 405, "Method not allowed.");
                return;
            }

            String requestBody;
            try {
                requestBody = readBody(exchange.getRequestBody());
            } catch (RequestTooLargeException exception) {
                sendJson(exchange, 413, exception.getMessage());
                return;
            }

            String code;
            try {
                code = extractCode(requestBody);
            } catch (IllegalArgumentException exception) {
                sendJson(exchange, 400, "Invalid JSON request.");
                return;
            }

            if (code == null || code.isBlank()) {
                sendJson(exchange, 400, "No code provided.");
                return;
            }

            sendJson(exchange, 200, executePython(code));
        }
    }

    private static String executePython(String code) {
        Path script = null;
        try {
            Path temporaryDirectory = Path.of(System.getProperty("java.io.tmpdir"));
            script = Files.createTempFile(temporaryDirectory, "runner-", ".py");
            Files.writeString(script, code, StandardCharsets.UTF_8);

            ProcessBuilder builder = new ProcessBuilder("python3", script.toString());
            builder.directory(temporaryDirectory.toFile());
            builder.redirectErrorStream(true);
            Map<String, String> environment = builder.environment();
            environment.clear();
            environment.put("HOME", temporaryDirectory.toString());
            environment.put("PATH", "/usr/bin:/usr/local/bin");

            Process process = builder.start();
            CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
                try {
                    return process.getInputStream().readAllBytes();
                } catch (IOException exception) {
                    throw new CompletionException(exception);
                }
            });
            boolean completed = process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
                return "Execution timed out (5 s limit).";
            }
            return new String(output.join(), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "Error running code: " + exception.getMessage();
        } finally {
            if (script != null) {
                try {
                    Files.deleteIfExists(script);
                } catch (IOException ignored) {
                    // The OS will clean /tmp; execution output has already been produced.
                }
            }
        }
    }

    private static String readBody(InputStream input) throws IOException, RequestTooLargeException {
        byte[] buffer = new byte[8192];
        int total = 0;
        List<byte[]> chunks = new ArrayList<>();
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_REQUEST_BYTES) {
                throw new RequestTooLargeException("Request body exceeds 1 MB.");
            }
            chunks.add(java.util.Arrays.copyOf(buffer, read));
        }
        byte[] body = new byte[total];
        int position = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, body, position, chunk.length);
            position += chunk.length;
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    // Parses the one-field JSON contract without adding a runtime dependency.
    private static String extractCode(String json) {
        JsonCursor cursor = new JsonCursor(json);
        cursor.skipWhitespace();
        cursor.expect('{');
        String code = null;
        cursor.skipWhitespace();
        while (!cursor.consume('}')) {
            String key = cursor.readString();
            cursor.skipWhitespace();
            cursor.expect(':');
            cursor.skipWhitespace();
            if ("code".equals(key)) {
                code = cursor.consumeNull() ? null : cursor.readString();
            } else {
                cursor.skipValue();
            }
            cursor.skipWhitespace();
            if (cursor.consume('}')) {
                break;
            }
            cursor.expect(',');
            cursor.skipWhitespace();
        }
        cursor.skipWhitespace();
        if (!cursor.atEnd()) {
            throw new IllegalArgumentException("Trailing JSON data");
        }
        return code;
    }

    private static void sendJson(HttpExchange exchange, int status, String output) throws IOException {
        byte[] response = ("{\"output\":\"" + escapeJson(output) + "\"}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, response.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(response);
        }
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static final class RequestTooLargeException extends Exception {
        private RequestTooLargeException(String message) {
            super(message);
        }
    }

    private static final class JsonCursor {
        private final String source;
        private int index;

        private JsonCursor(String source) {
            this.source = source;
        }

        private boolean atEnd() {
            return index == source.length();
        }

        private void skipWhitespace() {
            while (index < source.length() && Character.isWhitespace(source.charAt(index))) index++;
        }

        private void expect(char expected) {
            if (!consume(expected)) throw new IllegalArgumentException("Expected " + expected);
        }

        private boolean consume(char expected) {
            if (index < source.length() && source.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private boolean consumeNull() {
            if (source.startsWith("null", index)) {
                index += 4;
                return true;
            }
            return false;
        }

        private String readString() {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (index < source.length()) {
                char character = source.charAt(index++);
                if (character == '"') return value.toString();
                if (character != '\\') {
                    if (character < 0x20) throw new IllegalArgumentException("Control character");
                    value.append(character);
                    continue;
                }
                if (index >= source.length()) throw new IllegalArgumentException("Bad escape");
                char escaped = source.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> value.append(escaped);
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'u' -> {
                        if (index + 4 > source.length()) throw new IllegalArgumentException("Bad unicode escape");
                        value.append((char) Integer.parseInt(source.substring(index, index + 4), 16));
                        index += 4;
                    }
                    default -> throw new IllegalArgumentException("Bad escape");
                }
            }
            throw new IllegalArgumentException("Unterminated string");
        }

        private void skipValue() {
            if (index >= source.length()) throw new IllegalArgumentException("Missing value");
            if (source.charAt(index) == '"') {
                readString();
                return;
            }
            int nesting = 0;
            boolean inString = false;
            boolean escaped = false;
            while (index < source.length()) {
                char character = source.charAt(index);
                if (inString) {
                    index++;
                    if (escaped) escaped = false;
                    else if (character == '\\') escaped = true;
                    else if (character == '"') inString = false;
                } else if (character == '"') {
                    inString = true;
                    index++;
                } else if (character == '{' || character == '[') {
                    nesting++;
                    index++;
                } else if (character == '}' || character == ']') {
                    if (nesting == 0) return;
                    nesting--;
                    index++;
                } else if (character == ',' && nesting == 0) {
                    return;
                } else {
                    index++;
                }
            }
        }
    }
}
