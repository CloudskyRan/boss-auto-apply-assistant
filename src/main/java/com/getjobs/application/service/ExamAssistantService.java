package com.getjobs.application.service;

import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Explicitly submitted practice questions; independent from the Boss delivery AI workflow. */
@Service
public class ExamAssistantService {
    public static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    public static final int MAX_QUESTION_CHARS = 20_000;
    public static final int MAX_IMAGE_SIDE = 4096;
    public static final long MAX_IMAGE_PIXELS = 12_000_000;
    private static final Duration MODEL_TIMEOUT = Duration.ofSeconds(75);
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final String INSTRUCTIONS = "你是觅途的练习题学习助手。请先识别题型，给出答案和可核验的推导或解题思路。"
            + "选择题说明选项；代码题说明算法、复杂度和边界条件。题干不完整或文字疑似识别错误时，"
            + "明确指出需要补充的信息，不猜测缺失条件。只使用本次题目，不需要个人简历或求职招呼语。";
    private final ConfigService configService;
    private final HttpClient httpClient;
    private final Semaphore ocrRequests = new Semaphore(1);
    private final Semaphore solveRequests = new Semaphore(2);

    public ExamAssistantService(ConfigService configService) {
        this.configService = configService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Reads a caller-owned image; the caller remains responsible for removing that image. */
    public String recognizeImage(Path imagePath) {
        validateImage(imagePath);
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            throw failure(HttpStatus.NOT_IMPLEMENTED, "本地图片识别目前需要 Windows 系统。");
        }
        if (!ocrRequests.tryAcquire()) {
            throw failure(HttpStatus.TOO_MANY_REQUESTS, "正在识别另一张图片，请稍后重试。");
        }
        Path temporaryDirectory = null;
        Process process = null;
        try {
            String systemRoot = System.getenv("SystemRoot");
            if (systemRoot == null || systemRoot.isBlank()) {
                throw failure(HttpStatus.SERVICE_UNAVAILABLE, "未找到 Windows PowerShell，本地识别暂不可用。");
            }
            Path powershell = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
            if (!Files.isRegularFile(powershell)) {
                throw failure(HttpStatus.SERVICE_UNAVAILABLE, "未找到 Windows PowerShell，本地识别暂不可用。");
            }
            temporaryDirectory = Files.createTempDirectory("mitu-ocr-");
            Path script = temporaryDirectory.resolve("recognize-image.ps1");
            Path result = temporaryDirectory.resolve("result.json");
            try (InputStream source = getClass().getResourceAsStream("/desktop/recognize-image.ps1")) {
                if (source == null) {
                    throw failure(HttpStatus.SERVICE_UNAVAILABLE, "本地识别组件缺失，请重新构建桌面组件。");
                }
                Files.copy(source, script);
            }
            // Fixed code and environment arguments avoid quoting image paths into executable PowerShell.
            String loader = "$ErrorActionPreference='Stop'; try { "
                    + "$s=[IO.File]::ReadAllText($env:MITU_OCR_SCRIPT,[Text.Encoding]::UTF8); "
                    + "& ([ScriptBlock]::Create($s)) -ImagePath $env:MITU_OCR_IMAGE -OutputPath $env:MITU_OCR_OUTPUT "
                    + "} catch { exit 1 }";
            String encodedLoader = Base64.getEncoder().encodeToString(loader.getBytes(StandardCharsets.UTF_16LE));
            ProcessBuilder builder = new ProcessBuilder(powershell.toString(), "-NoLogo", "-NoProfile",
                    "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encodedLoader);
            builder.environment().put("MITU_OCR_SCRIPT", script.toString());
            builder.environment().put("MITU_OCR_IMAGE", imagePath.toAbsolutePath().toString());
            builder.environment().put("MITU_OCR_OUTPUT", result.toString());
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                throw failure(HttpStatus.GATEWAY_TIMEOUT, "图片识别超时，请裁剪为单道题目后重试。");
            }
            if (!Files.isRegularFile(result) || Files.size(result) > 256 * 1024) {
                throw failure(HttpStatus.SERVICE_UNAVAILABLE, "本地图片识别失败，请检查 Windows OCR 语言组件。");
            }
            JSONObject output = new JSONObject(Files.readString(result, StandardCharsets.UTF_8));
            if (process.exitValue() != 0 || output.has("error")) {
                String code = output.optString("error");
                if ("OCR_LANGUAGE_UNAVAILABLE".equals(code)) {
                    throw failure(HttpStatus.SERVICE_UNAVAILABLE, "Windows 尚未安装可用的 OCR 语言，请安装中文或英文语言组件。");
                }
                throw failure(HttpStatus.SERVICE_UNAVAILABLE, "本地图片识别失败，请换用清晰的 PNG 或 JPEG 图片。");
            }
            String text = output.optString("text", "").strip();
            if (text.isEmpty()) {
                throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "图片中没有识别到文字，请裁剪题目并提高图片清晰度。");
            }
            if (text.length() > MAX_QUESTION_CHARS) {
                throw failure(HttpStatus.PAYLOAD_TOO_LARGE, "识别文字超过 20,000 字符，请裁剪为单道题目。");
            }
            return text;
        } catch (ExamAssistantException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, "图片识别已中断，请稍后重试。");
        } catch (Exception e) {
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, "本地图片识别失败，请换用清晰的 PNG 或 JPEG 图片。");
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                boolean interrupted = Thread.interrupted();
                try {
                    process.waitFor(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                } finally {
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }
            removeTemporaryFiles(temporaryDirectory);
            ocrRequests.release();
        }
    }

    public String solve(String question) {
        if (question == null || question.isBlank()) {
            throw failure(HttpStatus.BAD_REQUEST, "请先填写或识别题目。");
        }
        if (question.length() > MAX_QUESTION_CHARS) {
            throw failure(HttpStatus.PAYLOAD_TOO_LARGE, "题目最多 20,000 字符，请缩短后重试。");
        }
        if (!solveRequests.tryAcquire()) {
            throw failure(HttpStatus.TOO_MANY_REQUESTS, "解析请求较多，请等待当前解析结束后重试。");
        }
        try {
            Map<String, String> config;
            try {
                config = configService.getAiConfigs();
            } catch (RuntimeException e) {
                throw failure(HttpStatus.BAD_REQUEST, "请先在系统设置填写 AI 服务地址、模型名称和 API Key。");
            }
            String baseUrl = requiredConfiguration(config, "BASE_URL");
            String apiKey = requiredConfiguration(config, "API_KEY");
            String model = requiredConfiguration(config, "MODEL");
            if (apiKey.length() > 8192 || apiKey.chars().anyMatch(character -> character < 32 || character == 127)) {
                throw failure(HttpStatus.BAD_REQUEST, "API Key 格式无效，请检查系统设置。");
            }
            if (model.length() > 256 || model.chars().anyMatch(Character::isISOControl)) {
                throw failure(HttpStatus.BAD_REQUEST, "模型名称格式无效，请检查系统设置。");
            }
            URI endpoint = buildEndpoint(baseUrl, model);
            JSONObject payload = new JSONObject().put("model", model).put("stream", false);
            if (endpoint.getPath().endsWith("/responses")) {
                payload.put("instructions", INSTRUCTIONS).put("input", question.strip());
            } else {
                payload.put("messages", new JSONArray()
                        .put(new JSONObject().put("role", "system").put("content", INSTRUCTIONS))
                        .put(new JSONObject().put("role", "user").put("content", question.strip())));
            }
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(MODEL_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                    .build();
            CompletableFuture<HttpResponse<String>> pending = httpClient.sendAsync(request, ignored -> new LimitedResponseBody());
            HttpResponse<String> response;
            try {
                // An explicit deadline also covers a server that sends headers and stalls its body.
                response = pending.get(MODEL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                pending.cancel(true);
                throw failure(HttpStatus.GATEWAY_TIMEOUT, "AI 解析超时，请缩短题目或稍后重试。");
            } catch (InterruptedException e) {
                pending.cancel(true);
                throw e;
            } catch (ExecutionException e) {
                if (e.getCause() instanceof HttpTimeoutException) {
                    throw failure(HttpStatus.GATEWAY_TIMEOUT, "AI 解析超时，请缩短题目或稍后重试。");
                }
                if (e.getCause() instanceof ExamAssistantException safeFailure) throw safeFailure;
                if (e.getCause() instanceof IOException ioFailure) throw ioFailure;
                throw failure(HttpStatus.BAD_GATEWAY, "AI 服务返回格式无效，请检查模型和服务配置。");
            }
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                if (status == 429) {
                    throw failure(HttpStatus.TOO_MANY_REQUESTS, "AI 服务请求额度或频率受限，请稍后重试。");
                }
                if (status == 401 || status == 403) {
                    throw failure(HttpStatus.BAD_GATEWAY, "AI 服务拒绝认证，请检查系统设置中的 API Key 和服务地址。");
                }
                throw failure(HttpStatus.BAD_GATEWAY, "AI 服务暂时无法完成解析（HTTP " + status + "），请检查模型配置后重试。");
            }
            String answer = extractAnswer(new JSONObject(response.body()));
            if (answer.isBlank()) {
                throw failure(HttpStatus.BAD_GATEWAY, "模型没有返回可用解析，请调整题目或模型后重试。");
            }
            return answer.strip();
        } catch (ExamAssistantException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            throw failure(HttpStatus.GATEWAY_TIMEOUT, "AI 解析超时，请缩短题目或稍后重试。");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, "AI 解析已中断，请稍后重试。");
        } catch (IOException e) {
            throw failure(HttpStatus.BAD_GATEWAY, "无法连接 AI 服务，请检查网络与系统设置中的服务地址。");
        } catch (Exception e) {
            throw failure(HttpStatus.BAD_GATEWAY, "AI 服务返回格式无效，请检查模型和服务配置。");
        } finally {
            solveRequests.release();
        }
    }

    private static String requiredConfiguration(Map<String, String> config, String key) {
        String value = config == null ? null : config.get(key);
        if (value == null || value.isBlank()) {
            throw failure(HttpStatus.BAD_REQUEST, "请先在系统设置填写 AI 服务地址、模型名称和 API Key。");
        }
        return value.strip();
    }

    private static URI buildEndpoint(String baseUrl, String model) {
        try {
            if (baseUrl.length() > 4096) throw new IllegalArgumentException();
            URI base = URI.create(baseUrl);
            if (!base.isAbsolute() || base.getHost() == null || base.getUserInfo() != null || base.getFragment() != null
                    || !("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))) {
                throw new IllegalArgumentException();
            }
            String path = base.getPath() == null ? "" : base.getPath().replaceAll("/+$", "");
            if (!path.endsWith("/responses") && !path.endsWith("/chat/completions")) {
                if (!path.endsWith("/v1")) path += "/v1";
                String normalizedModel = model.toLowerCase(Locale.ROOT);
                // Match the existing AI service's routing for saved configurations.
                boolean responses = normalizedModel.contains("o1") || normalizedModel.contains("o3") || normalizedModel.contains("o4")
                        || normalizedModel.contains("reasoner") || normalizedModel.contains("4.1")
                        || normalizedModel.contains("4o-mini");
                path += responses ? "/responses" : "/chat/completions";
            }
            return new URI(base.getScheme(), null, base.getHost(), base.getPort(), path, base.getQuery(), null);
        } catch (Exception e) {
            throw failure(HttpStatus.BAD_REQUEST, "AI 服务地址格式无效，请填写完整的 HTTP 或 HTTPS 地址。");
        }
    }

    private static String extractAnswer(JSONObject response) {
        Object direct = response.opt("output_text");
        if (direct instanceof String text && !text.isBlank()) return text;
        StringBuilder answer = new StringBuilder();
        JSONArray output = response.optJSONArray("output");
        if (output != null) {
            for (int i = 0; i < output.length(); i++) {
                JSONObject message = output.optJSONObject(i);
                if (message != null && "message".equals(message.optString("type", "message"))
                        && "assistant".equals(message.optString("role", "assistant"))) {
                    appendContent(answer, message.opt("content"));
                }
            }
        }
        if (!answer.isEmpty()) return answer.toString();
        JSONArray choices = response.optJSONArray("choices");
        if (choices != null && !choices.isEmpty()) {
            JSONObject choice = choices.optJSONObject(0);
            JSONObject message = choice == null ? null : choice.optJSONObject("message");
            if (message != null) appendContent(answer, message.opt("content"));
        }
        return answer.toString();
    }

    private static void appendContent(StringBuilder answer, Object content) {
        if (content instanceof String text) {
            answer.append(text);
        } else if (content instanceof JSONArray parts) {
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part != null && ("output_text".equals(part.optString("type")) || "text".equals(part.optString("type")))) {
                    Object text = part.opt("text");
                    if (text instanceof String value) answer.append(value);
                }
            }
        }
    }

    private static void validateImage(Path path) {
        try {
            if (path == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) == 0) {
                throw failure(HttpStatus.BAD_REQUEST, "请上传有效的 PNG 或 JPEG 图片。");
            }
            if (Files.size(path) > MAX_IMAGE_BYTES) {
                throw failure(HttpStatus.PAYLOAD_TOO_LARGE, "图片不能超过 5 MiB，请压缩或裁剪后重试。");
            }
            try (ImageInputStream input = ImageIO.createImageInputStream(path.toFile())) {
                if (input == null) throw failure(HttpStatus.BAD_REQUEST, "无法读取图片，请重新上传。");
                Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw failure(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "只支持 PNG 或 JPEG 图片。");
                ImageReader reader = readers.next();
                try {
                    reader.setInput(input, true, true);
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    if (!"png".equals(format) && !"jpeg".equals(format) && !"jpg".equals(format)) {
                        throw failure(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "只支持 PNG 或 JPEG 图片。");
                    }
                    int width = reader.getWidth(0);
                    int height = reader.getHeight(0);
                    if (width <= 0 || height <= 0 || width > MAX_IMAGE_SIDE || height > MAX_IMAGE_SIDE
                            || (long) width * height > MAX_IMAGE_PIXELS) {
                        throw failure(HttpStatus.PAYLOAD_TOO_LARGE, "图片单边不能超过 4096 像素、总像素不能超过 1200 万，请裁剪题目。");
                    }
                } finally {
                    reader.dispose();
                }
            }
        } catch (ExamAssistantException e) {
            throw e;
        } catch (Exception e) {
            throw failure(HttpStatus.BAD_REQUEST, "图片内容无效，请重新上传 PNG 或 JPEG 图片。");
        }
    }

    private static void removeTemporaryFiles(Path directory) {
        if (directory == null) return;
        // This directory was created for this call; only these fixed owned files may be removed.
        for (String name : new String[]{"result.json", "recognize-image.ps1"}) {
            try { Files.deleteIfExists(directory.resolve(name)); } catch (IOException ignored) { }
        }
        try { Files.deleteIfExists(directory); } catch (IOException ignored) { }
    }

    /** Keeps an untrusted model endpoint from buffering an unlimited response body. */
    private static final class LimitedResponseBody implements HttpResponse.BodySubscriber<String> {
        private final CompletableFuture<String> body = new CompletableFuture<>();
        private final List<byte[]> chunks = new ArrayList<>();
        private Flow.Subscription subscription;
        private int size;

        @Override
        public CompletableFuture<String> getBody() { return body; }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE_BYTES - size) {
                    subscription.cancel();
                    chunks.clear();
                    body.completeExceptionally(failure(HttpStatus.BAD_GATEWAY, "AI 服务返回内容过大，请缩短题目后重试。"));
                    return;
                }
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                chunks.add(bytes);
                size += bytes.length;
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable throwable) {
            chunks.clear();
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            byte[] bytes = new byte[size];
            int offset = 0;
            for (byte[] chunk : chunks) {
                System.arraycopy(chunk, 0, bytes, offset, chunk.length);
                offset += chunk.length;
            }
            chunks.clear();
            body.complete(new String(bytes, StandardCharsets.UTF_8));
        }
    }

    private static ExamAssistantException failure(HttpStatus status, String message) {
        return new ExamAssistantException(status, message);
    }
}
