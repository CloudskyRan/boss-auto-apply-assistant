# 自动投递静默运行：实现与验证

日期：2026-11-05  
状态：已实现、生产构建并覆盖本地部署；真实 Boss 连续后台投递尚未实测。  
目标：减少投递时的窗口弹出和焦点打扰，保留查看、人工处理与恢复后台的能力。

## 使用方式

投递工作台 `http://localhost:6866/boss/` 的“本次任务”区域新增“静默投递”，默认开启，下一次任务开始时生效。

- 修改后先点击“保存静默模式”。存在未保存模式草稿时禁止启动，运行期间不能更改模式。
- “查看浏览器”显示本项目受控 Chrome；“继续后台”将其最小化。两个按钮不会启动投递。
- 控制是异步请求，在当前浏览器操作结束后的安全检查点执行；页面显示等待状态，不能理解为点击后立即完成。
- 登录失效或人工验证时暂停后续操作并提示；查看浏览器手动处理后点击“继续后台”。等待可停止，最长五分钟，不自动解验证或盲目重发。

设置独立保存在 `work/quiet-delivery/settings.json`，首次读取默认值不创建文件，不修改 Boss 配置、数据库或登录资料。关闭静默后使用原有逐岗页面处理方式。

## 原因与实现

改造前，`Boss.resumeSubmissionInternal()` 每岗创建详情页，外层 finally 回收新增页，内部还有多处显式关闭；Chrome 启动参数包含 `--start-maximized`。频繁建页是打扰的重要来源，是否切换前台需要实测，不能只据源码推定。

### 复用页面与归属清理

静默模式保留搜索页，在同一上下文、同一窗口创建专用详情/聊天页，后续岗位导航复用。任务结束统一回收，关闭或崩溃后才重建，保留原有 Cookie、招呼兼容流程、JD 留存和数量上限。

逐岗清理按 opener 归属只处理本次业务产生的临时页，保护搜索页、复用页及用户自行打开的页面。没有全局改写 `window.open`，不屏蔽所有弹窗，不因窗口状态重复点击“立即沟通”。

### 受控窗口后台运行

移除启动最大化参数，任务开始最小化本项目窗口。CDP `Target.createTarget(background=true,newWindow=false)` 建页后核验 target、context、window 归属；失败直接停止，不悄悄降级普通模式。

实测当前 headed Chrome 后台建页仍可能恢复窗口，因此专用页建立后再最小化一次；每岗导航不反复最小化。只控制本项目窗口，不按标题或进程批量操作其他 Chrome。

参考：[Chrome DevTools 窗口接口](https://chromedevtools.github.io/devtools-protocol/tot/Browser/)、[后台建页参数](https://chromedevtools.github.io/devtools-protocol/tot/Target/)、[Playwright 多页面说明](https://playwright.dev/java/docs/pages)。实验接口兼容性以当前机器验证结果为准。

### 同线程控制与人工处理

HTTP 只写待处理意图，不跨线程操作 Playwright。运行时由 Boss 检查点消费；空闲时 dispatcher 将命令交给现有 Playwright 线程。显式查看时恢复窗口并置前，继续后台时最小化。

静默分支发现登录页、验证页或滑块时进入人工等待，不再阻塞终端 `System.in.read()`。处理后仍沿原阶段和消息结果核对继续；窗口显示不代表发送成功。停止沿用协作式取消，finally 清理后才释放任务状态。

## 代码与接口

- `BossQuietService`：设置、后台建页、窗口控制、同线程意图、人工等待和生命周期。
- `BossQuietController`：仅允许 socket loopback 和已有本机 Origin 的控制接口。
- `Boss`：复用页、检查点和临时页归属；`BossJobService` / `PlaywrightManager`：清理、状态及既有线程串行执行。
- `front/app/boss/page.tsx`：独立保存、控制按钮、状态与加载/草稿保护。

新增接口：

- `GET /api/boss/quiet/settings`：`quietEnabled`、`available`。
- `PUT /api/boss/quiet/settings`：布尔 `quietEnabled`，运行中 409。
- `POST /api/boss/quiet/show`、`/background`：接受后 202，稍后由状态确认完成。
- 原 `GET /api/boss/status` 增加 `quiet`，含 `enabled,available,active,windowState,needsAttention,message,pendingAction`。

## 验证结果

离线与构建检查通过：

- 127 项 Boss 静默生命周期：复用、清理、故障及登录/验证分支。
- 91 项服务/接口：默认不落盘、参数、本机权限、运行锁、同线程命令、人工等待停止及模拟超时清理。
- 95 项原投递/上限、106 项原单平台并发/停止、128 项 Boss 前端组件检查。
- Java 编译、Next 生产构建、静态复制、暂存 bootJar 和差异空白检查。

Computer Use 在本机真实 headed Chrome 与自建测试窗口实测，使用独立 disposable profile，仅访问 localhost：

- 连续 100 个模拟岗位，逐次填写、本地模拟发送 215 字信息并完整逐字回读。
- 页面保持 2（搜索页和复用页），最终窗口 `minimized`，无失败。
- 同时在自有 WinForms 测试窗口分两次输入文字；100ms 前台焦点采样共 1,841 次，离开测试窗口 0 次，文字完整保留。
- 部署后在工作台点击“查看浏览器”“继续后台”，最终状态分别 `normal` / `minimized`，待处理意图清空；任务始终未启动。

证据：`work/runtime/quiet-smoke/browser-result.json`、`probe-result.json`、`computer-use-result.png`；入口 `QuietDesktopSmoke.java` / `QuietTypingProbe.cs`。离线夹具为 `work/runtime/QuietBossPageCheck.java`、`QuietControlCheck.java`、`verify-boss-ui-logic.cjs`。运行时目录默认不作为发布源码，干净 CI 复现仍需后续整理。

本轮未向真实招聘者发消息、调用用户 AI、自动登录或解决验证码。五分钟截止未实际等待，验证的是模拟超时异常路径。本地回读不能等同真实 Boss 长招呼成功；平台强制新窗口仍可能短暂显示，不承诺所有实际场景零闪烁。

## 部署与后续

已通过 `work/runtime/deploy-mitu.ps1 -UseStaged` 覆盖 `build/libs/mitu-3.1.0.jar`。备份 `work/runtime/backups/mitu-quiet-20261105-184332/` 含旧 JAR、数据库与基线；原 24 张表和 Boss 配置指纹一致，SQLite `quick_check=ok`，健康且 Boss 已登录，未启动投递。

保持有界面浏览器。无界面模式、独立虚拟环境、真实 Boss 特殊弹页和图片附件后台兼容仍待分别验证。

定时、注册与多人服务为后续阶段，见 [实施路线](automation-multiuser-roadmap.md)。本轮未创建真实定时计划、开放注册、推送 GitHub 或开放公网端口。
