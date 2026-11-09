using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Forms;

[assembly: AssemblyTitle("Mitu Desktop Assistant")]
[assembly: AssemblyProduct("Mitu")]
[assembly: AssemblyDescription("Mitu desktop assistant")]
[assembly: AssemblyVersion("3.1.0.0")]
[assembly: AssemblyFileVersion("3.1.0.0")]

// This local companion receives content over inherited standard streams.
// Screen capture is performed only after a button, hotkey, or explicit command.
internal static class MituDesktop
{
    private static readonly object OutputLock = new object();
    private static readonly JavaScriptSerializer Json = new JavaScriptSerializer { MaxJsonLength = 262144 };
    private static StreamWriter output;

    internal static void Emit(Dictionary<string, object> value)
    {
        lock (OutputLock)
        {
            try { output.WriteLine(Json.Serialize(value)); output.Flush(); }
            catch (IOException) { }
        }
    }

    [STAThread]
    private static int Main(string[] args)
    {
        output = new StreamWriter(Console.OpenStandardOutput(), new UTF8Encoding(false)) { AutoFlush = true };
        if (args.Length == 1 && args[0] == "--identity-check")
        {
            using (Process current = Process.GetCurrentProcess())
            {
                FileVersionInfo version = FileVersionInfo.GetVersionInfo(current.MainModule.FileName);
                Emit(new Dictionary<string, object> {
                    { "type", "identity" }, { "processName", current.ProcessName },
                    { "productName", version.ProductName }, { "pid", current.Id }
                });
            }
            return 0;
        }
        if (args.Length != 6 || args[0] != "--capture-dir" || args[2] != "--instance-key" || args[4] != "--exclude-from-capture")
            return 2;
        string captureDirectory = Path.GetFullPath(args[1]);
        bool exclude;
        if (!Directory.Exists(captureDirectory) || !Boolean.TryParse(args[5], out exclude) ||
            !System.Text.RegularExpressions.Regex.IsMatch(args[3], "^[a-f0-9]{32}$")) return 2;
        bool created;
        using (Mutex singleton = new Mutex(true, "Local\\MituDesktop-" + args[3], out created))
        {
            if (!created)
            {
                Emit(new Dictionary<string, object> { { "type", "error" }, { "message", "觅途桌面助手已经运行，请先关闭已有窗口。" } });
                return 3;
            }
            try
            {
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                using (AssistantWindow form = new AssistantWindow(captureDirectory, exclude))
                {
                    Thread reader = new Thread(delegate()
                    {
                        try
                        {
                            using (StreamReader input = new StreamReader(Console.OpenStandardInput(), new UTF8Encoding(false)))
                            {
                                JavaScriptSerializer inputJson = new JavaScriptSerializer { MaxJsonLength = 262144 };
                                string line;
                                while ((line = input.ReadLine()) != null)
                                {
                                    if (line.Length > 262144) continue;
                                    Dictionary<string, object> command;
                                    try { command = inputJson.Deserialize<Dictionary<string, object>>(line); }
                                    catch (ArgumentException) { continue; }
                                    if (form.IsDisposed || !form.IsHandleCreated) break;
                                    form.BeginInvoke((MethodInvoker)delegate { form.Receive(command); });
                                }
                            }
                        }
                        catch (IOException) { }
                        catch (ObjectDisposedException) { }
                        catch (InvalidOperationException) { }
                        finally
                        {
                            if (!form.IsDisposed && form.IsHandleCreated)
                            {
                                try { form.BeginInvoke((MethodInvoker)delegate { form.Close(); }); }
                                catch (InvalidOperationException) { }
                            }
                        }
                    }) { IsBackground = true, Name = "Mitu control input" };
                    form.Shown += delegate { reader.Start(); };
                    Application.Run(form);
                }
            }
            finally { singleton.ReleaseMutex(); }
        }
        return 0;
    }
}

