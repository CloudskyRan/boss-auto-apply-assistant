package com.getjobs.worker.service;

import com.getjobs.application.service.ConfigService;
import com.getjobs.application.service.BossQuietService;
import com.getjobs.worker.boss.Boss;
import com.getjobs.worker.boss.BossConfig;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.manager.PlaywrightManager;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Boss直聘任务服务
 * 管理Boss平台的投递任务执行和状态
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BossJobService implements JobPlatformService {
    private static final String PLATFORM = "boss";

    private final PlaywrightManager playwrightManager;
    private final ConfigService configService;
    private final ObjectProvider<Boss> bossProvider;
    @Autowired(required = false)
    private BossQuietService quietService;

    // 任务运行状态
    private volatile boolean isRunning = false;
    // 停止标志
    private volatile boolean shouldStop = false;
    // 本次任务的开始时间（毫秒），用于判断任务是不是卡死了
    private volatile long runningSince = 0L;
    /** Last/current worker supplies JD counters without touching Playwright from HTTP threads. */
    private volatile Boss currentBoss;

    /** 在异步任务启动前校验已保存的配置，让 API 直接返回可操作的错误。 */
    public void validateDeliveryConfig() {
        configService.getBossConfig().validateForDelivery();
    }

    @Override
    public void executeDelivery(Consumer<JobProgressMessage> progressCallback) {
        boolean alreadyRunning;
        synchronized (this) {
            alreadyRunning = isRunning;
            if (!alreadyRunning) {
                // 初始化浏览器也属于本次任务，提前占用状态并原子阻止重复执行。
                isRunning = true;
                shouldStop = false;
                runningSince = System.currentTimeMillis();
                currentBoss = null;
            }
        }
        if (alreadyRunning) {
            progressCallback.accept(JobProgressMessage.warning(PLATFORM, "任务已在运行中"));
            return;
        }

        try {
            // 在访问浏览器前校验；定时任务等非 API 入口也必须遵循相同约束。
            BossConfig config = configService.getBossConfig();
            config.validateForDelivery();

            // 浏览器可能已经被关掉（手动关窗口 / Chrome 崩溃），这时各字段仍非空，
            // 但每个操作都会抛 TargetClosedError。先确保浏览器可用，必要时重新拉起。
            progressCallback.accept(JobProgressMessage.info(PLATFORM, "检查浏览器状态..."));
            playwrightManager.ensureReady();

            // 获取Boss页面实例
            Page page = playwrightManager.getBossPage();
            if (page == null) {
                progressCallback.accept(JobProgressMessage.error(PLATFORM, "Boss页面未初始化"));
                return;
            }

            // 检查是否已登录
            if (!playwrightManager.isLoggedIn(PLATFORM)) {
                progressCallback.accept(JobProgressMessage.error(PLATFORM, "请先登录Boss直聘"));
                return;
            }

            // 暂停后台登录监控，避免与投递流程并发访问同一Page
            playwrightManager.pauseBossMonitoring();

            // 加载配置（统一从 boss_config 专表读取）
            progressCallback.accept(JobProgressMessage.info(PLATFORM, "配置加载成功"));

            progressCallback.accept(JobProgressMessage.info(PLATFORM, "开始投递任务..."));

            // 创建Boss实例并执行投递
            Boss.ProgressCallback bossCallback = (message, current, total) -> {
                if (current != null && total != null) {
                    progressCallback.accept(JobProgressMessage.progress(PLATFORM, message, current, total));
                } else {
                    progressCallback.accept(JobProgressMessage.info(PLATFORM, message));
                }
            };

            Boss boss = bossProvider.getObject();
            currentBoss = boss;
            boss.setPage(page);
            boss.setConfig(config);
            boss.setProgressCallback(bossCallback);
            boss.setShouldStopCallback(this::shouldStop);
            boss.prepare();

            // 整个投递流程必须跑在 Playwright 专用线程上。
            // 原来跑在 ForkJoinPool 上，会和 scheduling-1 的登录监控同时操作同一条
            // Playwright 连接，导航被打断报 net::ERR_ABORTED，整个任务直接挂掉。
            int deliveredCount = playwrightManager.callOnPlaywright(() -> {
                try {
                    if (quietService != null && quietService.begin(page, this::shouldStop)) {
                        boss.setQuietDelivery(true);
                        boss.setQuietPageFactory(() -> quietService.createPage(page));
                        boss.setQuietPopupHandler(quietService::onPopup);
                        boss.setQuietCheckpoint(quietService::checkpoint);
                        boss.setQuietAttentionHandler(candidate -> quietService.awaitManual(candidate, this::shouldStop));
                    }
                    return boss.execute();
                } finally {
                    if (quietService != null) quietService.finish();
                }
            });

            progressCallback.accept(JobProgressMessage.success(PLATFORM,
                String.format("投递任务完成：已检查%d个岗位，尝试发起%d次沟通，成功沟通%d个，其中固定招呼语%d个，Boss默认招呼兜底%d个",
                        boss.getSearchedJobCount(), boss.getDeliveryAttemptCount(), deliveredCount,
                        boss.getSuccessfulGreetingCount(), boss.getDefaultGreetingCount())));
        } catch (Exception e) {
            log.error("Boss投递任务执行失败", e);
            progressCallback.accept(JobProgressMessage.error(PLATFORM, "投递失败: " + e.getMessage()));
        } finally {
            // 清理结束后才允许下一个任务进入，避免旧任务复位新任务的状态。
            try {
                playwrightManager.resumeBossMonitoring();
            } catch (Exception ignored) {}
            synchronized (this) {
                shouldStop = false;
                runningSince = 0L;
                isRunning = false;
            }
        }
    }

    @Override
    public void stopDelivery() {
        if (!isRunning) {
            return;
        }
        log.info("收到停止Boss投递任务的请求");
        shouldStop = true;

        // shouldStop 只在投递流程的检查点生效，阻塞中的浏览器调用需要继续等待。
        // 超时仅提示，不能提前释放状态；否则新任务会清除旧任务的停止标志并重叠执行。
        new Thread(() -> {
            long deadline = System.currentTimeMillis() + STOP_WARNING_DELAY_MS;
            while (isRunning && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (isRunning) {
                log.warn("Boss投递任务在 {} 秒内未结束，继续等待当前操作及清理完成；运行状态和停止标志保持不变",
                        STOP_WARNING_DELAY_MS / 1000);
            }
        }, "boss-stop-watchdog").start();
    }

    /** 停止指令发出后，提示任务仍未结束的等待时间。 */
    private static final long STOP_WARNING_DELAY_MS = 15_000L;

    /** 任务已经跑了多久（毫秒）；没在跑返回 0 */
    public long runningForMillis() {
        long since = runningSince;
        return since == 0L ? 0L : System.currentTimeMillis() - since;
    }

    @Override
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("platform", PLATFORM);
        status.put("isRunning", isRunning);
        status.put("isLoggedIn", playwrightManager.isLoggedIn(PLATFORM));
        Boss boss = currentBoss;
        status.put("jdCollection", boss == null ? Map.of("created", 0, "updated", 0, "duplicate", 0, "failed", 0)
                : boss.getJdCollection());
        status.put("quiet", quietService == null ? Map.of("enabled", false, "available", false,
                "windowState", "unknown", "needsAttention", false, "message", "静默模块未初始化") : quietService.status());
        return status;
    }

    @Override
    public String getPlatformName() {
        return PLATFORM;
    }

    @Override
    public boolean isRunning() {
        return isRunning;
    }

    /**
     * 检查是否应该停止
     * 供Boss.java调用
     */
    public boolean shouldStop() {
        return shouldStop;
    }

    
}
