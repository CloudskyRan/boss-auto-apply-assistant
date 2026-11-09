package com.getjobs.worker.boss;

import com.getjobs.application.entity.AiEntity;
import com.getjobs.application.service.AiService;
import com.getjobs.application.service.BossService;
import com.getjobs.application.service.BossJdStore;
import com.getjobs.application.service.BossJdAnalysisService;
import com.getjobs.worker.utils.Job;
import com.getjobs.worker.utils.JobUtils;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import lombok.RequiredArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.context.annotation.Scope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.function.Consumer;

import static com.getjobs.worker.boss.Locators.*;


/**
 * @author loks666
 * 项目链接: <a href=
 * "https://github.com/loks666/get_jobs">https://github.com/loks666/get_jobs</a>
 * Boss直聘自动投递
 */
@Slf4j
@Component
@Scope("prototype")
@RequiredArgsConstructor
public class Boss {

    @Setter
    private Page page;
    @Setter
    private BossConfig config;
    private final BossService bossService;
    private final AiService aiService;
    @Autowired(required = false)
    @Setter
    private BossJdStore jdStore;
    @Autowired(required = false)
    @Setter
    private BossJdAnalysisService jdAnalysis;
    private volatile int jdCreated;
    private volatile int jdUpdated;
    private volatile int jdDuplicate;
    private volatile int jdFailed;
    private String jdTaskId;
    private Set<String> blackCompanies;
    private Set<String> blackRecruiters;
    private Set<String> blackJobs;
    // 记录 encryptId -> encryptUserId 的映射，用于后续更新投递状态
    private final ConcurrentMap<String, String> encryptIdToUserId = new ConcurrentHashMap<>();
    @Setter
    private ProgressCallback progressCallback;
    @Setter
    private Supplier<Boolean> shouldStopCallback;
    /** Quiet mode retains one task-owned detail page and never falls back to foreground page creation. */
    @Setter
    private boolean quietDelivery;
    @Setter
    private Supplier<Page> quietPageFactory;
    @Setter
    private Consumer<Page> quietPopupHandler;
    @Setter
    private Runnable quietCheckpoint;
    @Setter
    private Consumer<Page> quietAttentionHandler;
    private Page reusableDeliveryPage;
    private final Set<Page> quietOwnedRoots = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Page> quietObservedPopups = Collections.newSetFromMap(new IdentityHashMap<>());

    private static final class QuietDeliveryFailure extends IllegalStateException {
        private QuietDeliveryFailure(String message) { super(message); }
        private QuietDeliveryFailure(String message, Throwable cause) { super(message, cause); }
    }

    private final List<Job> resultList = new ArrayList<>();
    @Getter
    private int searchedJobCount;
    @Getter
    private int deliveryAttemptCount;
    /** 自定义招呼实际发送成功次数（首条协议或聊天页第二条消息）。 */
    @Getter
    private int successfulGreetingCount;
    /** 协议失败后由 Boss 原生按钮发送的默认招呼次数。 */
    @Getter
    private int defaultGreetingCount;
    private boolean limitReported;

    private static final class DefaultGreetingResult {
        private final boolean sent;
        private final Page chatPage;

        private DefaultGreetingResult(boolean sent, Page chatPage) {
            this.sent = sent;
            this.chatPage = chatPage;
        }
    }

    private enum ProtocolGreetingResult {
        SENT,
        UNAVAILABLE,
        FAILED
    }

    /** Boss 首页，UI 搜索的入口 */
    private static final String BOSS_HOME_URL = "https://www.zhipin.com";
    /** Boss 的全国筛选编码；0/省略 city 会按浏览器定位城市搜索。 */
    private static final String NATIONWIDE_CITY_CODE = "100010000";
    /**
     * true=先回首页、在搜索框里做一次真实 UI 搜索再进列表页；false=直接拼 URL 跳转。
     * <p>
     * 默认关掉。实测 Boss 首页在受控标签页里会一直转圈加载不完，navigate 要 60 秒才返回，
     * 期间 evaluate / locator 全部无限期阻塞（这两个 API 没有默认超时）—— 启动慢、投递卡死、
     * 管理页面按钮没反应都出在这。而岗位列表页在同一个浏览器里是秒开的。
     * 想重新试 UI 搜索路径时再打开。
     */
    private static final boolean USE_UI_SEARCH = false;
    /** 等待页面加载状态的超时（毫秒），绝不能不设 —— 见 waitForPageSettled 的说明 */
    private static final double LOAD_STATE_TIMEOUT = 10_000;

    /** Boss 不同入口使用的聊天编辑器；顺序优先弹窗，再处理直接跳转聊天页。 */
    private static final String CHAT_INPUT_SELECTORS = String.join(", ",
            ".startchat-content .edit-area .input-area:visible",
            ".startchat-content textarea:visible",
            ".greet-boss-dialog textarea:visible",
            ".dialog-container textarea:visible",
            ".dialog-container .input-area:visible",
            ".chat-input textarea:visible",
            ".chat-input-box textarea:visible",
            ".input-area textarea:visible",
            ".chat-conversation .chat-editor #chat-input:visible",
            ".chat-editor #chat-input:visible",
            "#chat-input:visible",
            "textarea.input-area:visible",
            "textarea.chat-input:visible",
            "textarea[placeholder*='发送']:visible",
            "div#chat-input[contenteditable='true']:visible",
            "div.chat-input[contenteditable='true']:visible",
            "div.input-area[contenteditable='true']:visible",
            "[contenteditable='true'][role='textbox']:visible",
            "[contenteditable='true'][class*='input']:visible",
            "#boss-chat-global-input:visible",
            ".chat-input:visible",
            ".chat-input-box:visible",
            ".input-area:visible");

    /** 文本发送按钮，优先限制在聊天编辑器，避免误点发送图片等工具按钮。 */
    private static final String CHAT_SEND_SELECTORS = String.join(", ",
            ".startchat-content .edit-area .send-message:visible",
            ".dialog-container .send-message:visible",
            ".chat-editor .btn-send:visible",
            ".chat-editor .send-btn:visible",
            ".btn.btn-primary.btn-send:visible",
            ".chat-editor button[aria-label*='发送']:not([aria-label*='图片']):visible",
            "div.send-message:visible",
            "button[type='send'].btn-send:visible",
            "button.btn-send:visible",
            ".send-btn:visible",
            "button.chat-btn:visible",
            "[role='button'][class*='send']:visible");

    /**
     * 进度回调接口
     */
    @FunctionalInterface
    public interface ProgressCallback {
        void accept(String message, Integer current, Integer total);
    }

    // 通过 Lombok @RequiredArgsConstructor 使用构造器注入 bossService 与 aiService

    public void prepare() {
        // 调整 boss_data 表结构：将 encrypt_id、encrypt_user_id 前置
        try { bossService.ensureBossDataColumnOrder(); } catch (Throwable ignore) {}
        // 从数据库加载黑名单
        this.blackCompanies = bossService.getBlackCompanies();
        this.blackRecruiters = bossService.getBlackRecruiters();
        this.blackJobs = bossService.getBlackJobs();

        log.info("黑名单加载完成: 公司({}) 招聘者({}) 职位({})",
                blackCompanies != null ? blackCompanies.size() : 0,
                blackRecruiters != null ? blackRecruiters.size() : 0,
                blackJobs != null ? blackJobs.size() : 0);
        // 不在页面初始化阶段入库，仅用于后续点击卡片时按需入库
    }