internal sealed class AssistantWindow : Form
{
    private const int HotkeyId = 301;
    [DllImport("user32.dll", SetLastError = true)] private static extern bool SetWindowDisplayAffinity(IntPtr window, uint affinity);
    [DllImport("user32.dll", SetLastError = true)] private static extern bool GetWindowDisplayAffinity(IntPtr window, out uint affinity);
    [DllImport("user32.dll", SetLastError = true)] private static extern bool RegisterHotKey(IntPtr window, int id, uint modifiers, uint key);
    [DllImport("user32.dll")] private static extern bool UnregisterHotKey(IntPtr window, int id);
    [DllImport("dwmapi.dll")] private static extern int DwmFlush();

    private readonly string captureDirectory;
    private readonly bool requestedExclusion;
    private readonly Label protectionLabel;
    private readonly Label statusLabel;
    private readonly TextBox question;
    private readonly TextBox answer;
    private readonly Button captureButton;
    private readonly Button solveButton;
    private bool protectionApplied;
    private bool busy;
    private bool hotkeyRegistered;
    private string activeRequestId = "";

    protected override bool ShowWithoutActivation { get { return true; } }

    internal AssistantWindow(string directory, bool exclude)
    {
        captureDirectory = directory;
        requestedExclusion = exclude;
        Text = "觅途 Mitu · 笔试助手";
        ClientSize = new Size(470, 650);
        MinimumSize = new Size(380, 470);
        StartPosition = FormStartPosition.Manual;
        Rectangle area = Screen.FromPoint(Cursor.Position).WorkingArea;
        Location = new Point(Math.Max(area.Left, area.Right - Width - 24), area.Top + 28);
        BackColor = Color.FromArgb(246, 247, 255);
        ForeColor = Color.FromArgb(25, 32, 66);
        Font = new Font("Microsoft YaHei UI", 9);
        TopMost = true;
        AutoScaleMode = AutoScaleMode.Dpi;

        TableLayoutPanel layout = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(18), ColumnCount = 1, RowCount = 9 };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 35));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 44));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 49));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 27));
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 37));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 27));
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 63));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 43));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 34));
        layout.Controls.Add(new Label { Text = "觅途 Mitu  笔试助手", Dock = DockStyle.Fill, Font = new Font(Font.FontFamily, 14, FontStyle.Bold) }, 0, 0);
        protectionLabel = new Label { Dock = DockStyle.Fill, AutoSize = false };
        layout.Controls.Add(protectionLabel, 0, 1);
        TableLayoutPanel actions = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, RowCount = 1 };
        actions.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 48));
        actions.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 52));
        captureButton = new Button { Text = "截图识题", Dock = DockStyle.Fill, FlatStyle = FlatStyle.Flat,
            BackColor = Color.FromArgb(91, 77, 245), ForeColor = Color.White, Cursor = Cursors.Hand };
        captureButton.FlatAppearance.BorderSize = 0;
        captureButton.Click += delegate { CaptureScreen(Guid.NewGuid().ToString("N")); };
        solveButton = new Button { Text = "确认题目，生成解析", Dock = DockStyle.Fill, FlatStyle = FlatStyle.Flat,
            BackColor = Color.White, ForeColor = Color.FromArgb(91, 77, 245), Cursor = Cursors.Hand };
        solveButton.FlatAppearance.BorderColor = Color.FromArgb(180, 176, 240);
        solveButton.Click += delegate { Solve(); };
        actions.Controls.Add(captureButton, 0, 0);
        actions.Controls.Add(solveButton, 1, 0);
        layout.Controls.Add(actions, 0, 2);
        layout.Controls.Add(new Label { Text = "题目", Dock = DockStyle.Fill, TextAlign = ContentAlignment.BottomLeft }, 0, 3);
        question = ReadableBox();
        question.ReadOnly = false;
        question.MaxLength = 20000;
        question.Text = "";
        layout.Controls.Add(question, 0, 4);
        layout.Controls.Add(new Label { Text = "解析", Dock = DockStyle.Fill, TextAlign = ContentAlignment.BottomLeft }, 0, 5);
        answer = ReadableBox();
        answer.Text = "在上方输入题目或截图识题，确认后点击生成解析。\r\n截图识别只在本机进行；生成解析会发送题目到配置的 AI 服务。";
        layout.Controls.Add(answer, 0, 6);
        statusLabel = new Label { Text = "准备就绪。", Dock = DockStyle.Fill, Padding = new Padding(0, 8, 0, 0) };
        layout.Controls.Add(statusLabel, 0, 7);
        layout.Controls.Add(new Label { Text = "识题快捷键 Ctrl + Alt + Shift + Enter\r\n捕获排除不保证适用于所有共享/录屏方式。", Dock = DockStyle.Fill, ForeColor = Color.FromArgb(91, 98, 125) }, 0, 8);
        Controls.Add(layout);
        Shown += delegate
        {
            ApplyProtection();
            // MOD_NOREPEAT, CTRL, ALT, SHIFT. Failure is visible instead of silently promising a shortcut.
            hotkeyRegistered = RegisterHotKey(Handle, HotkeyId, 0x4007, 0x0D);
            if (!hotkeyRegistered) statusLabel.Text = "快捷键被占用，请使用截图识题按钮。";
            EmitStatus("ready", hotkeyRegistered ? "桌面窗口已启动。" : "桌面窗口已启动；快捷键被占用，请使用按钮。");
        };
        FormClosed += delegate
        {
            if (hotkeyRegistered) UnregisterHotKey(Handle, HotkeyId);
            MituDesktop.Emit(new Dictionary<string, object> { { "type", "closed" } });
        };
    }

    private static TextBox ReadableBox()
    {
        return new TextBox { Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical,
            Dock = DockStyle.Fill, BackColor = Color.White, BorderStyle = BorderStyle.FixedSingle,
            Font = new Font("Microsoft YaHei UI", 10), WordWrap = true };
    }

    private void ApplyProtection()
    {
        uint actual;
        bool success = SetWindowDisplayAffinity(Handle, requestedExclusion ? 0x11U : 0U);
        bool readable = GetWindowDisplayAffinity(Handle, out actual);
        protectionApplied = requestedExclusion && success && readable && actual == 0x11;
        protectionLabel.Text = !requestedExclusion ? "捕获排除：已关闭" : protectionApplied ?
            "捕获排除：系统已应用  ·  本机窗口仍可见" : "捕获排除：系统未成功应用，请勿依赖此状态";
        protectionLabel.ForeColor = requestedExclusion && !protectionApplied ? Color.FromArgb(175, 61, 51) : ForeColor;
    }

    private void EmitStatus(string type, string message)
    {
        using (Process current = Process.GetCurrentProcess())
        {
            MituDesktop.Emit(new Dictionary<string, object> {
                { "type", type }, { "pid", current.Id }, { "processName", current.ProcessName },
                { "excludeFromCapture", requestedExclusion }, { "captureProtectionApplied", protectionApplied },
                { "busy", busy }, { "message", message }
            });
        }
    }

    internal void Receive(Dictionary<string, object> command)
    {
        string operation = Value(command, "command");
        string requestId = Value(command, "requestId");
        if (operation == "close") { Close(); return; }
        if (operation == "update")
        {
            if (busy)
            {
                MituDesktop.Emit(new Dictionary<string, object> { { "type", "update-error" }, { "requestId", requestId } });
                return;
            }
            question.Text = Limit(Value(command, "question"));
            answer.Text = Limit(Value(command, "answer"));
            MituDesktop.Emit(new Dictionary<string, object> { { "type", "updated" }, { "requestId", requestId } });
            return;
        }
        if (operation == "capture") { CaptureScreen(requestId); return; }
        if (operation == "capture-complete")
        {
            if (requestId != activeRequestId) return;
            SetBusy(false);
            string error = Value(command, "error");
            if (error.Length == 0) { question.Text = Limit(Value(command, "question")); answer.Text = "请检查题目，确认后点击生成解析。"; }
            statusLabel.Text = error.Length == 0 ? "题目已识别，请检查内容后生成解析。" : Limit(error);
            EmitStatus("status", statusLabel.Text);
            return;
        }
        if (operation == "solve-complete")
        {
            if (requestId != activeRequestId) return;
            SetBusy(false);
            string error = Value(command, "error");
            if (error.Length == 0) answer.Text = Limit(Value(command, "answer"));
            statusLabel.Text = error.Length == 0 ? "解析已生成。" : Limit(error);
            EmitStatus("status", statusLabel.Text);
        }
    }

    private void SetBusy(bool value)
    {
        busy = value;
        captureButton.Enabled = !value;
        solveButton.Enabled = !value;
        question.ReadOnly = value;
        if (!value) activeRequestId = "";
    }

    private void Solve()
    {
        if (busy) return;
        string text = question.Text.Trim();
        if (text.Length == 0) { statusLabel.Text = "请先输入或识别题目。"; question.Focus(); return; }
        SetBusy(true);
        activeRequestId = Guid.NewGuid().ToString("N");
        statusLabel.Text = "正在生成解析，题目将发送到你配置的 AI 服务…";
        MituDesktop.Emit(new Dictionary<string, object> { { "type", "solve" }, { "requestId", activeRequestId }, { "question", text } });
    }

    private static string Limit(string value) { return value.Length <= 20000 ? value : value.Substring(0, 20000); }
    private static string Value(Dictionary<string, object> value, string key)
    {
        object result;
        return value.TryGetValue(key, out result) && result is string ? (string)result : "";
    }

    protected override void WndProc(ref Message message)
    {
        if (message.Msg == 0x0312 && message.WParam.ToInt32() == HotkeyId) CaptureScreen(Guid.NewGuid().ToString("N"));
        base.WndProc(ref message);
    }

    private void CaptureScreen(string requestId)
    {
        if (busy)
        {
            MituDesktop.Emit(new Dictionary<string, object> { { "type", "capture-error" }, { "requestId", requestId }, { "message", "已有识别任务，请等待完成。" } });
            return;
        }
        SetBusy(true);
        activeRequestId = requestId;
        statusLabel.Text = "正在截图并进行本机文字识别…";
        Rectangle bounds = Screen.FromPoint(Cursor.Position).Bounds;
        MituDesktop.Emit(new Dictionary<string, object> { { "type", "capture-requested" }, { "requestId", requestId } });
        Hide();
        ThreadPool.QueueUserWorkItem(delegate
        {
            string path = null;
            string error = null;
            try
            {
                Thread.Sleep(150);
                try { DwmFlush(); } catch (DllNotFoundException) { }
                using (Bitmap original = new Bitmap(bounds.Width, bounds.Height))
                {
                    using (Graphics graphics = Graphics.FromImage(original))
                        graphics.CopyFromScreen(bounds.Location, Point.Empty, bounds.Size, CopyPixelOperation.SourceCopy);
                    path = Path.Combine(captureDirectory, Guid.NewGuid().ToString("N") + ".png");
                    double ratio = Math.Min(1.0, 2500.0 / Math.Max(bounds.Width, bounds.Height));
                    using (Bitmap scaled = new Bitmap(original, new Size(Math.Max(1, (int)(bounds.Width * ratio)), Math.Max(1, (int)(bounds.Height * ratio)))))
                        scaled.Save(path, ImageFormat.Png);
                    if (new FileInfo(path).Length > 5 * 1024 * 1024) throw new InvalidOperationException();
                }
            }
            catch (Exception)
            {
                error = "截图失败或图片超过 5 MiB，请改用网页上传图片。";
                if (path != null) { try { File.Delete(path); } catch (IOException) { } }
            }
            if (!IsDisposed && IsHandleCreated)
            {
                try
                {
                    BeginInvoke((MethodInvoker)delegate
                    {
                        Show();
                        if (error != null)
                        {
                            SetBusy(false);
                            statusLabel.Text = error;
                            MituDesktop.Emit(new Dictionary<string, object> { { "type", "capture-error" }, { "requestId", requestId }, { "message", error } });
                        }
                        else MituDesktop.Emit(new Dictionary<string, object> { { "type", "captured" }, { "requestId", requestId }, { "path", path } });
                    });
                }
                catch (InvalidOperationException) { if (path != null) { try { File.Delete(path); } catch (IOException) { } } }
            }
            else if (path != null) { try { File.Delete(path); } catch (IOException) { } }
        });
    }
}
