package com.getjobs.application.service;

import com.google.gson.JsonObject;
import com.getjobs.worker.manager.PlaywrightManager;
import com.microsoft.playwright.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Window commands are consumed on the existing Playwright thread, never on an HTTP thread. */
@Service
public class BossQuietService {
    private final PlaywrightManager manager;
    private final Path settingsPath;
    private volatile boolean quietEnabled = true;
    private volatile boolean available = true;
    private volatile String windowState = "unknown";
    private volatile String message = "静默模式将在下次任务开始时生效";
    private volatile boolean needsAttention;
    private volatile boolean active;
    private volatile boolean userViewing;
    private volatile Page searchPage;
    private Page workPage;
    private Page attentionPage;
    private Integer ownedWindow;
    private boolean manualResume;
    private final AtomicReference<String> pending = new AtomicReference<>("");
    private final Queue<Page> newPages = new ConcurrentLinkedQueue<>();
    private final Set<Page> ownedPages = Collections.newSetFromMap(new IdentityHashMap<>());
    private Consumer<Page> pageListener;
    private long lastProbe;
    private Supplier<Boolean> stopSignal = () -> false;
    private final ScheduledExecutorService dispatcher = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "mitu-window-intents"); thread.setDaemon(true); return thread;
    });

    @Autowired
    public BossQuietService(PlaywrightManager manager) {
        this(manager, Path.of("work", "quiet-delivery", "settings.json"));
    }

    public BossQuietService(PlaywrightManager manager, Path settingsPath) {
        this.manager = manager;
        this.settingsPath = settingsPath;
    }

    @PostConstruct
    public void initialize() {
        try {
            if (Files.exists(settingsPath)) {
                JSONObject saved = new JSONObject(Files.readString(settingsPath, StandardCharsets.UTF_8));
                if (!(saved.opt("quietEnabled") instanceof Boolean)) throw new IllegalArgumentException();
                quietEnabled = saved.getBoolean("quietEnabled");
            }
            available = true;
            dispatcher.scheduleWithFixedDelay(() -> {
                if (!active && !pending.get().isEmpty() && manager != null && manager.isInitialized()) {
                    manager.tryRunOnPlaywright(this::checkpoint);
                }
            }, 250, 250, TimeUnit.MILLISECONDS);
        } catch (Exception failure) {
            available = false;
            message = "静默设置读取失败，请修复本地设置文件后重启";
        }
    }

    public Map<String, Object> settings() { return Map.of("quietEnabled", quietEnabled, "available", available); }

    public synchronized Map<String, Object> saveSettings(boolean enabled) {
        if (active) throw new IllegalStateException("任务运行中不能修改静默模式");
        if (!available) throw new IllegalStateException("静默设置不可用");
        Path temporary = null;
        try {
            Files.createDirectories(settingsPath.toAbsolutePath().getParent());
            temporary = Files.createTempFile(settingsPath.toAbsolutePath().getParent(), "quiet-", ".json");
            Files.writeString(temporary, new JSONObject().put("quietEnabled", enabled).toString(), StandardCharsets.UTF_8);
            try { Files.move(temporary, settingsPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException failure) { Files.move(temporary, settingsPath, StandardCopyOption.REPLACE_EXISTING); }
            quietEnabled = enabled;
            message = enabled ? "静默模式将在下次任务开始时生效" : "普通模式将在下次任务开始时生效";
            return settings();
        } catch (Exception failure) {
            throw new IllegalStateException("静默设置保存失败，原设置保留", failure);
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (Exception ignored) { }
        }
    }

    /** Run on the Playwright thread before the first candidate; no browser side effects when disabled. */
    public boolean begin(Page search) {
        return begin(search, () -> false);
    }

    public boolean begin(Page search, Supplier<Boolean> stop) {
        if (!available) throw new IllegalStateException("静默设置不可用，请检查工作台提示");
        pending.set("");
        needsAttention = false;
        userViewing = false;
        manualResume = false;
        if (!quietEnabled) { message = "普通模式"; return false; }
        if (search == null || search.isClosed()) throw new IllegalStateException("静默浏览器尚未就绪");
        try {
            searchPage = search;
            stopSignal = stop;
            ownedPages.clear();
            ownedPages.add(search);
            ownedWindow = windowId(search);
            active = true;
            pageListener = newPages::add;
            search.context().onPage(pageListener);
            setWindowState(search, "minimized");
            message = "静默运行中；可按需查看浏览器";
            return true;
        } catch (Exception failure) {
            finish();
            throw new IllegalStateException("当前浏览器不支持静默窗口控制，任务未开始；可显式切换普通模式", failure);
        }
    }

    /** Creates a background target in the same persistent context and verifies its identity/window. */
    public Page createPage(Page search) {
        if (!active || search != searchPage) throw new IllegalStateException("静默任务未初始化");
        CDPSession session = search.context().newCDPSession(search);
        String targetId = null;
        AtomicReference<String> requested = new AtomicReference<>();
        try {
            JsonObject info = session.send("Target.getTargetInfo").getAsJsonObject("targetInfo");
            JsonObject params = new JsonObject();
            params.addProperty("url", "about:blank");
            params.addProperty("background", true);
            params.addProperty("newWindow", false);
            if (info.has("browserContextId") && !info.get("browserContextId").getAsString().isBlank()) {
                params.addProperty("browserContextId", info.get("browserContextId").getAsString());
            }
            search.context().waitForPage(new BrowserContext.WaitForPageOptions().setTimeout(10_000), () ->
                    requested.set(session.send("Target.createTarget", params).get("targetId").getAsString()));
            targetId = requested.get();
            Page created = null;
            for (Page candidate : search.context().pages()) {
                if (!candidate.isClosed() && targetId.equals(targetId(candidate))) { created = candidate; break; }
            }
            if (created == null) throw new IllegalStateException("未能绑定静默投递页");
            if (!Objects.equals(ownedWindow, windowId(created))) throw new IllegalStateException("后台投递页窗口不匹配");
            ownedPages.add(created);
            workPage = created;
            created.setDefaultTimeout(30_000);
            // Some headed Chrome builds restore a minimized window even for a background target.
            // This runs once per dedicated page creation, not on every job navigation.
            if (!userViewing) setWindowState(created, "minimized");
            checkpoint();
            return created;
        } catch (Exception failure) {
            if (targetId == null) targetId = requested.get();
            if (targetId != null) {
                try { JsonObject close = new JsonObject(); close.addProperty("targetId", targetId); session.send("Target.closeTarget", close); }
                catch (Exception ignored) { }
            }
            throw new IllegalStateException("后台投递页创建失败，已停止以避免反复弹窗", failure);
        } finally { session.detach(); }
    }

    /** POST handlers only place an intent; never wait behind the whole delivery operation. */
    public Map<String, Object> request(String action) {
        if (!Set.of("show", "background").contains(action)) throw new IllegalArgumentException("浏览器操作无效");
        if (!available) throw new IllegalStateException("静默模块不可用");
        if (!active && (manager == null || !manager.isInitialized())) throw new IllegalStateException("浏览器尚未就绪，请先启动项目浏览器");
        pending.set(action);
        message = action.equals("show") ? "已请求显示，等待当前浏览器操作结束" : "已请求后台运行，等待当前浏览器操作结束";
        if (!active && manager != null) manager.tryRunOnPlaywright(this::checkpoint);
        return Map.of("accepted", true, "quiet", status());
    }

    public void checkpoint() {
        Page page = attentionPage != null ? attentionPage : workPage != null && !workPage.isClosed() ? workPage : searchPage;
        if (page == null && manager != null) page = manager.getBossPage();
        String action = pending.getAndSet("");
        try {
            if (page == null || page.isClosed()) {
                if (!action.isEmpty()) message = "受控浏览器已关闭，请重新启动任务";
                windowState = "unknown";
                return;
            }
            if (!action.isEmpty()) {
                if (action.equals("background") && needsAttention && requiresHuman(page.url())) {
                    message = "请先在浏览器中完成登录或验证，再继续后台运行";
                } else {
                    setWindowState(page, action.equals("show") ? "normal" : "minimized");
                    userViewing = action.equals("show");
                    if (!userViewing && needsAttention) manualResume = true;
                    message = userViewing ? "浏览器已显示，可人工处理" : "浏览器已最小化，继续后台运行";
                }
            }
            Page added;
            while ((added = newPages.poll()) != null) onPopup(added);
            if (active && !needsAttention && requiresHuman(page.url())) awaitManual(page, stopSignal);
            if (System.currentTimeMillis() - lastProbe > 1000) {
                windowState = readWindowState(page);
                lastProbe = System.currentTimeMillis();
            }
        } catch (Exception failure) {
            message = "浏览器窗口控制未完成；请检查浏览器状态";
            if (active) throw new IllegalStateException(message, failure);
        }
    }

    /** Only descendants of this task's pages are controlled. Ordinary user-created tabs are ignored. */
    public void onPopup(Page popup) {
        if (!active || popup == null || popup.isClosed() || ownedPages.contains(popup)) return;
        Page opener = popup.opener();
        if (opener == null || !ownedPages.contains(opener)) return;
        ownedPages.add(popup);
        if (!userViewing) setWindowState(popup, "minimized");
    }

    /** No console input, no captcha solving and no automatic repeat of a communication action. */
    public void awaitManual(Page page, Supplier<Boolean> stop) {
        needsAttention = true;
        attentionPage = page;
        manualResume = false;
        message = "任务已暂停；请查看浏览器处理登录/验证，再点击继续后台运行";
        long deadline = System.currentTimeMillis() + 5 * 60_000L;
        try {
            while (!manualResume) {
                if (Boolean.TRUE.equals(stop.get())) throw new IllegalStateException("用户已停止等待人工处理");
                if (System.currentTimeMillis() >= deadline) throw new IllegalStateException("人工处理等待超时，任务已停止，请确认岗位状态后重新开始");
                checkpoint();
                page.waitForTimeout(200);
            }
        } finally {
            needsAttention = false;
            attentionPage = null;
        }
    }

    public void finish() {
        if (searchPage != null && pageListener != null) {
            try { searchPage.context().offPage(pageListener); } catch (Exception ignored) { }
        }
        pageListener = null;
        active = false;
        needsAttention = false;
        attentionPage = null;
        pending.set("");
        newPages.clear();
        ownedPages.clear();
        workPage = null;
        searchPage = null;
        ownedWindow = null;
        stopSignal = () -> false;
        if (message.startsWith("静默运行中")) message = "任务已结束，浏览器保持后台，可按需查看";
    }

    public Map<String, Object> status() {
        return Map.of("enabled", quietEnabled, "available", available, "active", active,
                "windowState", windowState, "needsAttention", needsAttention, "message", message,
                "pendingAction", pending.get());
    }

    public static boolean requiresHuman(String url) {
        if (url == null) return false;
        return url.contains("/verify-slider") || url.contains("/passport/zp/verify")
                || url.contains("/passport/zp/security") || url.contains("security-check")
                || url.contains("/web/user/safe/") || url.contains("/web/user/?ka=header-login")
                || url.contains("/web/user/login") || url.contains("/passport/login");
    }

    private static String targetId(Page page) {
        CDPSession session = page.context().newCDPSession(page);
        try { return session.send("Target.getTargetInfo").getAsJsonObject("targetInfo").get("targetId").getAsString(); }
        finally { session.detach(); }
    }

    private static int windowId(Page page) {
        CDPSession session = page.context().newCDPSession(page);
        try { return session.send("Browser.getWindowForTarget").get("windowId").getAsInt(); }
        finally { session.detach(); }
    }

    private void setWindowState(Page page, String state) {
        CDPSession session = page.context().newCDPSession(page);
        try {
            int id = session.send("Browser.getWindowForTarget").get("windowId").getAsInt();
            if (active && !ownedPages.contains(page) && page != attentionPage) throw new IllegalStateException("窗口不属于本任务");
            JsonObject bounds = new JsonObject(); bounds.addProperty("windowState", state);
            JsonObject args = new JsonObject(); args.addProperty("windowId", id); args.add("bounds", bounds);
            session.send("Browser.setWindowBounds", args);
            if (state.equals("normal")) session.send("Page.bringToFront");
            JsonObject query = new JsonObject(); query.addProperty("windowId", id);
            windowState = session.send("Browser.getWindowBounds", query).getAsJsonObject("bounds").get("windowState").getAsString();
            if (!state.equals(windowState)) throw new IllegalStateException("未能确认浏览器窗口状态");
        } finally { session.detach(); }
    }

    private static String readWindowState(Page page) {
        CDPSession session = page.context().newCDPSession(page);
        try { return session.send("Browser.getWindowForTarget").getAsJsonObject("bounds").get("windowState").getAsString(); }
        finally { session.detach(); }
    }

    @PreDestroy
    public void close() { dispatcher.shutdownNow(); }
}
