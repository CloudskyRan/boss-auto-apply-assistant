package com.getjobs.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** Owns only the explicitly started local Windows companion; no Boss browser or database access. */
@Service
public class ExamDesktopService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_TEXT_LENGTH = 20_000;
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    private static final DesktopSettings DEFAULTS = new DesktopSettings("MituDesktop", true);
    private final Object lifecycle = new Object();
    private final Path baseDirectory;
    private final ExamAssistantService assistant;
    private final ExecutorService workers = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "mitu-desktop-worker");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Session current;
    private volatile String question = "";
    private volatile String answer = "";
    private volatile String message = "桌面窗口尚未启动。";
    private volatile boolean shuttingDown;

    @Autowired
    public ExamDesktopService(ExamAssistantService assistant) {
        this(assistant, Path.of("work", "desktop-assistant"));
    }

    ExamDesktopService(ExamAssistantService assistant, Path baseDirectory) {
        this.assistant = assistant;
        this.baseDirectory = baseDirectory.toAbsolutePath().normalize();
    }

    public record DesktopSettings(String processName, boolean excludeFromCapture) { }

    public record DesktopStatus(boolean supported, boolean running, String processName, Long pid,
                                boolean excludeFromCapture, boolean captureProtectionApplied,
                                String message, String question, String answer, boolean busy) { }

    public record CaptureResult(String text) { }

    public static String validateProcessName(String name) {
        if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_-]{0,47}")) {
            throw problem(HttpStatus.BAD_REQUEST, "进程名称须为 1–48 位英文字母、数字、下划线或短横线，且以字母开头。");
        }
        String upper = name.toUpperCase(Locale.ROOT);
        if (upper.matches("CON|PRN|AUX|NUL|CLOCK\\$|CONIN\\$|CONOUT\\$|COM[1-9]|LPT[1-9]")) {
            throw problem(HttpStatus.BAD_REQUEST, "进程名称不能使用 Windows 保留名称。");
        }
        return name;
    }

    /** Reading an absent file returns defaults without creating a directory or writing settings. */
    public DesktopSettings getSettings() {
        Path path = baseDirectory.resolve("settings.json");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return DEFAULTS;
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 4096) throw new IOException();
            DesktopSettings settings = JSON.readValue(Files.readString(path, StandardCharsets.UTF_8), DesktopSettings.class);
            if (settings == null) throw new IOException();
            validateProcessName(settings.processName());
            return settings;
        } catch (IOException | IllegalArgumentException | ResponseStatusException e) {
            throw problem(HttpStatus.INTERNAL_SERVER_ERROR, "桌面设置文件无效，请检查 work/desktop-assistant/settings.json。");
        }
    }

    public DesktopSettings saveSettings(DesktopSettings settings) {
        if (settings == null) throw problem(HttpStatus.BAD_REQUEST, "请提供桌面设置。");
        validateProcessName(settings.processName());
        synchronized (lifecycle) {
            if (isAlive(current)) throw problem(HttpStatus.CONFLICT, "请先关闭桌面窗口，再保存进程名称或捕获排除设置。");
            Path temporary = null;
            try {
                ensureBaseDirectory();
                temporary = Files.createTempFile(baseDirectory, ".settings-", ".json");
                Files.writeString(temporary, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(settings), StandardCharsets.UTF_8);
                atomicReplace(temporary, baseDirectory.resolve("settings.json"));
                return settings;
            } catch (IOException e) {
                throw problem(HttpStatus.INTERNAL_SERVER_ERROR, "无法保存桌面设置，请检查工作目录权限。");
            } finally {
                deleteQuietly(temporary);
            }
        }
    }

    public DesktopStatus getStatus() {
        synchronized (lifecycle) {
        Session session = current;
        boolean running = isAlive(session);
        DesktopSettings settings = running ? session.settings : getSettings();
        return new DesktopStatus(isWindows() && compilerPath() != null, running, settings.processName(),
                running ? session.process.pid() : null, settings.excludeFromCapture(),
                running && session.protectionApplied, isWindows() ? message : "桌面窗口目前仅支持 Windows。",
                question, answer, running && session.operation.get() != null);
        }
    }

    public DesktopStatus start() {
        synchronized (lifecycle) {
            if (shuttingDown) throw problem(HttpStatus.SERVICE_UNAVAILABLE, "服务正在关闭。");
            if (isAlive(current)) return getStatus();
            if (!isWindows() || compilerPath() == null) {
                throw problem(HttpStatus.NOT_IMPLEMENTED, "桌面窗口需要 Windows 与系统 .NET Framework 编译器。");
            }
            DesktopSettings settings = getSettings();
            Session session = null;
            Path unownedCaptures = null;
            try {
                ensureBaseDirectory();
                Path executable = prepareExecutable(settings.processName());
                Path captures = baseDirectory.resolve("captures").resolve(UUID.randomUUID().toString());
                Files.createDirectories(captures);
                unownedCaptures = captures;
                String instanceKey = sha256(baseDirectory.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 32);
                Process process = new ProcessBuilder(executable.toString(), "--capture-dir", captures.toString(),
                        "--instance-key", instanceKey, "--exclude-from-capture", Boolean.toString(settings.excludeFromCapture()))
                        .directory(baseDirectory.toFile()).start();
                session = new Session(process, settings, captures);
                unownedCaptures = null;
                current = session;
                message = "正在启动桌面窗口…";
                Session launched = session;
                workers.execute(() -> readEvents(launched));
                workers.execute(() -> drainErrors(launched));
                process.onExit().thenRun(() -> processExited(launched));
                session.ready.get(15, TimeUnit.SECONDS);
                if (!process.isAlive()) throw new IOException();
                message = session.settings.excludeFromCapture() && !session.protectionApplied
                        ? "桌面窗口已启动，但系统未成功应用捕获排除。" : session.readyMessage;
                send(session, Map.of("command", "update", "requestId", requestId(), "question", question, "answer", answer));
                return getStatus();
            } catch (ResponseStatusException e) {
                if (session != null) terminate(session);
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (session != null) terminate(session);
                throw problem(HttpStatus.SERVICE_UNAVAILABLE, "桌面窗口启动被中断。");
            } catch (IOException | ExecutionException | TimeoutException e) {
                if (session != null) terminate(session);
                message = "桌面窗口启动失败，请关闭残留的觅途窗口后重试。";
                throw problem(HttpStatus.INTERNAL_SERVER_ERROR, message);
            } finally {
                deleteQuietly(unownedCaptures);
            }
        }
    }

    public DesktopStatus stop() {
        synchronized (lifecycle) {
            Session session = current;
            if (session != null) terminate(session);
            message = "桌面窗口已关闭。";
            return getStatus();
        }
    }

    public DesktopStatus updateContent(String newQuestion, String newAnswer) {
        String cleanQuestion = validateText(newQuestion, false);
        String cleanAnswer = validateText(newAnswer, false);
        Session session;
        String id = requestId();
        synchronized (lifecycle) {
            session = current;
            if (isAlive(session) && !session.operation.compareAndSet(null, id)) {
                throw problem(HttpStatus.CONFLICT, "桌面助手正在识题或生成解析，请等待完成。");
            }
        }
        try {
                if (isAlive(session)) {
                    CompletableFuture<Void> applied = new CompletableFuture<>();
                    session.updates.put(id, applied);
                    try {
                        send(session, Map.of("command", "update", "requestId", id, "question", cleanQuestion, "answer", cleanAnswer));
                        applied.get(3, TimeUnit.SECONDS);
                    } finally { session.updates.remove(id); }
                }
                synchronized (lifecycle) {
                    if (session != null && session != current) throw problem(HttpStatus.CONFLICT, "桌面窗口状态已变化，请重新发送内容。");
                    question = cleanQuestion;
                    answer = cleanAnswer;
                    message = isAlive(session) ? "内容已发送到桌面窗口。" : "内容已准备，启动桌面窗口后显示。";
                    if (session != null) releaseOperation(session, id);
                    return getStatus();
                }
            } catch (IOException e) {
                throw problem(HttpStatus.SERVICE_UNAVAILABLE, "桌面窗口连接已断开，请重新启动。");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw problem(HttpStatus.SERVICE_UNAVAILABLE, "内容同步被中断，请重试。");
            } catch (ExecutionException e) {
                throw problem(HttpStatus.CONFLICT, "桌面助手正在操作，请等待完成后重新发送内容。");
            } catch (TimeoutException e) {
                throw problem(HttpStatus.GATEWAY_TIMEOUT, "桌面窗口未确认收到内容，请重试。");
        } finally {
            if (session != null) releaseOperation(session, id);
        }
    }

    /** Explicit web action; the companion button/hotkey enters the same local OCR path. */
    public CaptureResult capture() {
        Session session;
        String id = requestId();
        CompletableFuture<String> result = new CompletableFuture<>();
        synchronized (lifecycle) {
            session = current;
            if (!isAlive(session) || session.stopping) throw problem(HttpStatus.CONFLICT, "请先启动桌面窗口。");
            if (!session.operation.compareAndSet(null, id)) throw problem(HttpStatus.CONFLICT, "已有识题或解析任务，请等待完成。");
            session.pending.put(id, result);
        }
        try {
            send(session, Map.of("command", "capture", "requestId", id));
            return new CaptureResult(result.get(30, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelOperation(session, id, "截图识别被中断，请重试。");
            throw problem(HttpStatus.SERVICE_UNAVAILABLE, "截图识别被中断，请重试。");
        } catch (TimeoutException e) {
            cancelOperation(session, id, "截图识别超时，请重试或上传图片。");
            throw problem(HttpStatus.GATEWAY_TIMEOUT, "截图识别超时，请重试或上传图片。");
        } catch (ExecutionException | IOException e) {
            cancelOperation(session, id, "截图识别失败，请重试或上传图片。");
            throw problem(HttpStatus.BAD_GATEWAY, "截图识别失败，请重试或上传图片。");
        } finally {
            session.pending.remove(id);
        }
    }

    private static String validateText(String text, boolean required) {
        if (text == null || text.length() > MAX_TEXT_LENGTH || (required && text.isBlank())) {
            throw problem(HttpStatus.BAD_REQUEST, "题目与解析须为文本，每项最多 20,000 个字符；生成解析时题目不能为空。");
        }
        return text;
    }

    private void readEvents(Session session) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(session.process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() > 262144) continue;
                JsonNode event;
                try { event = JSON.readTree(line); } catch (IOException e) { continue; }
                if (current != session || session.stopping) continue;
                String type = event.path("type").asText();
                String id = event.path("requestId").asText();
                switch (type) {
                    case "ready" -> {
                        if (event.path("pid").asLong() != session.process.pid()
                                || !session.settings.processName().equals(event.path("processName").asText())) {
                            session.ready.completeExceptionally(new IOException());
                            continue;
                        }
                        session.protectionApplied = event.path("captureProtectionApplied").asBoolean(false);
                        session.readyMessage = event.path("message").asText("").contains("快捷键被占用")
                                ? "桌面窗口已启动；快捷键被占用，请使用窗口按钮。" : "桌面窗口已启动。";
                        session.ready.complete(null);
                    }
                    case "capture-requested" -> {
                        synchronized (lifecycle) {
                        if (current != session || session.stopping) break;
                        if (!validRequestId(id) || (!session.operation.compareAndSet(null, id) && !id.equals(session.operation.get()))) {
                            sendQuietly(session, Map.of("command", "capture-complete", "requestId", id, "error", "已有任务，请等待完成。"));
                        } else {
                            message = "正在截图并进行本机文字识别…";
                            CompletableFuture.delayedExecutor(30, TimeUnit.SECONDS).execute(() ->
                                    cancelOperation(session, id, "截图识别超时，请重试或上传图片。"));
                        }
                        }
                    }
                    case "captured" -> recognizeCapture(session, id, event.path("path").asText());
                    case "capture-error" -> finishCapture(session, id, null, "截图失败，请重试或上传图片。");
                    case "solve" -> solve(session, id, event.path("question").asText());
                    case "updated", "update-error" -> {
                        CompletableFuture<Void> update = session.updates.remove(id);
                        if (update != null) {
                            if (type.equals("updated")) update.complete(null);
                            else update.completeExceptionally(new IOException());
                        }
                    }
                    case "error" -> session.ready.completeExceptionally(new IOException());
                    default -> { /* No event payload is logged, including question/answer content. */ }
                }
            }
        } catch (IOException e) {
            session.ready.completeExceptionally(new IOException());
        }
    }

    private void recognizeCapture(Session session, String id, String suppliedPath) {
        Path image;
        try {
            image = Path.of(suppliedPath).toAbsolutePath().normalize();
            if (!validRequestId(id) || !image.getParent().equals(session.captureDirectory)
                    || !image.getFileName().toString().matches("[a-f0-9]{32}\\.png")
                    || !Files.isRegularFile(image, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(image) == 0 || Files.size(image) > MAX_IMAGE_BYTES) throw new IOException();
        } catch (IOException | RuntimeException e) {
            finishCapture(session, id, null, "截图文件无效，请重试或上传图片。");
            return;
        }
        if (!id.equals(session.operation.get())) { deleteQuietly(image); return; }
        synchronized (lifecycle) {
            if (current != session || session.stopping || !id.equals(session.operation.get())) { deleteQuietly(image); return; }
            CompletableFuture<String> result = new CompletableFuture<>();
            Future<?> task = workers.submit(() -> {
                String text = null;
                RuntimeException failure = null;
                try { text = validateText(assistant.recognizeImage(image), true); }
                catch (RuntimeException e) { failure = e; }
                finally { deleteQuietly(image); }
                if (failure == null) result.complete(text); else result.completeExceptionally(failure);
            });
            session.tasks.put(id, task);
            result.orTimeout(29, TimeUnit.SECONDS).whenComplete((text, failure) -> {
                session.tasks.remove(id, task);
                if (failure != null) task.cancel(true);
                finishCapture(session, id, text, failure == null ? null : "本机文字识别失败或超时，请重试或上传图片。");
            });
        }
    }

    private void finishCapture(Session session, String id, String text, String error) {
        synchronized (lifecycle) {
        if (!validRequestId(id) || current != session || session.stopping || !claimCompletion(session, id)) return;
        if (error == null) {
            question = text;
            answer = "";
            message = "题目已识别，请检查内容后生成解析。";
            sendQuietly(session, Map.of("command", "capture-complete", "requestId", id, "question", text, "error", ""));
        } else {
            message = error;
            Future<?> task = session.tasks.remove(id);
            if (task != null) task.cancel(true);
            sendQuietly(session, Map.of("command", "capture-complete", "requestId", id, "error", error));
        }
        releaseOperation(session, id + "!");
        CompletableFuture<String> result = session.pending.remove(id);
        if (result != null) {
            if (error == null) result.complete(text); else result.completeExceptionally(new IOException());
        }
        }
    }

    private void solve(Session session, String id, String actualQuestion) {
        synchronized (lifecycle) {
        if (current != session || session.stopping) return;
        if (!validRequestId(id) || !session.operation.compareAndSet(null, id)) {
            sendQuietly(session, Map.of("command", "solve-complete", "requestId", id, "error", "已有识题或解析任务，请等待完成。"));
            return;
        }
        try {
            question = validateText(actualQuestion, true).trim();
            answer = "";
        } catch (ResponseStatusException e) {
            releaseOperation(session, id);
            sendQuietly(session, Map.of("command", "solve-complete", "requestId", id, "error", "请提供有效题目，最多 20,000 个字符。"));
            return;
        }
        String submitted = question;
        message = "正在生成解析…";
        CompletableFuture<String> result = new CompletableFuture<>();
        Future<?> task = workers.submit(() -> {
            try {
                String reply = assistant.solve(submitted);
                if (reply != null && reply.length() > MAX_TEXT_LENGTH) throw new AnswerTooLongException();
                result.complete(validateText(reply, true));
            } catch (RuntimeException e) { result.completeExceptionally(e); }
        });
        session.tasks.put(id, task);
        result.orTimeout(80, TimeUnit.SECONDS).whenComplete((reply, failure) -> {
                    session.tasks.remove(id, task);
                    if (failure != null) task.cancel(true);
                    synchronized (lifecycle) {
                    if (current != session || session.stopping || !claimCompletion(session, id)) return;
                    if (failure == null) {
                        answer = reply;
                        message = "解析已生成。";
                        sendQuietly(session, Map.of("command", "solve-complete", "requestId", id, "answer", reply, "error", ""));
                    } else {
                        message = failure instanceof AnswerTooLongException ? "解析超过 20,000 个字符，请缩短题目后重试。"
                                : "生成解析失败或超时，请检查系统设置中的 AI 服务后重试。";
                        sendQuietly(session, Map.of("command", "solve-complete", "requestId", id, "error", message));
                    }
                    releaseOperation(session, id + "!");
                    }
                });
        }
    }

    private void cancelOperation(Session session, String id, String error) {
        synchronized (lifecycle) {
        if (current != session || session.stopping) return;
        if (claimCompletion(session, id)) {
            message = error;
            Future<?> task = session.tasks.remove(id);
            if (task != null) task.cancel(true);
            sendQuietly(session, Map.of("command", "capture-complete", "requestId", id, "error", error));
            releaseOperation(session, id + "!");
        }
        CompletableFuture<String> pending = session.pending.remove(id);
        if (pending != null) pending.completeExceptionally(new IOException());
        }
    }

    private void send(Session session, Map<String, ?> command) throws IOException {
        synchronized (session.writer) {
            session.writer.write(JSON.writeValueAsString(command));
            session.writer.newLine();
            session.writer.flush();
        }
    }

    private void sendQuietly(Session session, Map<String, ?> command) {
        if (!session.process.isAlive() || session.stopping) return;
        try { send(session, command); } catch (IOException e) { /* The exit watcher owns recovery. */ }
    }

    private void drainErrors(Session session) {
        try (InputStream error = session.process.getErrorStream()) {
            error.transferTo(java.io.OutputStream.nullOutputStream());
        } catch (IOException e) { /* Deliberately do not publish subprocess stderr. */ }
    }

    private void processExited(Session session) {
        session.ready.completeExceptionally(new IOException());
        session.pending.values().forEach(value -> value.completeExceptionally(new IOException()));
        session.pending.clear();
        session.updates.values().forEach(value -> value.completeExceptionally(new IOException()));
        session.updates.clear();
        session.operation.set(null);
        session.tasks.values().forEach(task -> task.cancel(true));
        session.tasks.clear();
        synchronized (lifecycle) {
            if (current == session) {
                current = null;
                message = session.stopping ? "桌面窗口已关闭。" : "桌面窗口已退出，可重新启动。";
            }
        }
        cleanupCaptures(session);
    }

    private void terminate(Session session) {
        session.stopping = true;
        session.pending.values().forEach(value -> value.completeExceptionally(new IOException()));
        session.pending.clear();
        session.updates.values().forEach(value -> value.completeExceptionally(new IOException()));
        session.updates.clear();
        session.operation.set(null);
        session.tasks.values().forEach(task -> task.cancel(true));
        session.tasks.clear();
        try {
            if (session.process.isAlive()) {
                try { send(session, Map.of("command", "close", "requestId", requestId())); } catch (IOException ignored) { }
                if (!session.process.waitFor(3, TimeUnit.SECONDS)) {
                    session.process.destroy();
                    if (!session.process.waitFor(2, TimeUnit.SECONDS)) session.process.destroyForcibly();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.process.destroyForcibly();
        } finally {
            try { session.writer.close(); } catch (IOException ignored) { }
            if (current == session) current = null;
            cleanupCaptures(session);
        }
    }

    private void cleanupCaptures(Session session) {
        Path path = session.captureDirectory;
        if (!path.startsWith(baseDirectory.resolve("captures")) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return;
        try (var entries = Files.list(path)) {
            entries.filter(item -> item.getFileName().toString().matches("[a-f0-9]{32}\\.png"))
                    .filter(item -> Files.isRegularFile(item, LinkOption.NOFOLLOW_LINKS)).forEach(ExamDesktopService::deleteQuietly);
        } catch (IOException ignored) { }
        deleteQuietly(path); // Only removes an empty owned session directory.
    }

    private Path prepareExecutable(String processName) throws IOException, InterruptedException {
        byte[] source;
        try (InputStream resource = getClass().getResourceAsStream("/desktop/MituDesktop.cs")) {
            if (resource == null) throw new IOException();
            source = resource.readAllBytes();
        }
        String fingerprint = sha256(source).substring(0, 16);
        String buildKey = sha256((processName + "|" + fingerprint).getBytes(StandardCharsets.UTF_8)).substring(0, 16);
        Path directory = baseDirectory.resolve("bin").resolve(buildKey);
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) throw new IOException();
        Path executable = directory.resolve(processName + ".exe");
        Path stamp = directory.resolve(".mitu-build-sha256");
        if (Files.exists(executable, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(stamp, LinkOption.NOFOLLOW_LINKS)
                    || !Files.readString(stamp, StandardCharsets.US_ASCII).trim().equals(sha256(Files.readAllBytes(executable)))) {
                throw problem(HttpStatus.CONFLICT, "桌面输出目录已有无法验证的文件，请检查 work/desktop-assistant/bin。");
            }
            return executable;
        }
        Path sourceFile = directory.resolve("MituDesktop.cs");
        Path temporary = directory.resolve(".build-" + UUID.randomUUID() + ".exe");
        Files.write(sourceFile, source);
        Process compiler = new ProcessBuilder(compilerPath().toString(), "/nologo", "/target:winexe", "/optimize+",
                "/out:" + temporary, "/reference:System.Drawing.dll", "/reference:System.Windows.Forms.dll",
                "/reference:System.Web.Extensions.dll", sourceFile.toString()).redirectErrorStream(true).start();
        workers.execute(() -> {
            try (InputStream output = compiler.getInputStream()) { output.transferTo(java.io.OutputStream.nullOutputStream()); }
            catch (IOException ignored) { }
        });
        try {
            if (!compiler.waitFor(20, TimeUnit.SECONDS)) {
                compiler.destroyForcibly();
                throw problem(HttpStatus.GATEWAY_TIMEOUT, "桌面组件编译超时，请重试。");
            }
            if (compiler.exitValue() != 0 || !Files.isRegularFile(temporary)) throw new IOException();
            atomicReplace(temporary, executable);
            Files.writeString(stamp, sha256(Files.readAllBytes(executable)), StandardCharsets.US_ASCII);
            return executable;
        } finally {
            if (compiler.isAlive()) compiler.destroyForcibly();
            deleteQuietly(temporary);
        }
    }

    private void ensureBaseDirectory() throws IOException {
        Files.createDirectories(baseDirectory);
        if (!Files.isDirectory(baseDirectory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(baseDirectory)) throw new IOException();
    }

    private static void atomicReplace(Path from, Path to) throws IOException {
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
    }

    private static Path compilerPath() {
        String windows = System.getenv("WINDIR");
        if (windows == null) return null;
        Path root = Path.of(windows);
        for (String framework : new String[]{"Framework64", "Framework"}) {
            Path candidate = root.resolve("Microsoft.NET").resolve(framework).resolve("v4.0.30319").resolve("csc.exe");
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows"); }
    private static boolean isAlive(Session session) { return session != null && session.process.isAlive(); }
    private static boolean validRequestId(String id) { return id.matches("[a-f0-9]{32}"); }
    private static boolean releaseOperation(Session session, String id) {
        String active = session.operation.get();
        return id.equals(active) && session.operation.compareAndSet(active, null);
    }
    private static boolean claimCompletion(Session session, String id) {
        String active = session.operation.get();
        return id.equals(active) && session.operation.compareAndSet(active, id + "!");
    }
    private static String requestId() { return UUID.randomUUID().toString().replace("-", ""); }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static ResponseStatusException problem(HttpStatus status, String message) { return new ResponseStatusException(status, message); }
    private static void deleteQuietly(Path path) {
        if (path != null) try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }

    @PreDestroy
    public void close() {
        synchronized (lifecycle) {
            shuttingDown = true;
            if (current != null) terminate(current);
            workers.shutdownNow();
        }
    }

    private static final class Session {
        private final Process process;
        private final DesktopSettings settings;
        private final Path captureDirectory;
        private final BufferedWriter writer;
        private final CompletableFuture<Void> ready = new CompletableFuture<>();
        private final AtomicReference<String> operation = new AtomicReference<>();
        private final ConcurrentHashMap<String, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, CompletableFuture<Void>> updates = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Future<?>> tasks = new ConcurrentHashMap<>();
        private volatile boolean protectionApplied;
        private volatile boolean stopping;
        private volatile String readyMessage = "桌面窗口已启动。";

        private Session(Process process, DesktopSettings settings, Path captures) {
            this.process = process;
            this.settings = settings;
            this.captureDirectory = captures;
            this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        }
    }

    private static final class AnswerTooLongException extends RuntimeException { }
}