    /**
     * 执行投递
     */
    public int execute() {
        config.validateForDelivery();
        searchedJobCount = 0;
        deliveryAttemptCount = 0;
        successfulGreetingCount = 0;
        defaultGreetingCount = 0;
        limitReported = false;
        resultList.clear();
        jdTaskId = UUID.randomUUID().toString();
        jdCreated = jdUpdated = jdDuplicate = jdFailed = 0;
        try {
        for (String cityCode : config.getCityCode()) {
            if (hasReachedTaskLimit()) break;
            if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                progressCallback.accept("用户取消投递", 0, 0);
                break;
            }
            postJobByCity(cityCode);
            if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                progressCallback.accept("用户取消投递", 0, 0);
                break;
            }
        }
        hasReachedTaskLimit();
        return resultList.size();
        } finally {
            closeReusableDeliveryPage();
            if (jdAnalysis != null) {
                try { jdAnalysis.onCollectionFinished(jdTaskId); }
                catch (Exception failure) { log.warn("JD后台分析调度未完成，原文仍保留"); }
            }
            if (progressCallback != null && jdStore != null) {
                progressCallback.accept("JD留存：新增" + jdCreated + "、版本更新" + jdUpdated
                        + "、重复" + jdDuplicate + "、失败" + jdFailed
                        + (jdFailed > 0 ? "（本次留存不完整）" : ""), null, null);
            }
        }
    }

    /** Counts belong to the current collection task and never consume delivery quota. */
    public Map<String, Integer> getJdCollection() {
        return Map.of("created", jdCreated, "updated", jdUpdated, "duplicate", jdDuplicate, "failed", jdFailed);
    }

    /**
     * 获取结果列表
     */
    public List<Job> getResultList() {
        return new ArrayList<>(resultList);
    }

    private boolean hasReachedDeliveryLimit() {
        return config.getMaxDeliveryJobs() != null && config.getMaxDeliveryJobs() > 0
                && deliveryAttemptCount >= config.getMaxDeliveryJobs();
    }

    private int remainingSearchJobs() {
        return config.getMaxSearchJobs() != null && config.getMaxSearchJobs() > 0
                ? Math.max(0, config.getMaxSearchJobs() - searchedJobCount) : Integer.MAX_VALUE;
    }

    /** 两项上限在整次任务内累计，不因切换关键词或城市而重置。 */
    private boolean hasReachedTaskLimit() {
        boolean searchReached = remainingSearchJobs() == 0;
        boolean deliveryReached = hasReachedDeliveryLimit();
        if (!searchReached && !deliveryReached) return false;
        if (!limitReported) {
            String reason = deliveryReached
                    ? "已达到本次投递上限：" + deliveryAttemptCount + " 次沟通尝试"
                    : "已达到搜索岗位上限：" + searchedJobCount + " 个候选岗位";
            log.info("{}，结束本次任务", reason);
            if (progressCallback != null) progressCallback.accept(reason + "，结束本次任务", null, null);
            limitReported = true;
        }
        return true;
    }

    /**
     * 更新黑名单（从聊天记录中）
     */
    public Map<String, Set<String>> updateBlacklistFromChats() {
        page.navigate("https://www.zhipin.com/web/geek/chat");
        PlaywrightUtil.sleep(3);

        int newBlacklistCount = 0;
        boolean shouldBreak = false;
        while (!shouldBreak) {
            try {
                Locator bottomLocator = page.locator(FINISHED_TEXT);
                if (bottomLocator.count() > 0 && "没有更多了".equals(bottomLocator.textContent())) {
                    shouldBreak = true;
                }
            } catch (Exception ignore) {
            }

            Locator items = page.locator(CHAT_LIST_ITEM);
            int itemCount = items.count();

            for (int i = 0; i < itemCount; i++) {
                try {
                    Locator companyElements = page.locator(COMPANY_NAME_IN_CHAT);
                    Locator messageElements = page.locator(LAST_MESSAGE);

                    if (i >= companyElements.count() || i >= messageElements.count()) {
                        break;
                    }

                    String companyName = null;
                    String message = null;
                    int retryCount = 0;

                    while (true) {
                        try {
                            companyName = companyElements.nth(i).textContent();
                            message = messageElements.nth(i).textContent();
                            break;
                        } catch (Exception e) {
                            retryCount++;
                            if (retryCount >= 2) {
                                log.info("尝试获取元素文本2次失败，放弃本次获取");
                                break;
                            }
                            log.info("页面元素已变更，正在重试第{}次获取元素文本...", retryCount);
                            PlaywrightUtil.sleep(1);
                        }
                    }

                    if (companyName != null && message != null) {
                        boolean match = message.contains("不") || message.contains("感谢") || message.contains("但")
                                || message.contains("遗憾") || message.contains("需要本") || message.contains("对不");
                        boolean nomatch = message.contains("不是") || message.contains("不生");
                        if (match && !nomatch) {
                            if (blackCompanies.stream().anyMatch(companyName::contains)) {
                                continue;
                            }
                            companyName = companyName.replaceAll("\\.{3}", "");
                            if (companyName.matches(".*(\\p{IsHan}{2,}|[a-zA-Z]{4,}).*")) {
                                blackCompanies.add(companyName);
                                // 保存到数据库
                                bossService.addBlacklist("company", companyName);
                                newBlacklistCount++;
                                log.info("黑名单公司：【{}】，信息：【{}】", companyName, message);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("寻找黑名单公司异常...", e);
                }
            }

            try {
                Locator scrollElement = page.locator(SCROLL_LOAD_MORE);
                if (scrollElement.count() > 0) {
                    scrollElement.scrollIntoViewIfNeeded();
                } else {
                    page.evaluate("window.scrollTo(0, document.body.scrollHeight);");
                }
            } catch (Exception e) {
                log.error("滚动元素出错", e);
                break;
            }
        }
        log.info("黑名单公司数量：{}，本次新增：{}", (blackCompanies != null ? blackCompanies.size() : 0), newBlacklistCount);

        Map<String, Set<String>> result = new HashMap<>();
        result.put("blackCompanies", new HashSet<>(blackCompanies != null ? blackCompanies : Collections.emptySet()));
        result.put("blackRecruiters", new HashSet<>(blackRecruiters != null ? blackRecruiters : Collections.emptySet()));
        result.put("blackJobs", new HashSet<>(blackJobs != null ? blackJobs : Collections.emptySet()));
        return result;
    }

    private void postJobByCity(String cityCode) {
        String searchUrl = getSearchUrl(cityCode);
        for (String keyword : config.getKeywords()) {
            if (hasReachedTaskLimit()) return;
            // 检查是否需要停止
            if (shouldStopCallback.get()) {
                progressCallback.accept("用户取消投递", 0, 0);
                return;
            }

            int postCount = 0;
            // 使用 URLEncoder 对关键词进行编码
            String encodedKeyword = URLEncoder.encode(keyword, StandardCharsets.UTF_8);

            String url = searchUrl + (searchUrl.contains("?") ? "&" : "?") + "query=" + encodedKeyword;
            // 单个关键词失败不该让整个投递任务中止，下面整段都包在 try 里
            try {
            // 进列表页 + 等列表渲染，整段带重试
            openJobListWithRetry(keyword, url, cityCode);

            // 1. 基于 footer 出现滚动到底，确保加载全部岗位
            int lastCount = -1;
            int stableTries = 0;
            int staleHits = 0;
            // 上限 300 轮：原来写的是 5000，而且 stableTries 只触发强制触底、从不退出循环，
            // 一旦 footer 选择器匹配不到就会空转几千轮，每轮一次 evaluate + count，
            // 能把 playwright 线程占死几十分钟 —— 整个应用跟着卡住、投递任务也永远结束不了。
            boolean loadedAll = false;
            int remainingSearch = remainingSearchJobs();
            for (int i = 0; i < 300; i++) {
                // 停止检查：滚动加载过程中也要及时响应
                if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                    progressCallback.accept("用户取消投递", 0, 0);
                    return;
                }
                // 滚动加载期间 Boss 会频繁增删 frame，Playwright 派发这些事件时可能
                // 引用到已销毁的 frame（Object doesn't exist: frame@...），异常会顺着
                // 当时在飞的那个调用抛出来。这类异常和调用本身无关，跳过这一轮继续滚就行。
                try {
                    // 只加载足够本次检查的候选，避免设置 100 却先滚动加载 300 个。
                    if (remainingSearch != Integer.MAX_VALUE && countJobCards() >= remainingSearch) {
                        log.info("【{}】已加载足够候选，本次最多检查 {} 个岗位", keyword, remainingSearch);
                        loadedAll = true;
                        break;
                    }
                    // 按视口高度的90%渐进滚动，触发懒加载
                    page.evaluate("() => window.scrollBy(0, Math.floor(window.innerHeight * 1.5))");
                    // 给懒加载请求留出响应时间。原先滚动后立刻计数，一轮只有几十毫秒：
                    // 只要下一批数据返回稍慢（限流、网络抖动），就会连续 2 轮计数不变，
                    // 恰好此时 footer 已在视口内，被误判为“加载完毕”，只拿到几十个岗位。
                    page.waitForTimeout(500);

                    // footer 可见不能立刻就当作"加载完了"：窗口最大化时首屏很短，
                    // 第一轮 footer 就是可见的，会导致只拿到首屏 15 个岗位就退出
                    // （实测同样的搜索条件，正常滚完是 300 个）。
                    // 必须先滚一段、并且连着几轮没有新增岗位，footer 才算数。
                    boolean footerVisible = false;
                    Locator footer = page.locator("div#footer, #footer");
                    if (footer.count() > 0 && footer.first().isVisible()) {
                        footerVisible = true;
                    }

                    // 获取卡片数量变化，判断是否需要强制触底
                    Locator cardsProbe = page.locator("//ul[contains(@class, 'rec-job-list')]//li[contains(@class, 'job-card-box')]");
                    int currentCount = cardsProbe.count();
                    if (currentCount == lastCount) {
                        stableTries++;
                    } else {
                        stableTries = 0;
                    }
                    lastCount = currentCount;

                    if (footerVisible && stableTries >= 5) {
                        log.info("【{}】已滚动到底部且连续 {} 轮无新增，判定加载完毕", keyword, stableTries);
                        loadedAll = true;
                        break;
                    }
                    if (stableTries >= 3) { // 连续多次无新增，则强制触底一次
                        page.evaluate("() => window.scrollTo(0, document.body.scrollHeight)");
                        page.waitForTimeout(500);
                    }
                    // 强制触底之后仍然连着好几轮没有新岗位，就认定加载完了。
                    // 不能只靠 footer —— Boss 有些版式根本没有 #footer，只等它就是死循环。
                    if (stableTries >= 10) {
                        log.info("【{}】连续 {} 轮没有新增岗位，判定已加载完毕", keyword, stableTries);
                        loadedAll = true;
                        break;
                    }
                } catch (Exception e) {
                    if (!isStaleObjectError(e)) {
                        throw e;
                    }
                    staleHits++;
                    if (staleHits > 20) {
                        log.warn("【{}】滚动期间反复出现失效对象异常({}次)，停止继续加载", keyword, staleHits);
                        break;
                    }
                    PlaywrightUtil.sleep(1);
                }
            }
            if (!loadedAll) {
                log.warn("【{}】滚动到达 300 轮上限仍未确认加载完毕，按当前已加载的岗位继续", keyword);
            }
            // 统计最终岗位数量
            int loadedCount = Math.min(countJobCards(), remainingSearch);
            log.info("【{}】岗位加载完成，本次待检查数量:{}", keyword, loadedCount);
            progressCallback.accept("岗位加载完成：" + keyword, 0, loadedCount);

            // 2. 回到页面顶部
            page.evaluate("window.scrollTo(0, 0);");
            PlaywrightUtil.sleep(1);

            // 3. 逐个遍历所有岗位
            Locator cards = page.locator("//ul[contains(@class, 'rec-job-list')]//li[contains(@class, 'job-card-box')]");
            int availableCount = cards.count();
            int count = Math.min(availableCount, remainingSearch);
            for (int i = 0; i < count; i++) {
                if (hasReachedTaskLimit()) return;
                // 检查是否需要停止
                if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                    progressCallback.accept("用户取消投递", i, count);
                    return;
                }

                // 一旦开始检查候选，无论详情失败或被过滤，均占用搜索上限。
                searchedJobCount++;

                // 重新获取卡片，避免元素过期
                cards = page.locator("//ul[contains(@class, 'rec-job-list')]//li[contains(@class, 'job-card-box')]");
                // 在点击卡片时同步等待岗位详情接口返回，随后解析并入库
                Response detailResp = null;
                String expectedJdId = jdStore == null ? null : cardJobId(cards.nth(i));
                try {
                    if (i == 0 && availableCount > 1) {
                        // 第一个卡片默认展开不会触发请求：先切到第二个，再切回第一个，并在返回第一个时监听响应
                        final Locator secondCard = cards.nth(1);
                        secondCard.click();
                        PlaywrightUtil.sleep(1);
                        final Locator firstCard = cards.nth(0);
                        detailResp = page.waitForResponse(r -> {
                            try {
                                return r.url() != null && r.url().contains("/wapi/zpgeek/job/detail.json")
                                        && "GET".equalsIgnoreCase(r.request().method());
                            } catch (Throwable ignore) { return false; }
                        }, firstCard::click);
                    } else {
                        final Locator cardToClick = cards.nth(i);
                        detailResp = page.waitForResponse(r -> {
                            try {
                                return r.url() != null && r.url().contains("/wapi/zpgeek/job/detail.json")
                                        && "GET".equalsIgnoreCase(r.request().method());
                            } catch (Throwable ignore) { return false; }
                        }, cardToClick::click);
                    }
                } catch (Exception e) {
                    log.warn("【{}】第 {} 个岗位详情加载失败，跳过当前岗位: {}", keyword, i + 1, e.getMessage());
                    if (progressCallback != null) {
                        progressCallback.accept("岗位详情加载失败，跳过当前岗位", i + 1, count);
                    }
                    continue;
                }
                PlaywrightUtil.sleep(1);

                // 统一从请求返回的 JSON 中获取数据并做过滤
                String jobName = null;
                String jobSalary = null;
                java.util.List<String> tags = new java.util.ArrayList<>();
                String jobDesc = null;
                String bossName = null;
                String bossActive = null;
                String bossCompany = null;
                String bossJobTitle = null;
                JSONObject matchedJobInfo = null;

                if (detailResp != null) {
                    try {
                        String body = detailResp.text();
                        // 保存原始 JSON 便于调试
                        appendRawJson(body);
                        // 解析并入库（仅在点击卡片触发时执行）
                        processJobDetailJsonAndInsert(body);

                        // 从 JSON 构建用于投递与过滤的字段
                        org.json.JSONObject root = new org.json.JSONObject(body);
                        org.json.JSONObject zpData = root.optJSONObject("zpData");
                        org.json.JSONObject jobInfo = zpData != null ? zpData.optJSONObject("jobInfo") : null;
                        org.json.JSONObject brand = zpData != null ? zpData.optJSONObject("brandComInfo") : null;
                        org.json.JSONObject boss = zpData != null ? zpData.optJSONObject("bossInfo") : null;

                        if (jobInfo != null) {
                            matchedJobInfo = jobInfo;
                            jobName = jobInfo.optString("jobName", "");
                            jobSalary = jobInfo.optString("salaryDesc", "");
                            String city = jobInfo.optString("locationName", "");
                            String exp = jobInfo.optString("experienceName", "");
                            String deg = jobInfo.optString("degreeName", "");
                            if (!city.isEmpty()) tags.add(city);
                            if (!exp.isEmpty()) tags.add(exp);
                            if (!deg.isEmpty()) tags.add(deg);
                            jobDesc = jobInfo.optString("postDescription", "");
                        }

                        if (boss != null) {
                            bossName = boss.optString("name", "");
                            bossActive = boss.optString("activeTimeDesc", "");
                            bossJobTitle = boss.optString("title", "");
                        }

                        if (brand != null) {
                            bossCompany = brand.optString("brandName", "");
                        }
                    } catch (Throwable e) {
                        log.debug("点击卡片后解析岗位详情用于过滤失败：{}", e.getMessage());
                    }
                }

                // 没有有效详情时不能沿用页面上一个岗位的详情链接发起沟通。
                if (jobName == null || jobName.isBlank()) {
                    log.warn("【{}】第 {} 个岗位未取得有效详情，跳过当前岗位", keyword, i + 1);
                    continue;
                }

                // Boss 的 query 参数是模糊/全文搜索，AI应用会把教育产品等泛岗位也返回。
                // 只对 AI 类关键词增加岗位相关性过滤，普通关键词保持原有行为。
                AiJobRelevance.Decision aiRelevance = AiJobRelevance.evaluate(keyword, jobName, jobDesc);
                if (aiRelevance.applies() && !aiRelevance.matched()) {
                    log.info("被过滤：AI岗位相关性不足 | 公司：{} | 岗位：{} | 原因：{}",
                            bossCompany != null ? bossCompany : "", jobName, aiRelevance.reason());
                    continue;
                }

                // 过滤（全部基于 JSON 字段），并输出过滤原因
                if (jobName != null && blackJobs != null && blackJobs.stream().anyMatch(jobName::contains)) {
                    String term = findMatchedTerm(blackJobs, jobName);
                    log.info("被过滤：职位黑名单命中 | 公司：{} | 岗位：{} | 关键词：{}", bossCompany != null ? bossCompany : "", jobName, term != null ? term : "");
                    continue;
                }
                // HR活跃状态过滤：当开启过滤开关且活跃描述为“N年前活跃”时，视为不活跃。
                // 注意不能用 contains("年")：Boss 的 activeTimeDesc 有“近半年活跃”“半年前活跃”，
                // 含“年”但 HR 实际在半年内活跃，误伤会过滤掉近半数候选岗位。
                boolean hrInactiveByYear = isHrInactiveByYear(bossActive);
                if (Boolean.TRUE.equals(config.getFilterDeadHR()) && hrInactiveByYear) {
                    log.info("被过滤：HR活跃状态为N年前活跃 | 公司：{} | 岗位：{} | 活跃：{}", bossCompany != null ? bossCompany : "", jobName != null ? jobName : "", bossActive);
                    continue;
                }
                if (bossCompany != null && blackCompanies != null && blackCompanies.stream().anyMatch(bossCompany::contains)) {
                    String term = findMatchedTerm(blackCompanies, bossCompany);
                    log.info("被过滤：公司黑名单命中 | 公司：{} | 岗位：{} | 关键词：{}", bossCompany, jobName != null ? jobName : "", term != null ? term : "");
                    continue;
                }
                if (bossJobTitle != null && blackRecruiters != null && blackRecruiters.stream().anyMatch(bossJobTitle::contains)) {
                    String term = findMatchedTerm(blackRecruiters, bossJobTitle);
                    log.info("被过滤：招聘者黑名单命中 | 公司：{} | 岗位：{} | 招聘者：{} | 关键词：{}", bossCompany != null ? bossCompany : "", jobName != null ? jobName : "", bossJobTitle, term != null ? term : "");
                    continue;
                }

                // 最终筛选通过后即留存；不依赖后续是否能沟通或成功发送。
                archiveMatchedJd(keyword, cityCode, expectedJdId, matchedJobInfo, bossCompany);

                // 创建Job对象（全部基于 JSON 字段）
                Job job = new Job();
                job.setJobName(jobName != null ? jobName : "");
                job.setSalary(jobSalary != null ? jobSalary : "");
                job.setJobArea(String.join(", ", tags));
                job.setCompanyName(bossCompany != null ? bossCompany : "");
                job.setRecruiter(bossName != null ? bossName : "");
                job.setJobInfo(jobDesc != null ? jobDesc : "");

                // 输出
                progressCallback.accept("正在投递：" + jobName, i + 1, count);
                int deliveredBefore = resultList.size();
                try {
                    // 单个岗位的页面结构、风控或发送异常不能终止整个关键词的投递。
                    resumeSubmission(keyword, job);
                } catch (Exception e) {
                    if (e instanceof QuietDeliveryFailure failure) throw failure;
                    log.warn("岗位投递异常，跳过当前岗位继续下一个 | 公司：{} | 岗位：{} | 原因：{}",
                            job.getCompanyName(), job.getJobName(), e.getMessage(), e);
                    if (progressCallback != null) {
                        progressCallback.accept("岗位跳过：" + jobName + "（" + e.getMessage() + "）", i + 1, count);
                    }
                }
                postCount += resultList.size() - deliveredBefore;
                if (hasReachedTaskLimit()) return;

                // 为避免点击下面的卡片触发页面刷新：在点击5个卡片之后，每次点击后适度下滑
                try {
                    if (i >= 5) {
                        page.evaluate("window.scrollBy(0, 140);");
                        PlaywrightUtil.sleep(1);
                    }
                } catch (Throwable ignore) {}

                // 按 wait_time 降速，别把风控刷出来
                pauseBetweenJobs();

                // 停顿期间可能已经被弹到安全验证页，发现了就停下来等人工过验证，
                // 而不是继续闷头点下去（继续点只会让后面的关键词全部失败）
                if (isSecurityVerifyUrl(safeUrl())) {
                    log.warn("【{}】遍历过程中被跳转到安全验证页：{}", keyword, safeUrl());
                    if (progressCallback != null) {
                        progressCallback.accept("触发Boss安全校验，请在浏览器中手动完成验证", i + 1, count);
                    }
                    waitForSliderVerify(page);
                }
            }
            log.info("【{}】岗位已投递完毕！已投递岗位数量:{}", keyword, postCount);
            } catch (Exception e) {
                if (e instanceof QuietDeliveryFailure failure) throw failure;
                log.error("【{}】处理失败，跳过该关键词继续下一个：{}", keyword, e.getMessage(), e);
                if (progressCallback != null) {
                    progressCallback.accept("关键词[" + keyword + "]失败已跳过：" + e.getMessage(), 0, 0);
                }
            }
        }
    }

    private String cardJobId(Locator card) {
        try {
            Locator link = card.locator("a[href*='/job_detail/']");
            if (link.count() > 0) return extractEncryptId(link.first().getAttribute("href"));
        } catch (Exception ignored) { }
        return null;
    }

    private void archiveMatchedJd(String keyword, String searchCity, String expectedId,
                                  JSONObject info, String companyName) {
        if (jdStore == null) return;
        try {
            String id = info == null ? "" : info.optString("encryptId", "").trim();
            if (id.isEmpty() || info.optString("postDescription", "").isBlank()) {
                throw new IllegalArgumentException("岗位标识或JD正文缺失");
            }
            String visibleId = expectedId;
            if (visibleId == null || visibleId.isBlank()) {
                Locator more = page.locator("a.more-job-btn");
                if (more.count() > 0) visibleId = extractEncryptId(more.first().getAttribute("href"));
            }
            if (visibleId == null || !id.equals(visibleId)) {
                throw new IllegalArgumentException("无法确认详情与当前岗位一致");
            }
            String actualType = String.valueOf(info.opt("jobType"));
            if (!Set.of("1901", "1902", "1903").contains(actualType)) actualType = "unknown";
            String requestedType = config.getJobType() == null || config.getJobType().isEmpty()
                    ? "0" : config.getJobType().getFirst();
            // Only search/filter facts are archived, never greeting text, resume data or credentials.
            JSONObject filters = new JSONObject();
            filters.put("keyword", keyword);
            filters.put("searchCity", searchCity);
            filters.put("requestedJobType", requestedType);
            filters.put("jobTypeVerification", actualType.equals("unknown") ? "unverified" : "detail");
            filters.put("experience", config.getExperience());
            filters.put("degree", config.getDegree());
            filters.put("salary", config.getSalary());
            filters.put("industry", config.getIndustry());
            filters.put("scale", config.getScale());
            filters.put("stage", config.getStage());
            filters.put("filterDeadHR", Boolean.TRUE.equals(config.getFilterDeadHR()));
            filters.put("rulesVersion", "mitu-jd-match-v1");
            filters.put("finalFiltersPassed", true);
            BossJdStore.CaptureResult saved = jdStore.capture(new BossJdStore.Capture(jdTaskId,
                    keyword, searchCity, filters.toString(), id, info.optString("jobName", ""),
                    companyName == null ? "" : companyName, info.optString("locationName", ""),
                    info.optString("salaryDesc", ""), info.optString("experienceName", ""),
                    info.optString("degreeName", ""), actualType, info.optString("postDescription", ""), "matched"));
            switch (saved.outcome()) {
                case "created" -> jdCreated++;
                case "updated" -> jdUpdated++;
                default -> jdDuplicate++;
            }
            if (jdAnalysis != null && !saved.outcome().equals("duplicate")) {
                try { jdAnalysis.onCapture(jdTaskId); }
                catch (Exception failure) { log.warn("JD已归档，后台分析调度稍后重试"); }
            }
        } catch (Exception failure) {
            jdFailed++;
            log.warn("JD留存失败，继续处理岗位；当前失败数={}，原因={}", jdFailed,
                    failure instanceof IllegalArgumentException ? failure.getMessage() : "本地JD存储暂不可用");
        }
    }

    /**
     * 解析岗位详情 JSON 并进行入库与黑名单处理（只在点击卡片时调用）。
     */
    private void processJobDetailJsonAndInsert(String body) {
        if (body == null || body.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(body);
            JSONObject zpData = root.optJSONObject("zpData");
            if (zpData == null) return;

            JSONObject jobInfo = zpData.optJSONObject("jobInfo");
            JSONObject brand = zpData.optJSONObject("brandComInfo");
            JSONObject bossInfo = zpData.optJSONObject("bossInfo");
            if (jobInfo == null) return;

            String encryptId = jobInfo.optString("encryptId", null);
            String encryptUserId = jobInfo.optString("encryptUserId", null);
            if (encryptUserId == null && bossInfo != null) {
                // 兼容部分页面字段落在 bossInfo 内
                encryptUserId = bossInfo.optString("encryptUserId", null);
                if (encryptUserId == null) {
                    // 进一步兼容可能的字段命名
                    encryptUserId = bossInfo.optString("encryptBossId", null);
                }
            }
            if (encryptId != null && encryptUserId != null) {
                encryptIdToUserId.put(encryptId, encryptUserId);
            }

            com.getjobs.application.entity.BossJobDataEntity entity = new com.getjobs.application.entity.BossJobDataEntity();
            entity.setJobName(jobInfo.optString("jobName", null));
            entity.setSalary(jobInfo.optString("salaryDesc", null));
            entity.setLocation(jobInfo.optString("locationName", null));
            entity.setExperience(jobInfo.optString("experienceName", null));
            entity.setDegree(jobInfo.optString("degreeName", null));
            entity.setJobDescription(jobInfo.optString("postDescription", null));
            entity.setRecruitmentStatus(jobInfo.optString("jobStatusDesc", null));
            entity.setCompanyAddress(jobInfo.optString("address", null));
            entity.setEncryptId(encryptId);
            entity.setEncryptUserId(encryptUserId);

            entity.setCompanyName(brand != null ? brand.optString("brandName", null) : null);
            entity.setIndustry(brand != null ? brand.optString("industryName", null) : null);
            entity.setIntroduce(brand != null ? brand.optString("introduce", null) : null);
            entity.setFinancingStage(brand != null ? brand.optString("stageName", null) : null);
            entity.setCompanyScale(brand != null ? brand.optString("scaleName", null) : null);

            entity.setHrName(bossInfo != null ? bossInfo.optString("name", null) : null);
            entity.setHrPosition(bossInfo != null ? bossInfo.optString("title", null) : null);
            entity.setHrActiveStatus(bossInfo != null ? bossInfo.optString("activeTimeDesc", null) : null);

            if (encryptId != null && !encryptId.isEmpty()) {
                entity.setJobUrl("https://www.zhipin.com/job_detail/" + encryptId + ".html");
            }

            // 黑名单处理
            boolean filtered = false;
            String companyName = entity.getCompanyName() != null ? entity.getCompanyName() : "";
            String positionName = entity.getJobName() != null ? entity.getJobName() : "";
            String hrPosition = entity.getHrPosition() != null ? entity.getHrPosition() : "";
            try {
                if (blackCompanies != null && blackCompanies.stream().anyMatch(companyName::contains)) filtered = true;
                if (!filtered && blackJobs != null && blackJobs.stream().anyMatch(positionName::contains)) filtered = true;
                if (!filtered && blackRecruiters != null && blackRecruiters.stream().anyMatch(hrPosition::contains)) filtered = true;
            } catch (Throwable ignore) {}

            // HR活跃状态过滤：开启过滤且活跃描述为“N年前活跃”，则标记为已过滤，但仍入库。
            // 与投递循环使用同一判断，避免“半年前活跃”被误伤。
            if (!filtered && Boolean.TRUE.equals(config.getFilterDeadHR())) {
                String hrActive = entity.getHrActiveStatus();
                if (isHrInactiveByYear(hrActive)) {
                    filtered = true;
                }
            }

            entity.setDeliveryStatus(filtered ? "已过滤" : "未投递");

            // 入库（若不存在），优先以 encrypt_id + encrypt_user_id 去重；若 userId 缺失，则以 encrypt_id 去重
            if (encryptId != null) {
                try {
                    boolean exists = false;
                    if (encryptUserId != null) {
        exists = bossService.existsBossJob(encryptId, encryptUserId);
                    } else {
        exists = bossService.existsBossJobByEncryptId(encryptId);
                    }
                    if (!exists) {
        bossService.insertBossJob(entity);
                        log.debug("岗位入库：{} | 公司：{} | HR：{} | 状态：{}", entity.getJobName(), entity.getCompanyName(), entity.getHrName(), entity.getDeliveryStatus());
                    }
                } catch (Exception e) {
                    log.warn("岗位入库失败：{}", e.getMessage());
                }
            }
        } catch (Throwable e) {
            log.debug("解析岗位详情 JSON 失败：{}", e.getMessage());
        }
    }

    public String decodeSalary(String text) {
        Map<Character, Character> fontMap = new HashMap<>();
        fontMap.put('\uE8F0', '0');
        fontMap.put('\uE8F1', '1');
        fontMap.put('\uE8F2', '2');
        fontMap.put('\uE8F3', '3');
        fontMap.put('\uE8F4', '4');
        fontMap.put('\uE8F5', '5');
        fontMap.put('\uE8F6', '6');
        fontMap.put('\uE8F7', '7');
        fontMap.put('\uE8F8', '8');
        fontMap.put('\uE8F9', '9');
        StringBuilder result = new StringBuilder();
        for (char c : text.toCharArray()) {
            result.append(fontMap.getOrDefault(c, c));
        }
        return result.toString();
    }

    // 安全获取单个文本内容
    public String safeText(Locator root, String selector) {
        Locator node = root.locator(selector);
        try {
            if (node.count() > 0 && node.innerText() != null) {
                return node.innerText().trim();
            }
        } catch (Exception e) {
            // ignore
        }
        return "";
    }

    // 安全获取多个文本内容
    public List<String> safeAllText(Locator root, String selector) {
        try {
            return root.locator(selector).allInnerTexts();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    // Boss姓名+活跃状态拆分
    public String[] splitBossName(String raw) {
        String[] bossParts = raw.trim().split("\\s+");
        String bossName = bossParts[0];
        String bossActive = bossParts.length > 1 ? String.join(" ", Arrays.copyOfRange(bossParts, 1, bossParts.length)) : "";
        return new String[]{bossName, bossActive};
    }

    // Boss公司+职位拆分
    public String[] splitBossTitle(String raw) {
        String[] parts = raw.trim().split(" · ");
        String company = parts[0];
        String job = parts.length > 1 ? parts[1] : "";
        return new String[]{company, job};
    }

    // 匹配命中词条（用于日志输出过滤原因）
    private String findMatchedTerm(java.util.Collection<String> patterns, String text) {
        if (patterns == null || text == null) return null;
        try {
            for (String p : patterns) {
                if (p != null && !p.isEmpty() && text.contains(p)) {
                    return p;
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    /**
     * 判断 HR 活跃描述是否表示“按年计的不活跃”（如“1年前活跃”“2年前活跃”）。
     * Boss 的 activeTimeDesc 还会出现“近半年活跃”“半年前活跃”，其中虽含“年”
     * 但 HR 在半年内活跃，不能用简单的 contains("年") 判断，必须要求“年”前有数字。
     */
    private static boolean isHrInactiveByYear(String activeDesc) {
        return activeDesc != null && activeDesc.matches(".*\\d+\\s*年.*");
    }

    public static String buildSearchUrl(BossConfig config, String cityCode) {
        String baseUrl = "https://www.zhipin.com/web/geek/jobs";
        if (config == null) {
            return baseUrl;
        }
        List<String> params = new ArrayList<>();
        String effectiveCityCode = "0".equals(cityCode) ? NATIONWIDE_CITY_CODE : cityCode;
        addParam(params, JobUtils.appendParam("city", effectiveCityCode));
        addParam(params, JobUtils.appendListParam("jobType", config.getJobType()));
        addParam(params, JobUtils.appendListParam("salary", config.getSalary()));
        addParam(params, JobUtils.appendListParam("experience", config.getExperience()));
        addParam(params, JobUtils.appendListParam("degree", config.getDegree()));
        addParam(params, JobUtils.appendListParam("scale", config.getScale()));
        addParam(params, JobUtils.appendListParam("industry", config.getIndustry()));
        addParam(params, JobUtils.appendListParam("stage", config.getStage()));
        if (params.isEmpty()) {
            return baseUrl;
        }
        return baseUrl + "?" + String.join("&", params);
    }

    private static void addParam(List<String> params, String param) {
        if (param == null || param.isEmpty()) {
            return;
        }
        params.add(param.startsWith("&") ? param.substring(1) : param);
    }

    private String getSearchUrl(String cityCode) {
        return buildSearchUrl(config, cityCode);
    }

    /**
     * 进入岗位列表页。
     * <p>
     * 优先模拟真人路径：回首页 -> 在搜索框里逐字输入关键词 -> 点搜索按钮，
     * 而不是冷启动直接把拼好的列表页 URL 丢给浏览器。
     * <p>
     * UI 搜索只能带上关键词和首页当前城市，config 里的城市/薪资/经验等筛选项仍然只能靠 URL，
     * 所以落地之后如果条件对不上，会再做一次同标签页跳转 —— 此时已经是热会话 + 同源 referer，
     * 和冷启动直闯不是一回事。UI 搜索任何一步失败都会退回原来的直接跳转，不影响主流程。
     */
    private void openJobList(String keyword, String targetUrl, String cityCode) {
        boolean uiSearchDone = USE_UI_SEARCH && searchFromHomePage(keyword);
        if (!uiSearchDone) {
            navigateToJobList(targetUrl);
        } else if (needsUrlFilters(targetUrl, cityCode)) {
            log.info("【{}】UI搜索已落地，补充筛选条件跳转：{}", keyword, targetUrl);
            navigateToJobList(targetUrl);
        }
        warnIfSecurityCheck(keyword);
    }

    /**
     * 在 Boss 首页搜索框里做一次真实的 UI 搜索。
     *
     * @return 是否成功落到岗位列表页
     */
    private boolean searchFromHomePage(String keyword) {
        try {
            String current = page.url();
            if (current == null || !current.startsWith(BOSS_HOME_URL) || current.contains("/web/geek/")) {
                navigateTo(BOSS_HOME_URL);
            }
            // 首页落地后还会自己跳一次（/shanghai/?seoRefer=index 之类），
            // 不等它跳完就找搜索框，会卡在 "waiting for navigation to finish" 直到超时
            waitForPageSettled();

            Locator input = page.locator(HOME_SEARCH_INPUT).first();
            input.waitFor(new Locator.WaitForOptions().setTimeout(10_000));
            input.click();
            input.fill("");
            // 逐字输入，模拟真人打字节奏；一次性 fill 在输入行为层面太干净了
            input.pressSequentially(keyword, new Locator.PressSequentiallyOptions().setDelay(140));
            PlaywrightUtil.sleep(1);

            Locator searchBtn = page.locator(HOME_SEARCH_BUTTON).first();
            if (searchBtn.count() > 0) {
                searchBtn.click();
            } else {
                input.press("Enter");
            }
            // 表单提交是当前标签页跳转；若 Boss 改成新开标签页，这里会超时并退回直接跳转
            page.waitForURL("**/web/geek/jobs**", new Page.WaitForURLOptions().setTimeout(15_000));
            log.info("【{}】已通过首页搜索框进入岗位列表：{}", keyword, page.url());
            return true;
        } catch (Exception e) {
            log.warn("【{}】首页UI搜索失败，退回直接跳转：{}", keyword, e.getMessage());
            return false;
        }
    }

    /**
     * UI 搜索落地后，判断还需不需要用 URL 把筛选条件补上。
     */
    private boolean needsUrlFilters(String targetUrl, String cityCode) {
        String landed = page.url();
        if (landed == null) {
            return true;
        }
        // 首页搜索用的是首页当前城市，不一定等于配置里的城市
        if (cityCode != null && !cityCode.isEmpty() && !landed.contains("city=" + cityCode)) {
            return true;
        }
        // 除 city/query 外还有别的筛选项（薪资、经验、学历……），UI 搜索带不上
        int queryStart = targetUrl.indexOf('?');
        if (queryStart < 0) {
            return false;
        }
        for (String param : targetUrl.substring(queryStart + 1).split("&")) {
            int eq = param.indexOf('=');
            String name = eq < 0 ? param : param.substring(0, eq);
            String value = eq < 0 ? "" : param.substring(eq + 1);
            if (value.isEmpty() || "city".equals(name) || "query".equals(name)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private void navigateToJobList(String url) {
        navigateTo(url);
    }

    /**
     * 导航并重试一次。
     * <p>
     * Boss 页面自己会做客户端跳转，撞上时 Playwright 报 net::ERR_ABORTED；
     * 这类失败重试一次基本就过了，不该让整个投递任务因此中止。
     */
    private void navigateTo(String url) {
        // Boss 的 SPA 在网络不佳或被限流时，15 秒常常不够，之前实测连着两次都超时
        Page.NavigateOptions options = new Page.NavigateOptions()
                .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                .setTimeout(45_000);
        try {
            page.navigate(url, options);
        } catch (Exception first) {
            // 导航期间页面自己又跳了一次时，Playwright 会抛 "Object doesn't exist: request@/frame@"，
            // 但页面其实已经到位了。先看落地 URL，别白白重试一遍。
            if (landedOn(url)) {
                log.debug("导航报了失效对象异常但页面已到位，忽略：{}", first.getMessage());
                settleAfterNavigation();
                return;
            }
            log.warn("导航失败，1秒后重试一次：{} | {}", url, first.getMessage());
            PlaywrightUtil.sleep(1);
            try {
                page.navigate(url, options);
            } catch (Exception second) {
                if (!landedOn(url)) {
                    throw second;
                }
                log.debug("重试同样报失效对象异常但页面已到位，忽略：{}", second.getMessage());
            }
        }
        settleAfterNavigation();
    }

    /**
     * 判断页面是否已经落在目标地址上（只比较路径，查询参数会被 Boss 改写）。
     */
    private boolean landedOn(String targetUrl) {
        try {
            String current = page.url();
            if (current == null) {
                return false;
            }
            String targetPath = targetUrl.split("\\?")[0];
            return current.startsWith(targetPath);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 等页面真正稳定下来再继续。
     * <p>
     * DOMCONTENTLOADED 返回之后 Boss 还会自己做客户端跳转，此时 frame 被替换，
     * 后续任何 locator 调用都会报 "Object doesn't exist: frame@..."。
     */
    private void settleAfterNavigation() {
        waitForPageSettled();
    }

    /**
     * 等页面进入 domcontentloaded，最多等 {@value #LOAD_STATE_TIMEOUT} 毫秒。
     * <p>
     * 两个坑都踩过了：
     * 一是不能等 LOAD/NETWORKIDLE —— Boss 是 SPA + WebSocket 长连接，这两个状态可能永远不到；
     * 二是必须显式给超时 —— 不给超时就是无限期挂起，而"卡住"不是异常，
     * 外面包 try/catch 完全没用（实测把线程挂了两分钟以上还在等）。
     */
    private void waitForPageSettled() {
        waitForPageSettled(page);
    }

    private void waitForPageSettled(Page candidate) {
        if (candidate == null) return;
        try {
            candidate.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(LOAD_STATE_TIMEOUT));
        } catch (Exception ignore) {
            // 等不到就算了，后面的 locator 调用自己有超时
        }
        PlaywrightUtil.sleep(2);
    }

    /**
     * 判断是不是 Playwright 的"对象已失效"异常。
     * <p>
     * 页面频繁增删 frame 时，Playwright Java 在派发事件时会引用到已经销毁的对象，
     * 抛 "Object doesn't exist: frame@..."。这个异常和当时在飞的那个调用没有因果关系，
     * 连接本身仍然可用，重试即可。
     */
    private static boolean isStaleObjectError(Throwable e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        // 两类都是"页面在动"导致的瞬时异常，跟调用本身没有因果关系，重试就好：
        // - Object doesn't exist: frame@/request@  事件派发时引用到已销毁的对象
        // - Execution context was destroyed         求值期间页面发生了导航
        return message.contains("Object doesn't exist")
                || message.contains("Execution context was destroyed");
    }

    /**
     * 进入岗位列表并等列表渲染出来，整段带重试。
     * Boss 首页/列表页在登录态下会连着跳好几次，一次失败很正常。
     */
    private void openJobListWithRetry(String keyword, String targetUrl, String cityCode) {
        // 只重试一次：每次都要走导航(最长45秒×2) + 等列表(15秒×3)，
        // 试三轮的话一个关键词失败要耗掉好几分钟，界面上看着就是"卡住不动"
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                openJobList(keyword, targetUrl, cityCode);
                waitForJobList();
                return;
            } catch (RuntimeException e) {
                if (e instanceof QuietDeliveryFailure failure) throw failure;
                last = e;
                log.warn("【{}】进入岗位列表失败（第{}/2次）：{}", keyword, attempt,
                        e.getMessage() == null ? e.toString() : e.getMessage().split("\n")[0]);
                if (attempt < 2) {
                    PlaywrightUtil.sleep(3);
                }
            }
        }
        throw last;
    }

    /**
     * 统计当前列表里的岗位卡片数量，对失效对象异常做重试。
     */
    private int countJobCards() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return page.locator(JOB_LIST_SELECTOR).count();
            } catch (Exception e) {
                if (!isStaleObjectError(e) || attempt == 3) {
                    if (isStaleObjectError(e)) {
                        log.warn("统计岗位数量始终失败，按 0 处理：{}", e.getMessage());
                        return 0;
                    }
                    throw e;
                }
                PlaywrightUtil.sleep(1);
            }
        }
        return 0;
    }

    /**
     * 等待岗位列表容器出现，带重试。
     * 页面在这期间可能还在跳转，一次失败不代表真的没有列表。
     */
    private void waitForJobList() {
        // Boss 改版频繁，推荐页和搜索结果页的容器类名不一样。
        // 用逗号把候选选择器拼成一个，让 Playwright 一次性等"任意一个先出现"，
        // 不要逐个 8 秒串行试 —— 那样一轮就要 48 秒，页面正常时也慢得像卡死。
        String containers = String.join(", ",
                "ul.rec-job-list",
                "ul.job-list-box",
                ".job-list-box",
                ".search-job-result",
                "li.job-card-box",
                "li.job-card-wrapper");

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                page.waitForSelector(containers,
                        new Page.WaitForSelectorOptions().setTimeout(15_000));
                log.info("岗位列表容器已出现（第{}次尝试）", attempt);
                return;
            } catch (Exception e) {
                log.warn("等待岗位列表失败（第{}/3次），当前页面: {}", attempt, safeUrl());
                settleAfterNavigation();
            }
        }
        dumpListPageStructure();
        throw new IllegalStateException("岗位列表始终未出现，当前页面: " + safeUrl());
    }

    private String safeUrl() {
        try {
            return page.url();
        } catch (Exception e) {
            return "(取不到URL)";
        }
    }

    /**
     * 列表容器一个都没命中时，把页面上的候选列表结构打出来，方便修选择器。
     */
    private void dumpListPageStructure() {
        try {
            Object info = page.evaluate("""
                    () => {
                      const uls = Array.from(document.querySelectorAll('ul,div'))
                        .filter(el => el.className && typeof el.className === 'string'
                                   && /job|list|rec/i.test(el.className))
                        .slice(0, 15)
                        .map(el => el.tagName.toLowerCase() + '.' + el.className.trim().replace(/\\s+/g, '.')
                                 + ' (children=' + el.childElementCount + ')');
                      return { title: document.title, url: location.href, candidates: uls };
                    }""");
            log.warn("列表页结构快照: {}", info);
        } catch (Exception e) {
            log.warn("抓取列表页结构失败: {}", e.getMessage());
        }
    }

    /**
     * 落地后检查是否被弹到风控/验证页，日志里能直接看出是哪一步触发的。
     */
    private void warnIfSecurityCheck(String keyword) {
        String url = page.url();
        if (url == null) {
            return;
        }
        if (isSecurityVerifyUrl(url) || (quietDelivery
                && com.getjobs.application.service.BossQuietService.requiresHuman(url))) {
            log.warn("【{}】进入岗位列表时被跳转到验证页：{}", keyword, url);
            if (progressCallback != null) {
                progressCallback.accept("触发Boss安全校验，请手动完成验证", 0, 0);
            }
            waitForSliderVerify(page);
        }
    }

    /** Creates at most one live detail page in quiet mode, within the existing logged-in context. */
    private Page acquireDeliveryPage() {
        if (!quietDelivery) return page.context().newPage();
        try {
            if (reusableDeliveryPage != null && !reusableDeliveryPage.isClosed()) return reusableDeliveryPage;
            if (quietPageFactory == null) throw new QuietDeliveryFailure("静默投递页面创建器尚未就绪，任务已停止");
            Page created = quietPageFactory.get();
            if (created == null || created == page || created.isClosed() || created.context() != page.context()) {
                throw new QuietDeliveryFailure("静默投递未取得同一登录上下文内的独立页面，任务已停止");
            }
            quietOwnedRoots.add(created);
            reusableDeliveryPage = created;
            return created;
        } catch (QuietDeliveryFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw new QuietDeliveryFailure("静默投递页面不可用，任务已停止，请查看浏览器后重试", failure);
        }
    }

    private void quietCheckpoint() {
        if (!quietDelivery || quietCheckpoint == null) return;
        try { quietCheckpoint.run(); }
        catch (QuietDeliveryFailure failure) { throw failure; }
        catch (Exception failure) { throw new QuietDeliveryFailure("静默任务控制未完成，任务已停止", failure); }
    }

    /** Only descendants opened by this task's pages are disposable, never manually opened user tabs. */
    private boolean isQuietOwnedPopup(Page candidate) {
        if (candidate == null || candidate == page || quietOwnedRoots.contains(candidate)) return false;
        Set<Page> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            Page ancestor = candidate;
            while (ancestor != null && visited.add(ancestor)) {
                ancestor = ancestor.opener();
                if (ancestor == page || quietOwnedRoots.contains(ancestor)) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private void observeQuietPopup(Page popup) {
        if (quietDelivery && isQuietOwnedPopup(popup) && quietObservedPopups.add(popup) && quietPopupHandler != null) {
            try { quietPopupHandler.accept(popup); }
            catch (Exception failure) { throw new QuietDeliveryFailure("平台附属页无法保持后台，任务已停止，请查看浏览器", failure); }
        }
    }

    private List<Page> chatCandidatePages(Page preferred) {
        List<Page> candidates = new ArrayList<>();
        candidates.add(preferred);
        try {
        for (Page candidate : preferred.context().pages()) {
            if (candidate == null || candidates.contains(candidate)) continue;
            if (!quietDelivery || candidate == reusableDeliveryPage || isQuietOwnedPopup(candidate)) {
                observeQuietPopup(candidate);
                candidates.add(candidate);
            }
        }
        } catch (QuietDeliveryFailure failure) {
            throw failure;
        } catch (Exception failure) {
            log.debug("枚举聊天候选页失败，继续检查当前页：{}", failure.getMessage());
        }
        return candidates;
    }

    private void releasePerJobPage(Page candidate) {
        if (candidate == null || candidate == page) return;
        if (quietDelivery && !isQuietOwnedPopup(candidate)) return;
        try { candidate.close(new Page.CloseOptions().setRunBeforeUnload(false)); }
        catch (Exception failure) { log.debug("清理本次投递临时页面失败：{}", failure.getMessage()); }
    }

    private void cleanupQuietPopups() {
        if (!quietDelivery || page == null) return;
        try {
            for (Page candidate : new ArrayList<>(page.context().pages())) {
                if (isQuietOwnedPopup(candidate)) releasePerJobPage(candidate);
            }
        } catch (Exception failure) { log.debug("枚举静默投递临时页面失败：{}", failure.getMessage()); }
    }

    private void closeReusableDeliveryPage() {
        cleanupQuietPopups();
        for (Page owned : new ArrayList<>(quietOwnedRoots)) {
            try { if (!owned.isClosed()) owned.close(new Page.CloseOptions().setRunBeforeUnload(false)); }
            catch (Exception failure) { log.warn("任务结束后关闭静默投递页失败：{}", failure.getMessage()); }
        }
        reusableDeliveryPage = null;
        quietOwnedRoots.clear();
        quietObservedPopups.clear();
    }

    /**
     * 投递单个岗位，并回收本次调用新打开的临时页；静默模式保留任务专用页。
     * 从“查看更多信息”进入详情页后再打开聊天按钮。
     *
     * <p>岗位详情页中的任一步骤都可能因为页面刷新、风控或 DOM 差异抛出异常。
     * 统一在这里清理本次新增页面，避免异常后残留聊天页影响下一个岗位。</p>
     */
    private void resumeSubmission(String keyword, Job job) {
        if (page == null || page.context() == null) {
            throw new IllegalStateException("Boss页面未初始化");
        }
        List<Page> pagesBefore = new ArrayList<>(page.context().pages());
        try {
            resumeSubmissionInternal(keyword, job);
        } finally {
            if (quietDelivery) {
                cleanupQuietPopups();
            } else {
            try {
                for (Page opened : new ArrayList<>(page.context().pages())) {
                    if (!pagesBefore.contains(opened)) {
                        try {
                            opened.close(new Page.CloseOptions().setRunBeforeUnload(false));
                        } catch (Exception closeEx) {
                            log.debug("清理岗位详情页失败: {}", closeEx.getMessage());
                        }
                    }
                }
            } catch (Exception pagesEx) {
                log.debug("枚举岗位详情页失败: {}", pagesEx.getMessage());
            }
            }
        }
    }

    @SneakyThrows
    private void resumeSubmissionInternal(String keyword, Job job) {
        quietCheckpoint();
        // 若收到停止指令，直接短路返回
        if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
            log.info("停止指令已触发，跳过投递 | 公司：{} | 岗位：{}", job.getCompanyName(), job.getJobName());
            return;
        }
        // 调试模式：仅遍历不投递
        if (Boolean.TRUE.equals(config.getDebugger())) {
            log.info("调试模式：仅遍历岗位，不投递 | 公司：{} | 岗位：{}", job.getCompanyName(), job.getJobName());
            return;
        }
        if (hasReachedDeliveryLimit()) return;

        // 在点击“立即沟通”前检查；该按钮本身就可能发送 Boss 的默认招呼。
        if (config.getSayHi() == null || config.getSayHi().isBlank()) {
            throw new IllegalArgumentException("固定招呼语为空，请保存配置后重新开始投递");
        }

        // 1. 查找"查看更多信息"按钮（必须存在且新开页）
        Locator moreInfoBtn = page.locator("a.more-job-btn");
        if (moreInfoBtn.count() == 0) {
            log.warn("未找到\"查看更多信息\"按钮，跳过...");
            return;
        }
        // 强制用js新开tab
        String href = moreInfoBtn.first().getAttribute("href");
        if (href == null || !href.startsWith("/job_detail/")) {
            log.warn("未获取到岗位详情链接，跳过...");
            return;
        }
        String detailUrl = "https://www.zhipin.com" + href;
        // 静默模式只导航任务专用页，普通模式沿用逐岗位新建详情页。
        Page detailPage = acquireDeliveryPage();
        detailPage.navigate(detailUrl);
        if (quietDelivery && isSecurityVerifyUrl(detailPage.url())) {
            waitForSliderVerify(detailPage);
            if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) return;
        }
        PlaywrightUtil.sleep(1);

        // 3. 查找"立即沟通"按钮
        Locator chatBtn = detailPage.locator("a.btn-startchat, a.op-btn-chat");
        boolean foundChatBtn = false;
        for (int i = 0; i < 5; i++) {
            if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                log.info("停止指令已触发，结束查找聊天按钮 | 公司：{} | 岗位：{}", job.getCompanyName(), job.getJobName());
                releasePerJobPage(detailPage);
                return;
            }
            if (chatBtn.count() > 0 && (chatBtn.first().textContent().contains("立即沟通"))) {
                foundChatBtn = true;
                break;
            }
            PlaywrightUtil.sleep(1);
        }
        if (!foundChatBtn) {
            log.warn("未找到立即沟通按钮，跳过岗位: {}", job.getJobName());
            // 关闭详情页
            releasePerJobPage(detailPage);
            return;
        }
        // Boss 的“立即沟通”会立即发送账号默认招呼，不能先点击再替换文本。
        // 先使用同源协议保存本岗位自定义招呼，再建立好友关系，让 Boss 首条消息直接使用该文本。
        if (hasReachedDeliveryLimit()) return;
        quietCheckpoint();
        if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) return;
        deliveryAttemptCount++;
        // 4. AI智能生成打招呼语（协议请求必须在点击“立即沟通”之前完成）
        String aiMessage = null;
        if (Boolean.TRUE.equals(config.getEnableAI())) {
            String jd = job.getJobInfo();
            if (jd != null && !jd.isEmpty()) {
                aiMessage = generateAiMessage(keyword, job.getJobName(), jd);
            }
        }
        String message = isValidString(aiMessage) ? aiMessage : config.getSayHi();

        ProtocolGreetingResult protocolResult = sendGreetingViaProtocol(detailPage, extractEncryptId(detailUrl), message);
        boolean defaultGreetingSent = false;
        boolean customGreetingSent = protocolResult == ProtocolGreetingResult.SENT;
        Page chatPage = detailPage;
        if (protocolResult != ProtocolGreetingResult.SENT) {
            // 协议接口可能随 Boss 改版暂时不可用。保留平台原生流程作为兜底，
            // 避免“协议失败后连默认招呼也完全不发送”。此分支发送的是 Boss 账号默认招呼，
            // 不把它误报为固定招呼成功。
            DefaultGreetingResult fallback = sendDefaultGreetingFallback(detailPage, chatBtn, job.getJobName());
            defaultGreetingSent = fallback.sent;
            chatPage = fallback.chatPage != null ? fallback.chatPage : detailPage;
            if (!defaultGreetingSent) {
                log.warn("协议模式未发送固定招呼，默认招呼兜底也未确认 | 岗位：{} | 结果：{}",
                        job.getJobName(), protocolResult);
                return;
            }
            defaultGreetingCount++;
            log.warn("协议模式未发送固定招呼，已回退到Boss默认招呼 | 岗位：{} | 结果：{}",
                    job.getJobName(), protocolResult);

            // 历史版本在原生沟通动作后直接操作聊天编辑器，可以发送超过
            // Boss 首条招呼100字限制的完整固定信息。协议失败时沿用该兼容路径，
            // 将完整文本作为聊天消息发送，并回读消息确认，避免只发默认短招呼。
            customGreetingSent = sendCustomChatMessage(chatPage, message, job);
            if (!customGreetingSent) {
                log.warn("Boss默认招呼已发送，但固定长信息未确认 | 岗位：{} | 页面：{}",
                        job.getJobName(), describeOpenPages());
            }
        }
        if (customGreetingSent) successfulGreetingCount++;
        boolean sendSuccess = defaultGreetingSent || customGreetingSent;
        log.info("投递沟通已确认 | 岗位：{} | 模式：{}", job.getJobName(),
                protocolResult == ProtocolGreetingResult.SENT
                        ? "固定招呼协议"
                        : (customGreetingSent ? "Boss默认招呼后发送固定信息" : "Boss默认招呼兜底"));

        // 8. 发送图片简历（可选）
        boolean imgResume = false;
        if (Boolean.TRUE.equals(config.getSendImgResume())) {
            imgResume = sendImageResume(chatPage);
        }

        log.info("投递处理完成 | 公司：{} | 岗位：{} | 薪资：{} | 固定招呼语确认：{} | 图片简历：{}",
                job.getCompanyName(), job.getJobName(), job.getSalary(),
                customGreetingSent ? "已确认" : "未确认（默认招呼）",
                imgResume ? "已发送" : "未发送");

        // 9. 关闭新打开的详情页
        if (chatPage != detailPage) releasePerJobPage(chatPage);
        if (detailPage != chatPage) releasePerJobPage(detailPage);
        PlaywrightUtil.sleep(1);

        // 10. 更新数据库投递状态 & 成功投递加入结果
        if (sendSuccess) {
            // 从详情链接提取 encrypt_id，并映射到 encrypt_user_id
            String encryptId = extractEncryptId(detailUrl);
            String encryptUserId = encryptId != null ? encryptIdToUserId.get(encryptId) : null;
            if (encryptId != null && encryptUserId != null) {
                try {
        bossService.updateDeliveryStatus(encryptId, encryptUserId, "已投递");
                    log.info("投递成功 | 公司：{} | 岗位：{} | encryptId：{} | encryptUserId：{}", job.getCompanyName(), job.getJobName(), encryptId, encryptUserId);
                } catch (Exception e) {
                    log.warn("更新投递状态为已投递失败：{}", e.getMessage());
                }
            } else {
                log.debug("未能找到 encryptId/encryptUserId 用于更新投递状态，detailUrl: {}", detailUrl);
            }
            resultList.add(job);
        } else {
            // 若发生发送失败，也进行状态更新
            String encryptId = extractEncryptId(detailUrl);
            String encryptUserId = encryptId != null ? encryptIdToUserId.get(encryptId) : null;
            if (encryptId != null && encryptUserId != null) {
                try {
        bossService.updateDeliveryStatus(encryptId, encryptUserId, "投递失败");
                    log.warn("投递失败 | 公司：{} | 岗位：{} | encryptId：{} | encryptUserId：{}", job.getCompanyName(), job.getJobName(), encryptId, encryptUserId);
                } catch (Exception e) {
                    log.warn("更新投递状态为投递失败异常：{}", e.getMessage());
                }
            }
        }
    }

    /**
     * 通过 Boss 同源协议保存本岗位招呼语并发起沟通。
     * “立即沟通”会直接发送账号默认招呼，因此必须先调用 custom/save，再调用 friend/add。
     */
    private ProtocolGreetingResult sendGreetingViaProtocol(Page detailPage, String encryptId, String message) {
        if (detailPage == null || encryptId == null || encryptId.isBlank() || message == null || message.isBlank()) {
            return ProtocolGreetingResult.UNAVAILABLE;
        }
        if (message.length() > 100) {
            log.warn("固定招呼语超过Boss首条招呼100字限制，将使用Boss默认招呼兜底 | 当前长度={}", message.length());
            return ProtocolGreetingResult.UNAVAILABLE;
        }
        try {
            Map<String, String> ids = null;
            for (int attempt = 0; attempt < 5 && (ids == null || ids.get("securityId") == null); attempt++) {
                Object rawIds = detailPage.evaluate("""
                        () => {
                          const nodes = Array.from(document.querySelectorAll('a[href], [data-security-id], [data-securityid]'));
                          for (const node of nodes) {
                            const href = node.getAttribute('href') || '';
                            const dataSecurity = node.getAttribute('data-security-id') || node.getAttribute('data-securityid') || '';
                            try {
                              const url = new URL(href || location.href, location.href);
                              const securityId = url.searchParams.get('securityId') || url.searchParams.get('securityid') || dataSecurity;
                              if (securityId) {
                                return { securityId, lid: url.searchParams.get('lid') || '' };
                              }
                            } catch (_) { }
                          }
                          const html = document.documentElement?.innerHTML || '';
                          const match = html.match(/securityId=([^&"']+)/i);
                          return match ? { securityId: decodeURIComponent(match[1]), lid: '' } : null;
                        }
                        """);
                if (rawIds instanceof Map<?, ?> rawMap) {
                    String securityId = rawMap.get("securityId") == null ? "" : String.valueOf(rawMap.get("securityId"));
                    String lid = rawMap.get("lid") == null ? "" : String.valueOf(rawMap.get("lid"));
                    if (!securityId.isBlank()) ids = Map.of("securityId", securityId, "lid", lid);
                }
                if (ids == null && attempt < 4) PlaywrightUtil.sleep(1);
            }
            if (ids == null) {
                log.warn("岗位详情未找到 securityId，无法在点击前设置固定招呼 | encryptId={}", encryptId);
                return ProtocolGreetingResult.UNAVAILABLE;
            }

            Object greetingListResponse = detailPage.evaluate("""
                    async () => {
                      const tokenMatch = document.cookie.match(/(?:^|;\\s*)(?:bst|geek_zp_token)=([^;]+)/i);
                      const zpToken = tokenMatch ? decodeURIComponent(tokenMatch[1]) : '';
                      const response = await fetch('/wapi/zpchat/greeting/getGreetingList', {
                        method: 'GET',
                        credentials: 'include',
                        headers: {
                          'Accept': 'application/json, text/plain, */*',
                          'X-Requested-With': 'XMLHttpRequest',
                          'Zp_token': zpToken
                        }
                      });
                      return {status: response.status, body: await response.text()};
                    }
                    """);
            String templateId = extractGreetingTemplateId(greetingListResponse);
            Map<String, String> savePayload = Map.of(
                    "templateId", templateId,
                    "content", message,
                    "customType", "2");
            Object saveResponse = detailPage.evaluate("""
                    async ({templateId, content, customType}) => {
                      const tokenMatch = document.cookie.match(/(?:^|;\\s*)(?:bst|geek_zp_token)=([^;]+)/i);
                      const zpToken = tokenMatch ? decodeURIComponent(tokenMatch[1]) : '';
                      const response = await fetch('/wapi/zpchat/greeting/custom/saveV2', {
                        method: 'POST',
                        credentials: 'include',
                        headers: {
                          'Accept': 'application/json, text/plain, */*',
                          'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
                          'X-Requested-With': 'XMLHttpRequest',
                          'Zp_token': zpToken
                        },
                        body: new URLSearchParams({templateId, content, customType}).toString()
                      });
                      return {status: response.status, body: await response.text()};
                    }
                    """, savePayload);
            if (!isSuccessfulBossResponse(saveResponse)) {
                log.warn("Boss custom/saveV2 未成功，未点击立即沟通 | encryptId={} | templateId={} | 响应={}",
                        encryptId, templateId, describeBossResponse(saveResponse));
                return ProtocolGreetingResult.FAILED;
            }

            Map<String, String> addPayload = Map.of(
                    "securityId", ids.get("securityId"),
                    "jobId", encryptId,
                    "lid", ids.getOrDefault("lid", ""));
            Object addResponse = detailPage.evaluate("""
                    async ({securityId, jobId, lid}) => {
                      const tokenMatch = document.cookie.match(/(?:^|;\\s*)(?:bst|geek_zp_token)=([^;]+)/i);
                      const zpToken = tokenMatch ? decodeURIComponent(tokenMatch[1]) : '';
                      // Boss 当前官网使用 POST + form body 发起沟通；securityId/jobId/lid 在 query，
                      // body 至少带 sessionId 字段以匹配页面请求的表单编码。
                      const query = new URLSearchParams({securityId, jobId, lid}).toString();
                      const response = await fetch('/wapi/zpgeek/friend/add.json?' + query, {
                        method: 'POST',
                        credentials: 'include',
                        headers: {
                          'Accept': 'application/json, text/plain, */*',
                          'X-Requested-With': 'XMLHttpRequest',
                          'Zp_token': zpToken,
                          'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8'
                        },
                        body: new URLSearchParams({sessionId: ''}).toString()
                      });
                      return {status: response.status, body: await response.text()};
                    }
                    """, addPayload);
            if (!isSuccessfulBossResponse(addResponse)) {
                log.warn("Boss friend/add 未成功，未点击立即沟通 | encryptId={} | 响应={}", encryptId, describeBossResponse(addResponse));
                return ProtocolGreetingResult.FAILED;
            }
            return ProtocolGreetingResult.SENT;
        } catch (Exception e) {
            log.warn("Boss 协议招呼调用异常，未点击立即沟通 | encryptId={} | 原因={}", encryptId, e.getMessage());
            return ProtocolGreetingResult.FAILED;
        }
    }

    private boolean isSuccessfulBossResponse(Object response) {
        if (!(response instanceof Map<?, ?> result)) return false;
        Object status = result.get("status");
        if (!(status instanceof Number number) || number.intValue() < 200 || number.intValue() >= 300) return false;
        String body = result.get("body") == null ? "" : String.valueOf(result.get("body"));
        try {
            JSONObject json = new JSONObject(body);
            if (json.optBoolean("success", false)) return true;
            Object code = json.has("code") ? json.opt("code") : json.opt("resCode");
            if (code != null && ("0".equals(String.valueOf(code)) || "200".equals(String.valueOf(code)))) return true;
            JSONObject data = json.optJSONObject("data");
            if (data != null) {
                Object nestedCode = data.has("code") ? data.opt("code") : data.opt("resCode");
                if (nestedCode != null && ("0".equals(String.valueOf(nestedCode)) || "200".equals(String.valueOf(nestedCode)))) return true;
            }
            String message = json.optString("message", json.optString("msg", ""));
            return message.contains("成功") || message.contains("success");
        } catch (Exception ignored) {
            return false;
        }
    }

    private String describeBossResponse(Object response) {
        if (!(response instanceof Map<?, ?> result)) return String.valueOf(response);
        Object status = result.get("status");
        String body = result.get("body") == null ? "" : String.valueOf(result.get("body"));
        if (body.length() > 300) body = body.substring(0, 300);
        return "status=" + status + ", body=" + body;
    }

    /** 从当前 Boss 招呼模板列表中读取 templateId；读取失败时允许新建模板使用空值。 */
    private String extractGreetingTemplateId(Object response) {
        if (!(response instanceof Map<?, ?> result)) return "";
        String body = result.get("body") == null ? "" : String.valueOf(result.get("body"));
        try {
            JSONObject json = new JSONObject(body);
            String templateId = findJsonString(json, "templateId");
            return templateId == null ? "" : templateId;
        } catch (Exception ignored) {
            return "";
        }
    }

    private String findJsonString(Object value, String key) {
        if (value instanceof JSONObject object) {
            if (object.has(key) && !object.isNull(key)) return object.optString(key, "");
            for (String name : object.keySet()) {
                String nested = findJsonString(object.opt(name), key);
                if (nested != null && !nested.isBlank()) return nested;
            }
        } else if (value instanceof org.json.JSONArray array) {
            for (Object item : array) {
                String nested = findJsonString(item, key);
                if (nested != null && !nested.isBlank()) return nested;
            }
        }
        return null;
    }

    /** 协议失败时的原生 Boss 沟通兜底，确认弹窗/跳转后才算默认招呼已发起。 */
    private DefaultGreetingResult sendDefaultGreetingFallback(Page detailPage, Locator chatBtn, String jobName) {
        try {
            chatBtn.first().click();
            for (int attempt = 0; attempt < 8; attempt++) {
                List<Page> candidates = chatCandidatePages(detailPage);
                for (Page candidate : candidates) {
                    try {
                        Locator dialog = candidate.locator(".greet-boss-dialog:visible, .dialog-container:visible");
                        if (dialog.count() > 0) {
                            String text = dialog.first().textContent();
                            if (text != null && (text.contains("已向BOSS发送消息") || text.contains("已发送消息"))) {
                                return new DefaultGreetingResult(true, candidate);
                            }
                        }
                        String url = candidate.url();
                        if (url != null && url.contains("/web/geek/chat")) {
                            return new DefaultGreetingResult(true, candidate);
                        }
                    } catch (Exception checkError) {
                        log.debug("检查Boss默认招呼结果失败 | 岗位：{} | {}", jobName, checkError.getMessage());
                    }
                }
                PlaywrightUtil.sleep(1);
            }
        } catch (Exception clickError) {
            if (clickError instanceof QuietDeliveryFailure failure) throw failure;
            log.warn("Boss默认招呼兜底点击失败 | 岗位：{} | {}", jobName, clickError.getMessage());
        }
        return new DefaultGreetingResult(false, null);
    }

    /** 兼容上游 Selenium 版本：关闭默认招呼弹窗后再次点击，进入真正的聊天编辑器。 */
    private void closeGreetingDialogAndReclick(Page detailPage, Locator chatBtn, String jobName) {
        try {
            Locator close = detailPage.locator(
                    "i.icon-close:visible, .dialog-container i.icon-close:visible, "
                            + ".greet-boss-dialog i.icon-close:visible, "
                            + "[aria-label='关闭']:visible, button.close:visible, .dialog-close:visible");
            for (int i = 0; i < close.count(); i++) {
                Locator candidate = close.nth(i);
                if (candidate.isVisible()) {
                    candidate.click();
                    PlaywrightUtil.sleep(1);
                    break;
                }
            }
            chatBtn.first().click();
            PlaywrightUtil.sleep(1);
            log.debug("已关闭默认招呼弹窗并重新进入聊天编辑器 | 岗位：{}", jobName);
        } catch (Exception e) {
            log.debug("关闭默认招呼弹窗并重试聊天入口失败 | 岗位：{} | {}", jobName, e.getMessage());
        }
    }

    /**
     * Boss 新版在“立即沟通”后只发默认招呼并停留在职位详情页，不一定自动打开聊天编辑器。
     * 旧版会直接把详情页切成聊天页，因此需要在新版显式进入消息页并选中刚建立的会话。
     */
    private Page openChatConversation(Page sourcePage, Job job) {
        if (sourcePage == null || sourcePage.context() == null) return sourcePage;
        Page actualPage = sourcePage;
        try {
            // 上游旧流程在同一详情页关闭弹窗后再次点击，聊天编辑器会直接出现；
            // 先立即检查，避免无谓地导航消息页打断这个已打开的编辑器。
            Locator sourceInput = findVisibleChatInput(actualPage);
            if (sourceInput != null) return actualPage;
            String url = actualPage.url();
            if (url != null && url.contains("/web/geek/chat")) {
                Page existing = waitForChatPage(actualPage);
                if (existing != null && findVisibleChatInput(existing) != null) return existing;
            }

            // 复用 upstream/e48d51d 的真实流程：关闭简化打招呼层，
            // 重新创建“继续沟通”定位器并再次点击，不等待 URL 变化。
            closeLightweightChatPanel(actualPage);
            Locator continueChat = findVisibleChatButton(actualPage);
            if (continueChat != null) {
                try {
                    continueChat.click(new Locator.ClickOptions().setTimeout(10_000));
                } catch (Exception clickError) {
                    log.debug("点击继续沟通时页面切换，继续探测聊天编辑器：{}", clickError.getMessage());
                }
                Page samePage = waitForChatEditor(actualPage, 15);
                if (samePage != null) return samePage;
            }

            // 同页重新进入失败时才使用消息页兜底；这是兼容某些版本确实新开聊天页的路径。
            if (url == null || !url.contains("/web/geek/chat")) {
                actualPage.navigate("https://www.zhipin.com/web/geek/chat",
                        new Page.NavigateOptions()
                                .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                                .setTimeout(30_000));
                waitForPageSettled(actualPage);
            }
            selectChatConversation(actualPage, job);
            Page selected = waitForChatPage(actualPage);
            return selected != null ? selected : actualPage;
        } catch (Exception e) {
            if (e instanceof QuietDeliveryFailure failure) throw failure;
            log.warn("进入Boss消息页失败 | 岗位：{} | {}", job == null ? "" : job.getJobName(), e.getMessage());
            return actualPage;
        }
    }

    private void closeLightweightChatPanel(Page candidate) {
        try {
            Locator closeButtons = candidate.locator(
                    "i.icon-close:visible, .dialog-container i.icon-close:visible, "
                            + ".greet-boss-dialog i.icon-close:visible, "
                            + "[aria-label='关闭']:visible, button.close:visible, .dialog-close:visible");
            for (int i = 0; i < closeButtons.count(); i++) {
                Locator close = closeButtons.nth(i);
                if (close.isVisible()) {
                    close.click(new Locator.ClickOptions().setTimeout(5_000));
                    PlaywrightUtil.sleep(1);
                    return;
                }
            }
        } catch (Exception e) {
            log.debug("关闭Boss简化打招呼层失败，继续寻找继续沟通按钮：{}", e.getMessage());
        }
    }

    private Locator findVisibleChatButton(Page candidate) {
        if (candidate == null) return null;
        try {
            Locator buttons = candidate.locator("a.btn-startchat, a.op-btn-chat");
            for (int i = 0; i < buttons.count(); i++) {
                Locator button = buttons.nth(i);
                if (!button.isVisible()) continue;
                String text = button.textContent();
                // 首次“立即沟通”已经点击过；只有 Boss 将入口切换为“继续沟通”
                // 后才允许再次点击，避免重复发起默认招呼。
                if (text == null || text.isBlank()
                        || text.contains("继续沟通") || text.contains("继续聊") || text.contains("聊天")) {
                    return button;
                }
            }
        } catch (Exception e) {
            log.debug("查找Boss继续沟通按钮失败：{}", e.getMessage());
        }
        return null;
    }

    private Page waitForChatEditor(Page candidate, int seconds) {
        for (int i = 0; i < seconds; i++) {
            Page found = waitForChatEditorOnce(candidate);
            if (found != null) return found;
            PlaywrightUtil.sleep(1);
        }
        return null;
    }

    private Page waitForChatEditorOnce(Page candidate) {
        if (candidate != null && findVisibleChatInput(candidate) != null) return candidate;
        if (candidate == null || candidate.context() == null) return null;
        try {
            for (Page open : chatCandidatePages(candidate)) {
                if (open != null && findVisibleChatInput(open) != null) return open;
            }
        } catch (Exception failure) {
            if (failure instanceof QuietDeliveryFailure quietFailure) throw quietFailure;
        }
        return null;
    }

    /** 在消息列表中按公司、招聘者或职位名选择刚刚建立的会话。 */
    private void selectChatConversation(Page chatPage, Job job) {
        if (chatPage == null || job == null) return;
        List<String> needles = new ArrayList<>();
        if (isValidString(job.getCompanyName())) needles.add(job.getCompanyName().trim());
        if (isValidString(job.getRecruiter())) needles.add(job.getRecruiter().trim());
        if (isValidString(job.getJobName())) needles.add(job.getJobName().trim());
        if (needles.isEmpty()) return;
        String selector = "li[role='listitem'], li.chat-list-item, .chat-list-item, "
                + ".conversation-item, [class*='chat-list'] li, [class*='conversation']";
        try {
            for (int attempt = 0; attempt < 12; attempt++) {
                Locator items = chatPage.locator(selector);
                for (int i = 0; i < items.count(); i++) {
                    Locator item = items.nth(i);
                    if (!item.isVisible()) continue;
                    String text = item.innerText();
                    if (text == null) continue;
                    String compact = text.replaceAll("\\s+", "");
                    boolean matched = needles.stream().anyMatch(needle -> {
                        if (needle == null || needle.isBlank()) return false;
                        return text.contains(needle) || compact.contains(needle.replaceAll("\\s+", ""));
                    });
                    if (matched) {
                        item.click();
                        PlaywrightUtil.sleep(1);
                        log.debug("已在消息页选中会话 | 公司：{} | 招聘者：{} | 岗位：{}",
                                job.getCompanyName(), job.getRecruiter(), job.getJobName());
                        return;
                    }
                }
                if (attempt < 11) PlaywrightUtil.sleep(1);
            }
            log.warn("消息页未按公司/招聘者/职位找到会话 | 公司：{} | 招聘者：{} | 岗位：{}",
                    job.getCompanyName(), job.getRecruiter(), job.getJobName());
        } catch (Exception e) {
            log.debug("选择Boss消息会话失败：{}", e.getMessage());
        }
    }

    /**
     * 兼容旧版的聊天页发送流程。Boss 的首条招呼可能受 100 字限制，但进入会话后
     * 仍可在 textarea/contenteditable 中发送完整文本。所有写入与确认都限定在可见
     * 聊天编辑器，避免把文本填入隐藏模板或图片上传控件。
     */
    private boolean sendCustomChatMessage(Page chatPage, String message, Job job) {
        String jobName = job == null ? "" : job.getJobName();
        if (chatPage == null || message == null || message.isBlank()) return false;
        try {
            Page actualPage = openChatConversation(chatPage, job);
            if (actualPage == null) actualPage = chatPage;
            Locator input = findVisibleChatInput(actualPage);
            if (input == null) {
                log.warn("聊天输入框未出现，无法发送固定信息 | 岗位：{}", jobName);
                return false;
            }
            // 某些版本把 .chat-input/.input-area 作为包装层，真正可编辑节点在其内部；
            // 重新定位后再写入，避免 fill 只改到不可提交的外层 DOM。
            try {
                Locator inner = input.locator("textarea, input, [contenteditable='true'], [contenteditable='plaintext-only']").first();
                if (inner.count() > 0 && inner.isVisible()) input = inner;
            } catch (Exception ignored) {}
            input.click();
            try {
                input.fill(message);
            } catch (Exception fillError) {
                input.evaluate("""
                        (el, msg) => {
                          const tag = (el.tagName || '').toLowerCase();
                          el.focus();
                          if (tag === 'textarea' || tag === 'input') {
                            const proto = tag === 'textarea' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                            const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
                            if (setter) setter.call(el, msg); else el.value = msg;
                          } else {
                            const inserted = document.execCommand && document.execCommand('insertText', false, msg);
                            if (!inserted) el.innerText = msg;
                          }
                          el.dispatchEvent(new InputEvent('input', {bubbles: true, inputType: 'insertText', data: msg}));
                          el.dispatchEvent(new Event('change', {bubbles: true}));
                        }
                        """, message);
            }
            String entered = readEditableText(input);
            if (!containsGreetingText(entered, message)) {
                log.warn("固定信息未成功写入聊天编辑器 | 岗位：{} | 实际长度：{} | 目标长度：{}",
                        jobName, entered.length(), message.length());
                return false;
            }

            Locator sendButton = findVisibleChatSendButton(actualPage);
            if (sendButton == null) {
                log.warn("聊天发送按钮未出现，无法发送固定信息 | 岗位：{}", jobName);
                return false;
            }
            sendButton.click();
            confirmChatSend(actualPage);
            boolean confirmed = waitForGreetingInConversation(actualPage, message);
            if (confirmed) {
                log.info("固定信息聊天消息已确认 | 岗位：{} | 长度：{}", jobName, message.length());
            }
            return confirmed;
        } catch (Exception e) {
            if (e instanceof QuietDeliveryFailure failure) throw failure;
            log.warn("发送固定聊天消息异常 | 岗位：{} | {}", jobName, e.getMessage());
            return false;
        }
    }

    private Locator findVisibleChatSendButton(Page candidate) {
        if (candidate == null) return null;
        try {
            Locator buttons = candidate.locator(CHAT_SEND_SELECTORS);
            for (int i = 0; i < buttons.count(); i++) {
                Locator button = buttons.nth(i);
                if (button.isVisible() && button.isEnabled()) return button;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String readEditableText(Locator input) {
        try {
            Object value = input.evaluate("el => { const tag=(el.tagName||'').toLowerCase(); return (tag==='textarea'||tag==='input') ? el.value : (el.innerText || el.textContent || ''); }");
            return value == null ? "" : String.valueOf(value);
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean containsGreetingText(String actual, String expected) {
        String actualNormalized = normalizeGreetingText(actual);
        String expectedNormalized = normalizeGreetingText(expected);
        return !expectedNormalized.isEmpty() && actualNormalized.contains(expectedNormalized);
    }

    /**
     * 在当前浏览器上下文中寻找真正包含聊天编辑器的页面。
     * 点击“立即沟通”后，Boss 可能在详情页弹窗、当前页导航或新标签页打开聊天。
     */
    private Page waitForChatPage(Page detailPage) {
        if (detailPage == null || detailPage.context() == null) return null;
        for (int attempt = 0; attempt < 15; attempt++) {
            List<Page> candidates = chatCandidatePages(detailPage);
            for (Page candidate : candidates) {
                if (findVisibleChatInput(candidate) != null) return candidate;
            }
            if (attempt < 14) PlaywrightUtil.sleep(1);
        }
        return null;
    }

    /** 返回页面中可见且确实可编辑的聊天输入框。 */
    private Locator findVisibleChatInput(Page candidate) {
        if (candidate == null) return null;
        try {
            Locator locator = candidate.locator(CHAT_INPUT_SELECTORS);
            int count = locator.count();
            for (int i = 0; i < count; i++) {
                Locator current = count == 1 ? locator.first() : locator.nth(i);
                if (!current.isVisible()) continue;
                Object editable = current.evaluate("el => { const tag = (el.tagName || '').toLowerCase(); const id=(el.id||'').toLowerCase(); const cls=(typeof el.className==='string'?el.className:'').toLowerCase(); const generic=/chat-input|chat-input-box|input-area/.test(id+' '+cls); return tag === 'textarea' || tag === 'input' || el.isContentEditable === true || el.getAttribute('role') === 'textbox' || generic; }");
                if (Boolean.TRUE.equals(editable)) return current;
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String describeOpenPages() {
        if (page == null || page.context() == null) return "无可用页面";
        try {
            List<String> urls = new ArrayList<>();
            for (Page openPage : page.context().pages()) {
                try {
                    urls.add(openPage.url());
                } catch (Exception ignored) {
                    urls.add("<页面已关闭>");
                }
            }
            return urls.toString();
        } catch (Exception e) {
            return "页面列表读取失败";
        }
    }

    /**
     * 等待聊天记录出现本次固定招呼语。
     * Boss 的“立即沟通”在部分版本会先发平台默认招呼；只有在消息列表中读回自定义文本，
     * 才认为本次固定招呼发送成功。查询仅限当前页面 DOM，不调用任何站内接口。
     */
    private boolean waitForGreetingInConversation(Page chatPage, String expectedMessage) {
        String expected = normalizeGreetingText(expectedMessage);
        if (expected.isEmpty()) return false;
        Locator messageNodes = chatPage.locator(String.join(", ",
                ".chat-message:visible",
                ".message-content:visible",
                ".message-text:visible",
                ".message-item:visible",
                ".item-myself:visible",
                ".im-list li.message-item.item-myself .message-text:visible",
                ".im-list li.message-item.item-myself .message-content:visible",
                ".chat-conversation [class*='message']:visible"));
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                for (String text : messageNodes.allTextContents()) {
                    if (text != null && normalizeGreetingText(text).contains(expected)) return true;
                }
            } catch (Exception readError) {
                log.debug("读取聊天记录确认固定招呼语失败：{}", readError.getMessage());
            }
            if (attempt < 4) PlaywrightUtil.sleep(1);
        }
        return false;
    }

    /** 某些 Boss 版本发送后还会显示“确定发送”二次确认按钮。 */
    private void confirmChatSend(Page candidate) {
        try {
            Locator confirm = candidate.locator(
                    ".btn-sure:visible, button.btn-sure:visible, "
                            + ".dialog-container button:visible, .greet-boss-dialog button:visible");
            for (int i = 0; i < confirm.count(); i++) {
                Locator button = confirm.nth(i);
                if (!button.isVisible()) continue;
                String text = button.innerText();
                if (text != null && (text.contains("确定") || text.contains("确认") || text.contains("发送"))) {
                    button.click();
                    PlaywrightUtil.sleep(1);
                    return;
                }
            }
        } catch (Exception ignored) {}
    }

    private String normalizeGreetingText(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }

    

    /**
     * 注册页面响应监听：拦截 /wapi/zpgeek/job/detail.json 请求并解析写库
     */
    private void attachJobDetailResponseListener() {
        if (page == null) return;
        page.onResponse(resp -> {
            try {
                String url = resp.url();
                if (url == null) return;
                // 仅处理 Boss 岗位详情接口（GET）
                if (url.contains("/wapi/zpgeek/job/detail.json") &&
                        "GET".equalsIgnoreCase(resp.request().method())) {
                    String body = null;
                    try {
                        body = resp.text();
                    } catch (Throwable ignore) {
                        // 某些情况下可能拿不到文本，忽略
                    }
                    if (body == null || body.isEmpty()) return;

                    // 保存原始 JSON 到 target/job.txt
                    appendRawJson(body);

                    // 仅记录映射与原始 JSON；入库逻辑已移动到点击卡片时
                    JSONObject root = new JSONObject(body);
                    JSONObject zpData = root.optJSONObject("zpData");
                    if (zpData == null) return;
                    JSONObject jobInfo = zpData.optJSONObject("jobInfo");
                    if (jobInfo == null) return;
                    String encryptId = jobInfo.optString("encryptId", null);
                    String encryptUserId = jobInfo.optString("encryptUserId", null);
                    if (encryptId != null && encryptUserId != null) {
                        encryptIdToUserId.put(encryptId, encryptUserId);
                    }
                }
            } catch (Throwable e) {
                log.debug("监听岗位详情响应处理异常：{}", e.getMessage());
            }
        });
    }


    /**
     * 追加保存原始 JSON 到 target/job.txt
     */
    private void appendRawJson(String body) {
        try {
            java.io.File dir = new java.io.File("target");
            if (!dir.exists()) dir.mkdirs();
            java.io.File file = new java.io.File(dir, "job.txt");
            try (java.io.FileWriter fw = new java.io.FileWriter(file, true)) {
                fw.write(body);
                fw.write(System.lineSeparator());
                fw.write("\n");
            }
        } catch (Exception e) {
            log.debug("写入 target/job.txt 失败：{}", e.getMessage());
        }
    }

    /**
     * 从详情页 URL 中提取 encrypt_id
     */
    private String extractEncryptId(String detailUrl) {
        try {
            if (detailUrl == null) return null;
            String key = "/job_detail/";
            int idx = detailUrl.indexOf(key);
            if (idx < 0) return null;
            int start = idx + key.length();
            int end = detailUrl.indexOf(".html", start);
            if (end < 0) end = detailUrl.length();
            return detailUrl.substring(start, end);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isValidString(String str) {
        return str != null && !str.isBlank();
    }

    private boolean sendImageResume(Page page) {
        try {
            // 0) 资源存在性校验，避免后续无效操作
            URL resourceUrlCheck = Boss.class.getResource("/resume.jpg");
            if (resourceUrlCheck == null) {
                log.error("资源文件 resume.jpg 不存在，跳过发送图片简历");
                return false;
            }

            // 进入聊天页
            if (!page.url().contains("/web/geek/chat")) {
                Locator chatBtn = page.locator("a.btn-startchat, a.op-btn-chat");
                if (chatBtn.count() == 0) {
                    log.warn("未找到【继续沟通/立即沟通】按钮，跳过发送图片简历");
                    return false;
                }
                chatBtn.first().click();
                page.waitForURL("**/web/geek/chat**", new Page.WaitForURLOptions().setTimeout(15_000));
            }

            // 1) 解析图片路径（在可能触发文件选择器前就准备好）
            java.nio.file.Path imagePath = resolveResumeImage();

            // 精准定位聊天工具栏内的图片输入，避免匹配到页面其他上传控件
            Locator imgContainer = page.locator("div.btn-sendimg[aria-label='发送图片'], div[aria-label='发送图片'].btn-sendimg");
            Locator imageInput = imgContainer.locator("input[type='file'][accept*='image']").first();
            if (imageInput.count() == 0) {
                // 若未渲染，尝试拦截系统文件选择器；若未出现则普通点击促使 input 出现
                if (imgContainer.count() > 0) {
                    boolean chooserHandled = false;
                    try {
                        com.microsoft.playwright.FileChooser chooser = page.waitForFileChooser(() -> {
                            imgContainer.first().click();
                        });
                        chooser.setFiles(imagePath);
                        chooserHandled = true;
                        log.info("通过 FileChooser 直接提交图片文件，避免系统窗口阻塞");
                    } catch (com.microsoft.playwright.PlaywrightException ignore) {
                        // 未弹出系统文件选择器，继续常规流程
                    }
                    if (!chooserHandled) {
                        PlaywrightUtil.sleep(1);
                        imageInput = imgContainer.locator("input[type='file'][accept*='image']").first();
                    }
                }
            }
            imageInput.waitFor(new Locator.WaitForOptions().setTimeout(10_000));

            // 上传图片
            imageInput.setInputFiles(imagePath);
            PlaywrightUtil.sleep(1);
            return true;
        } catch (Throwable e) {
            log.error("发送图片简历失败：{}", e.getMessage(), e);
            return false;
        }
    }

    private java.nio.file.Path resolveResumeImage() throws Exception {
        URL resourceUrl = Boss.class.getResource("/resume.jpg");
        if (resourceUrl == null) {
            throw new IllegalStateException("资源文件 /resume.jpg 未找到，请将图片放置到 src/main/resources 目录下");
        }
        if ("file".equalsIgnoreCase(resourceUrl.getProtocol())) {
            return java.nio.file.Paths.get(resourceUrl.toURI());
        }
        java.nio.file.Path temp = java.nio.file.Files.createTempFile("resume-", ".jpg");
        try (java.io.InputStream in = Boss.class.getResourceAsStream("/resume.jpg")) {
            if (in == null) {
                throw new IllegalStateException("无法从类路径读取 /resume.jpg 资源");
            }
            java.nio.file.Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return temp;
    }

    /**
     * 检查岗位薪资是否符合预期
     *
     * @return boolean
     * true 不符合预期
     * false 符合预期
     * 期望的最低薪资如果比岗位最高薪资还小，则不符合（薪资给的太少）
     * 期望的最高薪资如果比岗位最低薪资还小，则不符合(要求太高满足不了)
     */
    private boolean isSalaryNotExpected(String salary) {
        try {
            // 1. 如果没有期望薪资范围，直接返回 false，表示"薪资并非不符合预期"
            List<Integer> expectedSalary = config.getExpectedSalary();
            if (!hasExpectedSalary(expectedSalary)) {
                return false;
            }

            // 2. 清理薪资文本（比如去掉 "·15薪"）
            salary = removeYearBonusText(salary);

            // 3. 如果薪资格式不符合预期（如缺少 "K" / "k"），直接返回 true，表示"薪资不符合预期"
            if (!isSalaryInExpectedFormat(salary)) {
                return true;
            }

            // 4. 进一步清理薪资文本，比如去除 "K"、"k"、"·" 等
            salary = cleanSalaryText(salary);

            // 5. 判断是 "月薪" 还是 "日薪"
            String jobType = detectJobType(salary);
            salary = removeDayUnitIfNeeded(salary); // 如果是按天，则去除 "元/天"

            // 6. 解析薪资范围并检查是否超出预期
            Integer[] jobSalaryRange = parseSalaryRange(salary);
            return isSalaryOutOfRange(jobSalaryRange,
                    getMinimumSalary(expectedSalary),
                    getMaximumSalary(expectedSalary),
                    jobType);

        } catch (Exception e) {
            log.error("岗位薪资获取异常！薪资文本【{}】,异常信息【{}】", salary, e.getMessage(), e);
            // 出错时，您可根据业务需求决定返回 true 或 false
            // 这里假设出错时无法判断，视为不满足预期 => 返回 true
            return true;
        }
    }

    /**
     * 是否存在有效的期望薪资范围
     */
    private boolean hasExpectedSalary(List<Integer> expectedSalary) {
        return expectedSalary != null && !expectedSalary.isEmpty();
    }

    /**
     * 去掉年终奖信息，如 "·15薪"、"·13薪"。
     */
    private String removeYearBonusText(String salary) {
        if (salary.contains("薪")) {
            // 使用正则去除 "·任意数字薪"
            return salary.replaceAll("·\\d+薪", "");
        }
        return salary;
    }

    /**
     * 判断是否是按天计薪，如发现 "元/天" 则认为是日薪
     */
    private String detectJobType(String salary) {
        if (salary.contains("元/天")) {
            return "day";
        }
        return "mouth";
    }

    /**
     * 如果是日薪，则去除 "元/天"
     */
    private String removeDayUnitIfNeeded(String salary) {
        if (salary.contains("元/天")) {
            return salary.replaceAll("元/天", "");
        }
        return salary;
    }

    private Integer getMinimumSalary(List<Integer> expectedSalary) {
        return expectedSalary != null && !expectedSalary.isEmpty() ? expectedSalary.get(0) : null;
    }

    private Integer getMaximumSalary(List<Integer> expectedSalary) {
        return expectedSalary != null && expectedSalary.size() > 1 ? expectedSalary.get(1) : null;
    }

    private boolean isSalaryInExpectedFormat(String salaryText) {
        return salaryText.contains("K") || salaryText.contains("k") || salaryText.contains("元/天");
    }

    private String cleanSalaryText(String salaryText) {
        salaryText = salaryText.replace("K", "").replace("k", "");
        int dotIndex = salaryText.indexOf('·');
        if (dotIndex != -1) {
            salaryText = salaryText.substring(0, dotIndex);
        }
        return salaryText;
    }

    private boolean isSalaryOutOfRange(Integer[] jobSalary, Integer miniSalary, Integer maxSalary,
                                       String jobType) {
        if (jobSalary == null) {
            return true;
        }
        if (miniSalary == null) {
            return false;
        }
        if (Objects.equals("day", jobType)) {
            // 期望薪资转为平均每日的工资
            maxSalary = BigDecimal.valueOf(maxSalary).multiply(BigDecimal.valueOf(1000))
                    .divide(BigDecimal.valueOf(21.75), 0, RoundingMode.HALF_UP).intValue();
            miniSalary = BigDecimal.valueOf(miniSalary).multiply(BigDecimal.valueOf(1000))
                    .divide(BigDecimal.valueOf(21.75), 0, RoundingMode.HALF_UP).intValue();
        }
        // 如果职位薪资下限低于期望的最低薪资，返回不符合
        if (jobSalary[1] < miniSalary) {
            return true;
        }
        // 如果职位薪资上限高于期望的最高薪资，返回不符合
        return maxSalary != null && jobSalary[0] > maxSalary;
    }

    public boolean containsDeadStatus(String activeTimeText, List<String> deadStatus) {
        for (String status : deadStatus) {
            if (activeTimeText.contains(status)) {
                return true;// 一旦找到包含的值，立即返回 true
            }
        }
        return false;// 如果没有找到，返回 false
    }

    private String generateAiMessage(String keyword, String jobName, String jd) {
        AiEntity aiConfig = aiService.getAiConfig();
        String introduce = (aiConfig != null && aiConfig.getIntroduce() != null) ? aiConfig.getIntroduce() : "";
        String prompt = (aiConfig != null) ? aiConfig.getPrompt() : null;

        String requestMessage = (prompt != null)
                ? String.format(prompt, introduce, keyword, jobName, jd, config.getSayHi())
                : buildDefaultPrompt(introduce, keyword, jobName, jd);

        try {
            String result = aiService.sendRequest(requestMessage);
            if (result == null) {
                return config.getSayHi();
            }
            return result.toLowerCase().contains("false") ? config.getSayHi() : result;
        } catch (Exception e) {
            log.warn("AI请求失败，使用原有打招呼语: {}", e.getMessage());
            return config.getSayHi();
        }
    }

    private String buildDefaultPrompt(String introduce, String keyword, String jobName, String jd) {
        return "请基于以下信息生成简洁友好的中文打招呼语，不超过60字：\n" +
                "个人介绍：" + introduce + "\n" +
                "关键词：" + keyword + "\n" +
                "职位名称：" + jobName + "\n" +
                "职位描述：" + jd + "\n" +
                "参考语：" + config.getSayHi();
    }

    private Integer[] parseSalaryRange(String salaryText) {
        try {
            return Arrays.stream(salaryText.split("-")).map(s -> s.replaceAll("[^0-9]", "")) // 去除非数字字符
                    .map(Integer::parseInt) // 转换为Integer
                    .toArray(Integer[]::new); // 转换为Integer数组
        } catch (Exception e) {
            log.error("薪资解析异常！{}", e.getMessage(), e);
        }
        return null;
    }

    /** wait_time 没配或配得不合法时用的秒数 */
    private static final int DEFAULT_WAIT_TIME_SECONDS = 10;

    /**
     * 取配置里的 wait_time（秒），非法值一律退回默认值。
     */
    private int resolveWaitTimeSeconds() {
        try {
            String raw = config == null ? null : config.getWaitTime();
            if (raw != null && !raw.isBlank()) {
                int parsed = Integer.parseInt(raw.trim());
                if (parsed > 0) {
                    return parsed;
                }
            }
        } catch (NumberFormatException ignored) {
            // 配置里塞了非数字，用默认值
        }
        return DEFAULT_WAIT_TIME_SECONDS;
    }

    /**
     * 每处理完一个岗位后的停顿。
     * <p>
     * 上一轮实测 7 分钟连刷 297 个岗位详情，直接把 Boss 风控触发了，
     * 后续关键词全部被弹到安全验证页。这里按 wait_time 降速：
     * 正常模式在 [wait_time/2, wait_time] 之间随机，避免固定节奏本身成为特征；
     * 调试模式固定用最大值 wait_time，方便观察。
     */
    private void pauseBetweenJobs() {
        int waitTime = resolveWaitTimeSeconds();
        int seconds;
        if (Boolean.TRUE.equals(config.getDebugger())) {
            seconds = waitTime;
        } else {
            int min = Math.max(1, waitTime / 2);
            seconds = min >= waitTime ? waitTime
                    : ThreadLocalRandom.current().nextInt(min, waitTime + 1);
        }
        log.debug("岗位间停顿 {} 秒（wait_time={}，debugger={}）", seconds, waitTime, config.getDebugger());
        PlaywrightUtil.sleep(seconds);
    }

    /**
     * 判断是不是 Boss 的安全验证页。
     * <p>
     * 实测密集遍历后会被弹到 /web/passport/zp/verify.html（极验滑块，页面上是
     * div.geetest_success_correct 那一套），而原来的判断只认 verify-slider，
     * 导致真正遇到验证时程序把关键词当失败跳过，人也不知道要去过验证。
     */
    private static boolean isSecurityVerifyUrl(String url) {
        if (url == null) {
            return false;
        }
        return url.contains("/web/user/safe/verify-slider")
                || url.contains("/web/passport/zp/verify")
                || url.contains("/web/passport/zp/security")
                || url.contains("security-check");
    }

    private void waitForSliderVerify(Page page) {
        if (quietDelivery) {
            if (quietAttentionHandler == null) throw new QuietDeliveryFailure("静默投递需要人工验证，请查看浏览器后重试");
            try {
                quietAttentionHandler.accept(page);
                quietCheckpoint();
                return;
            } catch (QuietDeliveryFailure failure) {
                throw failure;
            } catch (Exception failure) {
                throw new QuietDeliveryFailure("人工验证尚未完成，静默任务已停止", failure);
            }
        }
        // 最多等待5分钟（防呆，防止死循环）
        long start = System.currentTimeMillis();
        while (true) {
            String url = page.url();
            if (isSecurityVerifyUrl(url)) {
                progressCallback.accept("请手动完成Boss直聘滑块验证，通过后在控制台回车继续...", 0, 0);
                System.out.println("\n【滑块验证】请手动完成Boss直聘滑块验证，通过后在控制台回车继续…");
                try {
                    System.in.read();
                } catch (Exception e) {
                    log.error("等待滑块验证输入异常: {}", e.getMessage());
                }
                PlaywrightUtil.sleep(1);
                // 验证通过后页面url会变，循环再检测一次
                continue;
            }
            if ((System.currentTimeMillis() - start) > 5 * 60 * 1000) {
                throw new RuntimeException("滑块验证超时！");
            }
            break;
        }
    }


    private boolean isLoginRequired() {
        try {
            Locator buttonLocator = page.locator(LOGIN_BTNS);
            if (buttonLocator.count() > 0 && buttonLocator.textContent().contains("登录")) {
                return true;
            }
        } catch (Exception e) {
            try {
                page.locator(PAGE_HEADER).waitFor();
                Locator errorLoginLocator = page.locator(ERROR_PAGE_LOGIN);
                if (errorLoginLocator.count() > 0) {
                    errorLoginLocator.click();
                }
                return true;
            } catch (Exception ex) {
                log.info("没有出现403访问异常");
            }
            log.info("cookie有效，已登录...");
            return false;
        }
        return false;
    }

}
