// Aria2PanFlowLauncher.cs — PanDownloader 独立版启动器 Final2 启动竞态修复
// 编译: csc.exe /target:winexe /out:Aria2PanFlow.exe Aria2PanFlowLauncher.cs
// v3: 驻留监控 — 打开浏览器后本进程保持运行, 轮询后端生命周期接口,
//     浏览器页面关闭(前端 beforeunload 上报)后自动停止全部服务并退出.
//     不再需要 start.bat / stop.bat.
// v4: 端口占用检查 — 启动前检查本应用占用端口(18080/16800/9222/15000/15001,
//     均为非熟知端口), 若被占用即视为上次残留的旧进程, 先杀掉占用进程再全新启动.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Net;
using System.Text;
using System.Threading;
using System.Windows.Forms;

static class Launcher
{
    static string BaseDir { get { return AppDomain.CurrentDomain.BaseDirectory; } }
    const int BACKEND_PORT = 18080;
    // 单实例运行期间的“重启请求”信号。
    // 重复启动请求不会再创建第二/第三个 Launcher 去排队等待 Mutex，
    // 而是通知当前主实例：等本次浏览器关闭并完成清理后，由当前实例自己重新启动。
    const string RESTART_EVENT_NAME = "Aria2PanFlow_RestartRequest";
    // 更新源(服务器端已部署 farstars.cc.cd 静态托管: version.json + Aria2-PanFlow.jar)
    const string UPDATE_JSON = "https://farstars.cc.cd/version.json";
    const int ARIA2_RPC_PORT = 16800;
    // CDP 端口不再固定为 9222。每个 Browser Session 启动时从 9222~9299 选择空闲端口，避免旧实例残留抢占。
    static int CurrentCdpPort = 9222;
    static int BrowserProcessId = 0;
    static string BrowserProfilePath = Path.Combine(BaseDir, "browser-profile");
    static readonly int[] SERVICE_PORTS = { BACKEND_PORT, ARIA2_RPC_PORT, 15000, 15001 };
    const int CDP_MIN_PORT = 9222;
    const int CDP_MAX_PORT = 9299;
    static bool startupPageReadyConfirmed = false;
    static readonly string[] SERVICE_NAMES = {
        "java.exe", "aria2c.exe", "aliyun_service.exe", "pan123_service.exe", "pan123_login.exe", "Photos.exe"
    };

    [System.Runtime.InteropServices.DllImport("user32.dll")]
    static extern bool SetProcessDpiAwarenessContext(int value);

    // Win32 无边框拖动：直接向当前窗口发送 WM_NCLBUTTONDOWN/HTCAPTION，
    // 由 Windows 原生窗口管理器接管移动。
    [System.Runtime.InteropServices.DllImport("user32.dll")]
    static extern bool ReleaseCapture();
    [System.Runtime.InteropServices.DllImport("user32.dll")]
    static extern IntPtr SendMessage(IntPtr hWnd, uint Msg, IntPtr wParam, IntPtr lParam);

    static int SelectCdpPort()
    {
        for (int port = CDP_MIN_PORT; port <= CDP_MAX_PORT; port++)
        {
            if (!IsPortOpen(port, 120))
            {
                CurrentCdpPort = port;
                LogErr("Browser Session 选择 CDP 端口=" + CurrentCdpPort + "。PID=" + Process.GetCurrentProcess().Id);
                return CurrentCdpPort;
            }
        }
        CurrentCdpPort = CDP_MIN_PORT;
        LogErr("未找到空闲 CDP 端口，回退到 " + CurrentCdpPort + "。PID=" + Process.GetCurrentProcess().Id);
        return CurrentCdpPort;
    }

    static int FindActiveCdpPort()
    {
        for (int port = CDP_MIN_PORT; port <= CDP_MAX_PORT; port++)
        {
            if (IsCdpUp(port)) return port;
        }
        return 0;
    }

    static int FindProfileBrowserPid()
    {
        try
        {
            string profileToken = BrowserProfilePath;
            string escaped = profileToken.Replace("'", "''");
            string psCmd = "Get-CimInstance Win32_Process -Filter \"name='msedge.exe' or name='chrome.exe'\" "
                + "| Where-Object { $_.CommandLine -like '*" + escaped + "*' } "
                + "| Select-Object -First 1 -ExpandProperty ProcessId";
            ProcessStartInfo psi = new ProcessStartInfo("powershell", "-NoProfile -ExecutionPolicy Bypass -Command \"" + psCmd + "\"");
            psi.CreateNoWindow = true;
            psi.UseShellExecute = false;
            psi.RedirectStandardOutput = true;
            Process p = Process.Start(psi);
            if (p == null) return 0;
            string output = p.StandardOutput.ReadToEnd();
            p.WaitForExit(3000);
            int pid;
            return int.TryParse(output.Trim(), out pid) ? pid : 0;
        }
        catch { return 0; }
    }

    static void RefreshOwnBrowserPid()
    {
        if (IsOwnBrowserAlive()) return;
        int pid = FindProfileBrowserPid();
        if (pid > 0)
        {
            BrowserProcessId = pid;
            LogErr("刷新 Browser Session PID=" + pid + "。profile=" + BrowserProfilePath + "，LauncherPID=" + Process.GetCurrentProcess().Id);
        }
    }

    static bool IsOwnBrowserAlive()
    {
        if (BrowserProcessId <= 0) return false;
        try
        {
            using (Process p = Process.GetProcessById(BrowserProcessId))
            {
                return !p.HasExited;
            }
        }
        catch { return false; }
    }

    static void KillOwnBrowserTree()
    {
        if (BrowserProcessId <= 0) return;
        int pid = BrowserProcessId;
        try
        {
            ProcessStartInfo psi = new ProcessStartInfo("taskkill", "/F /T /PID " + pid);
            psi.CreateNoWindow = true;
            psi.UseShellExecute = false;
            Process p = Process.Start(psi);
            if (p != null) p.WaitForExit(5000);
            LogErr("已停止当前 Browser Session。BrowserPID=" + pid + ", LauncherPID=" + Process.GetCurrentProcess().Id);
        }
        catch (Exception ex)
        {
            LogErr("停止当前 Browser Session 失败：" + ex.Message);
        }
        BrowserProcessId = 0;
    }

    [STAThread]
    static void Main()
    {
        // v: 每次启动覆盖日志文件(不累积历史日志)
        try { File.WriteAllText(Path.Combine(BaseDir, "launcher_error.log"), ""); } catch (Exception) { }
        // v21: DPI 感知(PER_MONITOR_AWARE_V2), 避免系统缩放导致字体发虚
        try { SetProcessDpiAwarenessContext(-4); } catch (Exception) { }
        try
        {
            RunMain();
        }
        catch (Exception ex)
        {
            LogErr("主流程未捕获异常: " + ex);
            try { MessageBox.Show("启动失败:\n" + ex.Message, "PanDownloader", MessageBoxButtons.OK, MessageBoxIcon.Error); } catch (Exception) { }
        }
    }

    static void RunMain()
    {
        bool createdNew;
        using (System.Threading.Mutex mtx = new System.Threading.Mutex(true, "Aria2PanFlow_SingleInstance", out createdNew))
        {
            if (!createdNew)
            {
                // 关键修复：绝不再让第二个 Launcher 等待主 Mutex。
                // 这样不会出现“第二次完成后自动启动第三次”以及多个 Launcher 互相等待。
                HandleDuplicateLaunchRequest();
                return;
            }

            try
            {
                RunSupervisorLoop();
            }
            finally
            {
                try { mtx.ReleaseMutex(); } catch (Exception) { }
            }
        }
    }

    static void RunSupervisorLoop()
    {
        EventWaitHandle restartEvent = null;
        try
        {
            bool created;
            restartEvent = new EventWaitHandle(false, EventResetMode.ManualReset, RESTART_EVENT_NAME, out created);
            restartEvent.Reset();

            // 这一版改为“单 Launcher + 4 分钟保活窗口”。
            // RunMainBody 内部的 RunMonitor 负责：浏览器关闭后不立即停服务，
            // 在 4 分钟窗口内收到新的启动请求则只恢复浏览器；超时才释放服务和端口。
            startupPageReadyConfirmed = false;
            RunMainBody().GetAwaiter().GetResult();

            LogErr("工作区生命周期结束，Launcher 即将退出。PID=" + Process.GetCurrentProcess().Id);
        }
        catch (Exception ex)
        {
            LogErr("RunSupervisorLoop 异常: " + ex);
        }
        finally
        {
            try { if (restartEvent != null) restartEvent.Dispose(); } catch (Exception) { }
        }
    }

    static void HandleDuplicateLaunchRequest()
    {
        int currentPid = Process.GetCurrentProcess().Id;
        LogErr("处理新的启动请求。PID=" + currentPid);

        try
        {
            // 新策略：不再等待主 Mutex，也不再猜测旧实例是否正在关闭。
            // 任何第二次启动请求都只向唯一主 Launcher 发送一个事件。
            // 主 Launcher 若仍在正常运行，会打开/恢复自己的调试浏览器；
            // 若浏览器刚关闭，则会在 4 分钟保活窗口内恢复浏览器；
            // 若 4 分钟已过且主 Launcher 已退出，本进程拿不到事件对象，直接结束，
            // 由后续正常启动流程接管。
            using (EventWaitHandle evt = EventWaitHandle.OpenExisting(RESTART_EVENT_NAME))
            {
                evt.Set();
                LogErr("已向当前主 Launcher 发送工作区恢复/重开请求，不创建排队实例。PID=" + currentPid);
            }
        }
        catch (WaitHandleCannotBeOpenedException)
        {
            LogErr("当前没有可恢复的主 Launcher，本次启动请求结束。PID=" + currentPid);
        }
        catch (Exception ex)
        {
            LogErr("处理重复启动请求异常: " + ex);
        }
    }

    static async System.Threading.Tasks.Task RunMainBody()
    {
        LogErr("RunMainBody 开始。PID=" + Process.GetCurrentProcess().Id);
        ShowSplash();
        try
        {
            SetSplashStage(0);   // 检查运行环境
            string javaExe = Path.Combine(BaseDir, "jre", "windows-x64", "bin", "java.exe");
            if (!File.Exists(javaExe))
            {
                CloseSplash();
                MessageBox.Show("未找到内置 JDK:\n" + javaExe + "\n\n请确认 jre\\windows-x64 目录完整。",
                    "PanDownloader", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return;
            }
            string jarPath = Path.Combine(BaseDir, "app", "Aria2-PanFlow.jar");
            if (!File.Exists(jarPath))
            {
                CloseSplash();
                MessageBox.Show("未找到后端程序:\n" + jarPath + "\n\n请确认 app\\Aria2-PanFlow.jar 存在。",
                    "PanDownloader", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return;
            }

            // 同步检查更新(服务未启动, 可安全替换 jar; 无更新/失败静默)
            await CheckUpdateAsync();
            SelectCdpPort();
            SetSplashStatus("正在启动服务…");
            // v4: 端口占用检查 — 若端口已被占用(非熟知端口, 占用即上次残留的旧进程),
            //     先杀掉占用进程再全新启动, 避免多套旧服务并存互相干扰
            // v21: 后台线程执行, 不阻塞 UI 线程(动画持续流畅)
            await System.Threading.Tasks.Task.Run(() =>
            {
                foreach (int port in SERVICE_PORTS)
                {
                    // v13: 跳过 9222 — 不误杀仍在使用的调试浏览器(页面关闭后浏览器窗口可能仍开着,
                    //     由 LaunchDebugBrowser 内部按需清理残留)
                    KillPortOwner(port);
                    // v5: 等待端口真正释放(Windows 进程退出/端口释放有延迟),
                    //     否则新服务/调试浏览器启动时可能因端口未释放而绑定失败
                    WaitForPortClosed(port, 3000);
                }
            });

            SetSplashStage(1);   // 启动 Aria2
            // 1. 启动 aria2 (Windows x64)
            string aria2Exe = Path.Combine(BaseDir, "aria2", "windows-x64", "aria2c.exe");
            if (File.Exists(aria2Exe))
            {
                StartHidden(aria2Exe, "--conf-path=aria2.conf", Path.Combine(BaseDir, "aria2", "windows-x64"));
            }
            SetSplashStatus("正在拉起 Aria2…");
            // 2. 启动 Java 后端
            StartHidden(javaExe, "-jar \"" + jarPath + "\"", BaseDir);
            SetSplashStatus("后端服务启动中…");
            SetSplashStage(2);   // 启动后端服务

            // 3. 等待后端就绪后打开页面: 若调试浏览器(9222)仍在运行则复用(在其新标签页打开,
            //    避免误杀用户正在使用的调试浏览器窗口), 否则全新启动调试浏览器(支持 CDP 自动获取)
            SetSplashStatus("等待服务就绪…");
            bool up = await WaitForPortAsync(BACKEND_PORT, 20000);
            if (up)
            {
                // 注意：这里不要提前将 lifecycle 标记为 page-open。
                // 必须等调试浏览器真正启动后再初始化/确认页面状态，
                // 否则 RunMonitor 可能把“尚未打开页面”误当成“页面已关闭”。
                SetSplashStatus("正在等待调试浏览器就绪…");
                SetSplashStage(3);   // 打开调试浏览器
                try
                {
                    // 当前 Launcher 自己负责完整 Browser Session。
                    // 不再扫描/强杀全局 browser-profile，避免误伤当前窗口或其它 Chromium 子进程。
                    await System.Threading.Tasks.Task.Run(() => WaitForPortClosed(CurrentCdpPort, 5000));
                    await LaunchDebugBrowser();

                    // 浏览器进程与 CDP 已经拉起后，再初始化页面生命周期。
                    // 给浏览器/前端留出启动时间，避免刚进入 RunMonitor 就读取到旧状态。
                    if (IsCdpUp(CurrentCdpPort))
                    {
                        try { ResetPageState(); } catch (Exception) { }
                        bool pageReady = await WaitForPageReadyAsync(12000);
                        startupPageReadyConfirmed = pageReady;
                        if (!pageReady)
                        {
                            LogErr("浏览器已启动但页面生命周期在 12 秒内未确认，进入保护监控。PID=" + Process.GetCurrentProcess().Id);
                        }
                    }
                }
                catch (Exception ex)
                {
                    // 不再回退默认浏览器，避免掩盖调试浏览器启动失败。
                    LogErr("启动调试浏览器阶段异常: " + ex);
                }
            }
            else
            {
                CloseSplash();
                MessageBox.Show("后端服务启动超时，请检查:\n1) 端口 " + BACKEND_PORT + " 是否被占用\n2) python_service 目录是否就位\n\n可稍后手动访问 http://localhost:" + BACKEND_PORT + "/",
                    "PanDownloader", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }

            // 只有确认 CDP 真正可用，才显示 100%。
            // 避免浏览器未打开时启动画面仍显示“全部完成”。
            if (IsCdpUp(CurrentCdpPort))
            {
                SetSplashStage(4);
                SetSplashStatus("启动完成 · 正在进入工作区…");
                SplashSleep(420);
            }
            else
            {
                SetSplashStatus("调试浏览器启动失败，正在退出…");
                SplashSleep(900);
            }

            CloseSplash();
            LogErr("启动流程完成，进入 RunMonitor。PID=" + Process.GetCurrentProcess().Id);
            // 4. 驻留监控: 页面关闭后自动停止服务并退出
            RunMonitor(startupPageReadyConfirmed);
        }
        catch (Exception ex)
        {
            // v23: async void 必须有 catch — 否则任何异常都会直接导致进程崩溃(闪退)
            LogErr("RunMainBody 启动异常: " + ex);
            try
            {
                MessageBox.Show("启动异常:\n" + ex.Message, "PanDownloader",
                    MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
            catch (Exception) { }
        }
        finally
        {
            CloseSplash();
        }
    }

    /// 启动画面(v5): 响应式双栏布局 + 现代深色质感。
    /// 重点修复：
    /// 1. 不再依赖固定 1800x900 坐标，避免不同分辨率/缩放下元素挤压、越界。
    /// 2. 品牌区与启动进度区分离，减少“所有内容堆在中间”的视觉拥挤。
    /// 3. 中文标题/阶段文字使用独立字号和安全边距，避免截图中的重叠与挤压感。
    /// 4. 进度百分比按阶段平滑分配，不再出现 89% 这种突兀数字。
    /// 5. 完成后短暂停留，让 100% / 启动完成状态能够被看见。
    static Form splash;
    static string splashStatusText = "正在启动…";
    static int splashStage = -1; // 加载阶段 0-4
    static Bitmap bgCache;
    static Font fontBrand, fontBrandSmall, fontPoem, fontSection, fontItem, fontItemSmall, fontPct, fontStatus;
    static StringFormat sfCenter, sfLeft;
    static readonly string[] splashPoems = { "山静似太古", "日长如小年" };
    static readonly string[] splashStages = { "检查运行环境", "启动 Aria2 下载器", "启动后端服务", "打开调试浏览器" };

    sealed class SplashForm : Form
    {
        const int WM_LBUTTONDOWN = 0x0201;
        const int WM_NCLBUTTONDOWN = 0x00A1;
        const int HTCAPTION = 2;

        public SplashForm()
        {
            SetStyle(ControlStyles.OptimizedDoubleBuffer |
                      ControlStyles.AllPaintingInWmPaint |
                      ControlStyles.UserPaint |
                      ControlStyles.ResizeRedraw, true);
        }

        // 关键修复：不再依赖 WM_NCHITTEST。
        // 对无边框 + 自绘 + Region 圆角窗口，最稳的是在收到左键按下时，
        // 直接模拟 Windows 标准标题栏拖动。
        // 这样无论鼠标落在背景、文字、进度区域还是自绘图形上，都由窗口管理器接管移动。
        protected override void WndProc(ref Message m)
        {
            if (m.Msg == WM_LBUTTONDOWN)
            {
                try
                {
                    ReleaseCapture();
                    SendMessage(this.Handle, WM_NCLBUTTONDOWN, (IntPtr)HTCAPTION, IntPtr.Zero);
                }
                catch
                {
                    // 拖动失败时仍让 WinForms 正常处理原始鼠标消息。
                    base.WndProc(ref m);
                }
                return;
            }

            base.WndProc(ref m);
        }
    }

    static float SX(float v) { return splash == null ? v : splash.ClientSize.Width * v; }
    static float SY(float v) { return splash == null ? v : splash.ClientSize.Height * v; }

    static void ShowSplash()
    {
        try
        {
            splash = new SplashForm();
            splash.FormBorderStyle = FormBorderStyle.None;
            splash.StartPosition = FormStartPosition.CenterScreen;
            splash.ShowInTaskbar = false;
            splash.TopMost = true;
            splash.BackColor = Color.FromArgb(12, 15, 21);

            // 根据工作区自适应，避免 1800x900 在较小屏幕/高缩放下被裁切。
            Rectangle wa = Screen.PrimaryScreen.WorkingArea;
            int maxW = Math.Max(1100, wa.Width - 80);
            int maxH = Math.Max(620, wa.Height - 80);
            int targetW = Math.Min(1800, maxW);
            int targetH = targetW / 2;
            if (targetH > maxH)
            {
                targetH = maxH;
                targetW = targetH * 2;
            }
            splash.Size = new Size(targetW, targetH);

            splash.Paint += new PaintEventHandler(Splash_Paint);

            // 拖动由 SplashForm.WndProc 的 WM_LBUTTONDOWN -> WM_NCLBUTTONDOWN/HTCAPTION 统一处理。
            // 不再依赖 MouseDown 事件或 WM_NCHITTEST，兼容无边框 + 自绘 + Region 圆角窗口。

            // 圆角。
            int rr = Math.Max(16, (int)(20 * (targetW / 1800f)));
            using (GraphicsPath path = new GraphicsPath())
            {
                path.AddArc(0, 0, rr, rr, 180, 90);
                path.AddArc(targetW - rr - 1, 0, rr, rr, 270, 90);
                path.AddArc(targetW - rr - 1, targetH - rr - 1, rr, rr, 0, 90);
                path.AddArc(0, targetH - rr - 1, rr, rr, 90, 90);
                path.CloseFigure();
                splash.Region = new Region(path);
            }

            float scale = Math.Max(0.78f, Math.Min(1.10f, targetW / 1800f));
            fontBrand = new Font("Segoe UI", 38 * scale, FontStyle.Bold, GraphicsUnit.Pixel);
            fontBrandSmall = new Font("Segoe UI", 18 * scale, FontStyle.Regular, GraphicsUnit.Pixel);
            fontPoem = new Font("Microsoft YaHei UI", 20 * scale, FontStyle.Regular, GraphicsUnit.Pixel);
            fontSection = new Font("Microsoft YaHei UI", 16 * scale, FontStyle.Bold, GraphicsUnit.Pixel);
            fontItem = new Font("Microsoft YaHei UI", 20 * scale, FontStyle.Regular, GraphicsUnit.Pixel);
            fontItemSmall = new Font("Microsoft YaHei UI", 13 * scale, FontStyle.Regular, GraphicsUnit.Pixel);
            fontPct = new Font("Segoe UI", 26 * scale, FontStyle.Bold, GraphicsUnit.Pixel);
            fontStatus = new Font("Microsoft YaHei UI", 14 * scale, FontStyle.Regular, GraphicsUnit.Pixel);

            sfCenter = new StringFormat
            {
                Alignment = StringAlignment.Center,
                LineAlignment = StringAlignment.Center,
                Trimming = StringTrimming.EllipsisCharacter
            };
            sfLeft = new StringFormat
            {
                Alignment = StringAlignment.Near,
                LineAlignment = StringAlignment.Center,
                Trimming = StringTrimming.EllipsisCharacter
            };

            try
            {
                bgCache = new Bitmap(targetW, targetH);
                using (Graphics bg = Graphics.FromImage(bgCache))
                {
                    DrawStaticLayer(bg);
                }
            }
            catch
            {
                bgCache = null;
            }

            splash.Show();
            splash.Refresh();
            Application.DoEvents();
        }
        catch (Exception) { }
    }

    static void Splash_Paint(object sender, PaintEventArgs e)
    {
        try
        {
            Graphics g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.InterpolationMode = InterpolationMode.HighQualityBicubic;
            g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.ClearTypeGridFit;

            int w = splash.ClientSize.Width;
            int h = splash.ClientSize.Height;

            if (bgCache != null)
                g.DrawImageUnscaled(bgCache, 0, 0);
            else
                DrawStaticLayer(g);

            // 主区分割线。
            float dividerX = w * 0.455f;
            using (Pen p = new Pen(Color.FromArgb(45, 56, 68), 1f))
                g.DrawLine(p, dividerX, h * 0.16f, dividerX, h * 0.84f);

            DrawBrandArea(g, w, h, dividerX);
            DrawProgressArea(g, w, h, dividerX);
        }
        catch (Exception) { }
    }

    static void DrawStaticLayer(Graphics g)
    {
        int w = splash.ClientSize.Width;
        int h = splash.ClientSize.Height;

        // 深色基底。
        using (LinearGradientBrush b = new LinearGradientBrush(
            splash.ClientRectangle,
            Color.FromArgb(10, 13, 19),
            Color.FromArgb(27, 33, 43),
            25f))
        {
            g.FillRectangle(b, splash.ClientRectangle);
        }

        // 左上暖色光晕 + 右下冷色光晕，增强层次但不喧宾夺主。
        DrawRadialGlow(g, w * 0.17f, h * 0.20f, w * 0.32f, Color.FromArgb(42, 216, 163, 91));
        DrawRadialGlow(g, w * 0.86f, h * 0.82f, w * 0.38f, Color.FromArgb(25, 82, 111, 158));

        // 很少量微粒，只做空间质感。
        Random rnd = new Random(20260813);
        for (int i = 0; i < 16; i++)
        {
            float x = 16 + (float)rnd.NextDouble() * (w - 32);
            float y = 12 + (float)rnd.NextDouble() * (h - 24);
            float r = 0.6f + (float)rnd.NextDouble() * 1.3f;
            int a = 24 + rnd.Next(30);
            using (SolidBrush b = new SolidBrush(i % 3 == 0
                ? Color.FromArgb(a, 238, 202, 118)
                : Color.FromArgb(a, 143, 159, 178)))
            {
                g.FillEllipse(b, x - r, y - r, r * 2, r * 2);
            }
        }

        // 很细的金色外框。
        using (Pen p = new Pen(Color.FromArgb(95, 179, 146, 84), 1f))
            g.DrawRectangle(p, 0.5f, 0.5f, w - 1f, h - 1f);
    }

    static void DrawRadialGlow(Graphics g, float cx, float cy, float radius, Color color)
    {
        using (GraphicsPath path = new GraphicsPath())
        {
            path.AddEllipse(cx - radius, cy - radius, radius * 2, radius * 2);
            using (PathGradientBrush b = new PathGradientBrush(path))
            {
                b.CenterColor = color;
                b.SurroundColors = new[] { Color.FromArgb(0, color.R, color.G, color.B) };
                g.FillPath(b, path);
            }
        }
    }

    static void DrawBrandArea(Graphics g, int w, int h, float dividerX)
    {
        float areaW = dividerX;
        float centerX = areaW * 0.50f;

        // Logo: 程序图标(从 exe 提取内嵌 HD 图标绘制, 替代原"舟"字印章)
        float logoY = h * 0.27f;
        float logoR = Math.Min(42f, h * 0.075f);
        try
        {
            using (Icon appIcon = Icon.ExtractAssociatedIcon(Application.ExecutablePath))
            {
                if (appIcon != null)
                    g.DrawImage(appIcon.ToBitmap(), centerX - logoR, logoY - logoR, logoR * 2, logoR * 2);
            }
        }
        catch (Exception) { }

        // 品牌名分成两行，避免原版一行大标题造成“顶天立地”的压迫感。
        using (SolidBrush b = new SolidBrush(Color.FromArgb(239, 202, 112)))
        {
            g.DrawString("PANDOWNLOADER", fontBrand, b, new RectangleF(0, h * 0.36f, areaW, 50 * (h / 900f)), sfCenter);
        }

        using (SolidBrush b = new SolidBrush(Color.FromArgb(184, 158, 103)))
        {
            g.DrawString(splashPoems[0] + "  ·  " + splashPoems[1], fontPoem, b,
                new RectangleF(0, h * 0.515f, areaW, 34 * (h / 900f)), sfCenter);
        }

        using (SolidBrush b = new SolidBrush(Color.FromArgb(118, 130, 145)))
        {
            g.DrawString("PanDownloader", fontBrandSmall, b,
                new RectangleF(0, h * 0.585f, areaW, 28 * (h / 900f)), sfCenter);
        }

        // 左下小提示，仅保留一个层级，避免原版底部信息过多。
        using (SolidBrush b = new SolidBrush(Color.FromArgb(91, 103, 118)))
        {
            g.DrawString("启动器正在准备运行环境", fontItemSmall, b,
                new RectangleF(0, h * 0.82f, areaW, 24 * (h / 900f)), sfCenter);
        }
    }

    static void DrawProgressArea(Graphics g, int w, int h, float dividerX)
    {
        float left = dividerX + w * 0.065f;
        float right = w - w * 0.065f;
        float contentW = right - left;

        using (SolidBrush b = new SolidBrush(Color.FromArgb(127, 141, 158)))
            g.DrawString("启动环境", fontSection, b,
                new RectangleF(left, h * 0.17f, contentW, 28 * (h / 900f)), sfLeft);

        string currentTitle = "准备启动";
        if (splashStage >= 0 && splashStage < splashStages.Length)
            currentTitle = splashStages[splashStage];
        else if (splashStage >= 4)
            currentTitle = "启动完成";

        using (SolidBrush b = new SolidBrush(Color.FromArgb(236, 241, 246)))
            g.DrawString(currentTitle, fontItem, b,
                new RectangleF(left, h * 0.215f, contentW, 34 * (h / 900f)), sfLeft);

        // 副状态：由当前阶段自动决定，不新增业务信息。
        string phaseText = splashStage >= 4 ? "所有组件已就绪" :
            splashStage >= 0 ? "正在处理 · " + splashStages[splashStage] : splashStatusText;
        using (SolidBrush b = new SolidBrush(Color.FromArgb(110, 125, 143)))
            g.DrawString(phaseText, fontItemSmall, b,
                new RectangleF(left, h * 0.265f, contentW, 24 * (h / 900f)), sfLeft);

        // 四步列表。
        float rowTop = h * 0.345f;
        float rowGap = h * 0.105f;
        float iconX = left + 12;
        float textX = left + 45;

        for (int i = 0; i < splashStages.Length; i++)
        {
            float cy = rowTop + i * rowGap;
            bool done = splashStage > i || splashStage >= 4;
            bool cur = splashStage == i;

            // 行背景，当前行略提亮。
            if (cur)
            {
                using (SolidBrush rb = new SolidBrush(Color.FromArgb(20, 240, 202, 118)))
                using (GraphicsPath rp = RoundedRect(left, cy - 25, contentW, 52, 12))
                    g.FillPath(rb, rp);
            }

            if (done)
            {
                using (SolidBrush b = new SolidBrush(Color.FromArgb(64, 187, 126)))
                    g.FillEllipse(b, iconX - 11, cy - 11, 22, 22);
                using (Pen p = new Pen(Color.White, 2f) { StartCap = LineCap.Round, EndCap = LineCap.Round })
                    g.DrawLines(p, new[] {
                        new PointF(iconX - 5, cy + 0),
                        new PointF(iconX - 1, cy + 4),
                        new PointF(iconX + 6, cy - 5)
                    });
            }
            else if (cur)
            {
                using (SolidBrush b = new SolidBrush(Color.FromArgb(239, 202, 112)))
                    g.FillEllipse(b, iconX - 11, cy - 11, 22, 22);
                using (SolidBrush b = new SolidBrush(Color.FromArgb(26, 31, 40)))
                    g.FillEllipse(b, iconX - 4, cy - 4, 8, 8);
            }
            else
            {
                using (Pen p = new Pen(Color.FromArgb(75, 91, 109), 1.5f))
                    g.DrawEllipse(p, iconX - 10, cy - 10, 20, 20);
            }

            using (SolidBrush b = new SolidBrush(done ? Color.FromArgb(222, 231, 239) :
                                                  cur ? Color.FromArgb(239, 202, 112) : Color.FromArgb(105, 118, 135)))
            {
                g.DrawString(splashStages[i], fontItem, b,
                    new RectangleF(textX, cy - 19, contentW - 60, 34), sfLeft);
            }

            string badge = done ? "完成" : cur ? "进行中" : "等待";
            Color badgeColor = done ? Color.FromArgb(77, 160, 111) :
                               cur ? Color.FromArgb(190, 154, 74) : Color.FromArgb(74, 88, 104);
            using (SolidBrush b = new SolidBrush(Color.FromArgb(215, badgeColor.R, badgeColor.G, badgeColor.B)))
                g.DrawString(badge, fontItemSmall, b,
                    new RectangleF(right - 54, cy - 12, 54, 24), new StringFormat
                    {
                        Alignment = StringAlignment.Far,
                        LineAlignment = StringAlignment.Center
                    });
        }

        // 进度条。
        float pct = splashStage < 0 ? 0f :
                    splashStage == 0 ? 9f :
                    splashStage == 1 ? 31f :
                    splashStage == 2 ? 56f :
                    splashStage == 3 ? 82f : 100f;

        float barY = h * 0.775f;
        float barH = Math.Max(5f, h * 0.007f);
        float barW = contentW * 0.72f;
        float barX = left;
        using (GraphicsPath track = RoundedRect(barX, barY, barW, barH, barH / 2))
        using (SolidBrush tb = new SolidBrush(Color.FromArgb(44, 54, 67)))
            g.FillPath(tb, track);
        if (pct > 0)
        {
            float fillW = barW * pct / 100f;
            using (GraphicsPath fill = RoundedRect(barX, barY, Math.Max(barH, fillW), barH, barH / 2))
            using (SolidBrush fb = new SolidBrush(Color.FromArgb(239, 202, 112)))
                g.FillPath(fb, fill);
        }

        using (SolidBrush b = new SolidBrush(Color.FromArgb(239, 202, 112)))
            g.DrawString(((int)pct) + "%", fontPct, b,
                new RectangleF(barX + barW + 20, barY - 14, contentW - barW - 20, 40), sfLeft);

        // 底部状态，不再和百分比抢同一视觉层。
        using (SolidBrush b = new SolidBrush(Color.FromArgb(148, 162, 177)))
            g.DrawString(splashStatusText, fontStatus, b,
                new RectangleF(left, h * 0.835f, contentW, 28), sfLeft);
    }

    static GraphicsPath RoundedRect(float x, float y, float width, float height, float radius)
    {
        float r = Math.Max(1f, Math.Min(radius, Math.Min(width, height) / 2f));
        GraphicsPath p = new GraphicsPath();
        p.AddArc(x, y, r * 2, r * 2, 180, 90);
        p.AddArc(x + width - r * 2, y, r * 2, r * 2, 270, 90);
        p.AddArc(x + width - r * 2, y + height - r * 2, r * 2, r * 2, 0, 90);
        p.AddArc(x, y + height - r * 2, r * 2, r * 2, 90, 90);
        p.CloseFigure();
        return p;
    }

    static void SetSplashStatus(string text)
    {
        try
        {
            splashStatusText = text;
            if (splash != null && !splash.IsDisposed)
            {
                splash.Invalidate();
                Application.DoEvents();
            }
        }
        catch (Exception) { }
    }

    static void SetSplashStage(int stage)
    {
        try
        {
            splashStage = stage;
            if (splash != null && !splash.IsDisposed)
            {
                splash.Invalidate();
                Application.DoEvents();
            }
        }
        catch (Exception) { }
    }

    static void SplashSleep(int ms)
    {
        DateTime deadline = DateTime.Now.AddMilliseconds(ms);
        while (DateTime.Now < deadline)
        {
            Application.DoEvents();
            Thread.Sleep(30);
        }
    }

    static void CloseSplash()
    {
        try
        {
            if (splash != null && !splash.IsDisposed)
            {
                try
                {
                    for (float o = 1f; o > 0.05f; o -= 0.18f)
                    {
                        splash.Opacity = o;
                        Thread.Sleep(14);
                    }
                }
                catch (Exception) { }
                splash.Close();
                splash.Dispose();
            }

            if (bgCache != null)
            {
                try { bgCache.Dispose(); } catch (Exception) { }
                bgCache = null;
            }

            DisposeSplashFonts();
            splash = null;
        }
        catch (Exception) { }
    }

    static void DisposeSplashFonts()
    {
        try { if (fontBrand != null) fontBrand.Dispose(); } catch (Exception) { }
        try { if (fontBrandSmall != null) fontBrandSmall.Dispose(); } catch (Exception) { }
        try { if (fontPoem != null) fontPoem.Dispose(); } catch (Exception) { }
        try { if (fontSection != null) fontSection.Dispose(); } catch (Exception) { }
        try { if (fontItem != null) fontItem.Dispose(); } catch (Exception) { }
        try { if (fontItemSmall != null) fontItemSmall.Dispose(); } catch (Exception) { }
        try { if (fontPct != null) fontPct.Dispose(); } catch (Exception) { }
        try { if (fontStatus != null) fontStatus.Dispose(); } catch (Exception) { }
        fontBrand = null;
        fontBrandSmall = null;
        fontPoem = null;
        fontSection = null;
        fontItem = null;
        fontItemSmall = null;
        fontPct = null;
        fontStatus = null;
        try { if (sfCenter != null) sfCenter.Dispose(); } catch (Exception) { }
        try { if (sfLeft != null) sfLeft.Dispose(); } catch (Exception) { }
        sfCenter = null;
        sfLeft = null;
    }

    /// 驻留监控(v8.2):
    /// 1) 启动后先进入保护窗口，不允许瞬时 closed/CDP 抖动触发停服；
    /// 2) 页面真正启动后，要求“页面关闭 + CDP 消失”或持续 CDP 消失才停止；
    /// 3) 所有等待/检测均在后台线程，避免关闭浏览器时 UI 卡死。
    /// 监控 + 4 分钟保活：浏览器关闭后不立即停止服务。
    /// 4 分钟内再次启动：恢复原 Browser Session；浏览器仍在则打开新标签页，
    /// 浏览器已关则复用原 profile/CDP 端口重新启动。4 分钟无请求才真正停止服务和释放端口。
    /// aria2 是否有活动下载任务(下载中视为正在使用, 4 分钟保活窗口持续重置, 不关闭)
    static bool HasActiveDownloads()
    {
        try
        {
            string secret = ReadAria2Secret();
            string body = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"aria2.tellActive\",\"params\":[\"token:" + secret + "\"]}";
            HttpWebRequest req = (HttpWebRequest)WebRequest.Create("http://127.0.0.1:16800/jsonrpc");
            req.Method = "POST";
            req.ContentType = "application/json";
            req.Timeout = 3000;
            byte[] data = Encoding.UTF8.GetBytes(body);
            req.ContentLength = data.Length;
            using (Stream s = req.GetRequestStream()) s.Write(data, 0, data.Length);
            using (HttpWebResponse resp = (HttpWebResponse)req.GetResponse())
            using (StreamReader sr = new StreamReader(resp.GetResponseStream(), Encoding.UTF8))
            {
                string json = sr.ReadToEnd();
                // result 为活动任务数组: 非空即下载中
                return json.Contains("\"result\":[") && !json.Contains("\"result\":[]");
            }
        }
        catch (Exception) { return false; }
    }

    /// 读取 aria2 配置文件中的 rpc-secret(与后端一致)
    static string ReadAria2Secret()
    {
        try
        {
            string conf = Path.Combine(BaseDir, "aria2", "windows-x64", "aria2.conf");
            foreach (string line in File.ReadAllLines(conf))
            {
                string t = line.Trim();
                if (t.StartsWith("rpc-secret", StringComparison.OrdinalIgnoreCase))
                {
                    int eq = t.IndexOf('=');
                    if (eq > 0) return t.Substring(eq + 1).Trim();
                }
            }
        }
        catch (Exception) { }
        return "";
    }

    static void RunMonitor(bool pageReadyConfirmed)
    {
        const int STARTUP_GRACE_MS = 10000;
        const int POLL_MS = 1000;
        const int CLOSE_CONFIRM_COUNT = 3;
        const int CDP_DOWN_LIMIT = 8;
        const int IDLE_GRACE_MS = 4 * 60 * 1000;

        LogErr("RunMonitor 开始保护期。PID=" + Process.GetCurrentProcess().Id
            + ", BrowserPID=" + BrowserProcessId + ", CDP=" + CurrentCdpPort);
        Thread.Sleep(STARTUP_GRACE_MS);

        bool everConfirmedOpen = pageReadyConfirmed;
        int closeCount = 0;
        int cdpDownCount = 0;
        bool browserWasClosed = false;

        if (everConfirmedOpen)
            LogErr("RunMonitor 接收启动阶段 PAGE_OPEN_CONFIRM。PID=" + Process.GetCurrentProcess().Id);

        while (true)
        {
            Thread.Sleep(POLL_MS);

            // 第二次启动/恢复请求：如果当前还处于正常工作状态，就打开新标签页；
            // 如果浏览器刚关闭，就恢复同一 profile + CDP；服务端口始终保持不动。
            if (HasRestartRequest())
            {
                ResetRestartRequest();
                try
                {
                    LogErr("RunMonitor 收到恢复请求：4 分钟保活期间不重启后端，仅恢复调试浏览器。PID=" + Process.GetCurrentProcess().Id);
                    awaitableRestoreBrowser();
                    browserWasClosed = false;
                    everConfirmedOpen = true;
                    closeCount = 0;
                    cdpDownCount = 0;
                    LogErr("RunMonitor 浏览器恢复请求处理完成。PID=" + Process.GetCurrentProcess().Id);
                }
                catch (Exception ex)
                {
                    LogErr("RunMonitor 恢复浏览器失败：" + ex);
                }
            }

            RefreshOwnBrowserPid();
            bool browserAlive = IsOwnBrowserAlive();
            bool cdpUp = IsCdpUp(CurrentCdpPort);
            LifecycleState state = QueryLifecycleState();
            bool pageClosed = state == LifecycleState.Closed;

            if (state == LifecycleState.Open)
            {
                everConfirmedOpen = true;
                browserWasClosed = false;
                closeCount = 0;
                cdpDownCount = 0;
                continue;
            }

            if (!browserAlive)
            {
                if (!cdpUp && pageClosed)
                {
                    closeCount++;
                    LogErr("RunMonitor 检测到工作区关闭：BrowserPID=" + BrowserProcessId
                        + ", pageStatus=" + state + ", cdpUp=" + cdpUp
                        + ", closeConfirm=" + closeCount + "/" + CLOSE_CONFIRM_COUNT
                        + ", PID=" + Process.GetCurrentProcess().Id);
                    if (everConfirmedOpen && closeCount >= CLOSE_CONFIRM_COUNT)
                    {
                        browserWasClosed = true;
                        break;
                    }
                }
                else
                {
                    closeCount = 0;
                }
                continue;
            }

            if (!cdpUp)
            {
                cdpDownCount++;
                if (pageClosed) closeCount++; else closeCount = 0;
                LogErr("RunMonitor BrowserPID 仍存活但 CDP 不可用：BrowserPID=" + BrowserProcessId
                    + ", cdp=" + CurrentCdpPort + ", pageStatus=" + state
                    + ", cdpDownCount=" + cdpDownCount + "/" + CDP_DOWN_LIMIT
                    + ", closeConfirm=" + closeCount + "/" + CLOSE_CONFIRM_COUNT
                    + ", PID=" + Process.GetCurrentProcess().Id);
                if (everConfirmedOpen && pageClosed && cdpDownCount >= CDP_DOWN_LIMIT && closeCount >= CLOSE_CONFIRM_COUNT)
                {
                    browserWasClosed = true;
                    break;
                }
                continue;
            }

            cdpDownCount = 0;
            if (!pageClosed)
            {
                closeCount = 0;
                continue;
            }

            closeCount++;
            LogErr("RunMonitor 页面已报告 Closed，但 Browser Session 与 CDP 仍存活：closeConfirm="
                + closeCount + "/" + CLOSE_CONFIRM_COUNT + ", BrowserPID=" + BrowserProcessId
                + "。PID=" + Process.GetCurrentProcess().Id);
            if (everConfirmedOpen && closeCount >= CLOSE_CONFIRM_COUNT)
            {
                browserWasClosed = true;
                break;
            }
        }

        if (!browserWasClosed) return;

        // 关键变化：浏览器关闭后只进入 4 分钟保活，不立即 taskkill。
        // 后端、aria2、端口、Launcher Mutex 全部保持。
        DateTime idleDeadline = DateTime.Now.AddMilliseconds(IDLE_GRACE_MS);
        LogErr("RunMonitor 浏览器已关闭，进入 4 分钟保活窗口；后端/Aria2/端口暂不释放。PID=" + Process.GetCurrentProcess().Id);

        while (DateTime.Now < idleDeadline)
        {
            Thread.Sleep(POLL_MS);

            // 下载进行中视为正在使用: 4 分钟保活窗口持续重置, 下载不结束不关闭
            if (HasActiveDownloads())
            {
                idleDeadline = DateTime.Now.AddMilliseconds(IDLE_GRACE_MS);
            }

            if (HasRestartRequest())
            {
                ResetRestartRequest();
                LogErr("RunMonitor 在 4 分钟保活窗口内收到恢复请求，尝试恢复调试浏览器。PID=" + Process.GetCurrentProcess().Id);

                try
                {
                    awaitableRestoreBrowser();
                    startupPageReadyConfirmed = true;
                    everConfirmedOpen = true;
                    closeCount = 0;
                    cdpDownCount = 0;
                    LogErr("RunMonitor 已恢复调试浏览器；继续保活监控。PID=" + Process.GetCurrentProcess().Id);

                    // 恢复成功后回到正常监控，下一次关闭仍会重新进入 4 分钟窗口。
                    while (true)
                    {
                        Thread.Sleep(POLL_MS);

                        if (HasRestartRequest())
                        {
                            ResetRestartRequest();
                            try
                            {
                                LogErr("RunMonitor 工作区运行中收到再次启动请求，打开新标签页/恢复浏览器。PID=" + Process.GetCurrentProcess().Id);
                                awaitableRestoreBrowser();
                                continue;
                            }
                            catch (Exception ex)
                            {
                                LogErr("RunMonitor 运行中恢复浏览器失败：" + ex);
                            }
                        }

                        RefreshOwnBrowserPid();
                        bool alive2 = IsOwnBrowserAlive();
                        bool cdp2 = IsCdpUp(CurrentCdpPort);
                        LifecycleState st2 = QueryLifecycleState();

                        if (st2 == LifecycleState.Open)
                        {
                            closeCount = 0;
                            continue;
                        }

                        if (!alive2 && !cdp2 && st2 == LifecycleState.Closed)
                        {
                            closeCount++;
                            if (closeCount >= CLOSE_CONFIRM_COUNT) break;
                        }
                        else
                        {
                            closeCount = 0;
                        }
                    }

                    idleDeadline = DateTime.Now.AddMilliseconds(IDLE_GRACE_MS);
                    LogErr("RunMonitor 再次检测到浏览器关闭，重新进入 4 分钟保活窗口。PID=" + Process.GetCurrentProcess().Id);
                    continue;
                }
                catch (Exception ex)
                {
                    LogErr("RunMonitor 保活窗口恢复浏览器失败：" + ex);
                }
            }
        }

        // 4 分钟无新的启动/恢复请求，才真正释放资源。
        LogErr("RunMonitor 4 分钟内无恢复请求，开始最终停止 Browser Session、服务并释放端口。PID=" + Process.GetCurrentProcess().Id);
        try
        {
            System.Threading.Tasks.Task.Run(() =>
            {
                try { KillOwnBrowserTree(); } catch (Exception ex) { LogErr("后台停止 Browser Session 异常: " + ex); }
                try { StopAllServices(); } catch (Exception ex) { LogErr("后台停止服务异常: " + ex); }
                try { WaitForPortClosed(BACKEND_PORT, 5000); } catch (Exception) { }
                try { WaitForPortClosed(ARIA2_RPC_PORT, 5000); } catch (Exception) { }
                try { WaitForPortClosed(CurrentCdpPort, 5000); } catch (Exception) { }
                LogErr("RunMonitor 最终清理完成，端口已释放。PID=" + Process.GetCurrentProcess().Id);
            }).Wait(25000);
        }
        catch (Exception ex)
        {
            LogErr("RunMonitor 最终停止服务异常: " + ex);
        }
    }

    static bool HasRestartRequest()
    {
        try
        {
            using (EventWaitHandle evt = EventWaitHandle.OpenExisting(RESTART_EVENT_NAME))
            {
                return evt.WaitOne(0);
            }
        }
        catch { return false; }
    }

    static void ResetRestartRequest()
    {
        try
        {
            using (EventWaitHandle evt = EventWaitHandle.OpenExisting(RESTART_EVENT_NAME))
                evt.Reset();
        }
        catch { }
    }

    static void awaitableRestoreBrowser()
    {
        // 同步桥接：RunMonitor 本身是同步监控线程，内部等待浏览器恢复完成。
        try
        {
            bool browserAlive = IsOwnBrowserAlive() && IsCdpUp(CurrentCdpPort);
            string url = "http://localhost:" + BACKEND_PORT + "/";

            if (browserAlive)
            {
                LogErr("恢复策略：当前调试浏览器仍可用，打开新标签页。PID=" + Process.GetCurrentProcess().Id);
                OpenPageInDebugBrowserAsync(url).GetAwaiter().GetResult();
            }
            else
            {
                LogErr("恢复策略：原调试浏览器已关闭，复用当前 profile + CDP 端口重新启动浏览器。PID=" + Process.GetCurrentProcess().Id);
                if (!IsPortOpen(CurrentCdpPort, 200))
                {
                    LaunchDebugBrowser().GetAwaiter().GetResult();
                }
                else
                {
                    // 端口仍被占用时先尝试直接打开新标签页；若不是本 Session，由 CDP 检查结果决定。
                    OpenPageInDebugBrowserAsync(url).GetAwaiter().GetResult();
                }
            }

            if (IsCdpUp(CurrentCdpPort))
            {
                try { ResetPageState(); } catch { }
                DateTime deadline = DateTime.Now.AddMilliseconds(12000);
                int openCount = 0;
                while (DateTime.Now < deadline)
                {
                    if (IsCdpUp(CurrentCdpPort) && QueryLifecycleState() == LifecycleState.Open)
                    {
                        openCount++;
                        if (openCount >= 2)
                        {
                            RefreshOwnBrowserPid();
                            LogErr("恢复后的页面生命周期已连续确认为 Open。BrowserPID=" + BrowserProcessId + ", CDP=" + CurrentCdpPort);
                            return;
                        }
                    }
                    else
                    {
                        openCount = 0;
                    }
                    Thread.Sleep(500);
                }
                throw new InvalidOperationException("恢复后页面生命周期未在 12 秒内恢复为 Open。");
            }
            throw new InvalidOperationException("恢复后 CDP 未重新就绪。");
        }
        catch (Exception ex)
        {
            LogErr("恢复调试浏览器失败：" + ex);
            throw;
        }
    }

    enum LifecycleState
    {
        Unknown,
        Open,
        Closed
    }

    /// 返回三态生命周期，而不是把请求异常直接当作 closed=true。
    /// 这是本次修复的关键：网络超时/后端短暂不可达只能算 Unknown。
    static LifecycleState QueryLifecycleState()
    {
        try
        {
            HttpWebRequest req = (HttpWebRequest)WebRequest.Create(
                "http://localhost:" + BACKEND_PORT + "/api/lifecycle/status");
            req.Timeout = 1200;
            req.Method = "GET";

            using (HttpWebResponse resp = (HttpWebResponse)req.GetResponse())
            using (StreamReader sr = new StreamReader(resp.GetResponseStream(), Encoding.UTF8))
            {
                string body = sr.ReadToEnd();

                if (body.IndexOf("\"closed\":true", StringComparison.OrdinalIgnoreCase) >= 0)
                    return LifecycleState.Closed;

                if (body.IndexOf("\"closed\":false", StringComparison.OrdinalIgnoreCase) >= 0)
                    return LifecycleState.Open;

                return LifecycleState.Unknown;
            }
        }
        catch (Exception)
        {
            return LifecycleState.Unknown;
        }
    }

    /// 浏览器启动后等待页面生命周期接口进入稳定可用状态。
    /// 注意：这里允许 Unknown/网络抖动，不把它当作“页面已关闭”。
    static async System.Threading.Tasks.Task<bool> WaitForPageReadyAsync(int timeoutMs)
    {
        DateTime deadline = DateTime.Now.AddMilliseconds(timeoutMs);
        int openCount = 0;

        while (DateTime.Now < deadline)
        {
            if (!IsCdpUp(CurrentCdpPort))
            {
                await System.Threading.Tasks.Task.Delay(250);
                continue;
            }

            LifecycleState state = QueryLifecycleState();

            if (state == LifecycleState.Open)
            {
                RefreshOwnBrowserPid();
                openCount++;
                if (openCount >= 2)
                {
                    LogErr("页面生命周期已连续确认为 Open。PID=" + Process.GetCurrentProcess().Id);
                    return true;
                }
            }
            else
            {
                openCount = 0;
            }

            await System.Threading.Tasks.Task.Delay(500);
        }

        return false;
    }

    /// 重置页面生命周期状态: POST /api/lifecycle/page-open (避免上次关闭遗留误判)
    static void ResetPageState()
    {
        try
        {
            HttpWebRequest req = (HttpWebRequest)WebRequest.Create(
                "http://localhost:" + BACKEND_PORT + "/api/lifecycle/page-open");
            req.Timeout = 1000;   // v20: 缩短阻塞, 减少动画停顿
            req.Method = "POST";
            using (HttpWebResponse resp = (HttpWebResponse)req.GetResponse()) { }
        }
        catch (Exception) { }
    }

    /// 写入错误日志(launcher_error.log), 用于定位双击闪退等异常
    static void LogErr(string msg)
    {
        try
        {
            File.AppendAllText(Path.Combine(BaseDir, "launcher_error.log"),
                DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + " " + msg + "\r\n");
        }
        catch (Exception) { }
    }

    /// 查询后端生命周期状态, 返回页面是否已关闭
    static bool QueryPageClosed()
    {
        try
        {
            HttpWebRequest req = (HttpWebRequest)WebRequest.Create("http://localhost:" + BACKEND_PORT + "/api/lifecycle/status");
            req.Timeout = 3000;
            req.Method = "GET";
            using (HttpWebResponse resp = (HttpWebResponse)req.GetResponse())
            using (StreamReader sr = new StreamReader(resp.GetResponseStream(), Encoding.UTF8))
            {
                string body = sr.ReadToEnd();
                return body.Contains("\"closed\":true");
            }
        }
        catch (Exception)
        {
            // 后端不可达(服务已停)视为页面已关闭
            return true;
        }
    }

    /// 停止全部相关服务进程
    static void StopAllServices()
    {
        foreach (string name in SERVICE_NAMES)
        {
            try
            {
                ProcessStartInfo psi = new ProcessStartInfo("taskkill", "/F /IM " + name);
                psi.CreateNoWindow = true;
                psi.UseShellExecute = false;
                Process p = Process.Start(psi);
                if (p != null) p.WaitForExit(3000);
            }
            catch (Exception) { }
        }
    }

    /// 以调试模式启动浏览器(v9 强制调试):
    /// 1) 每次启动前按 profile 精确清理本项目的浏览器进程(不影响用户自开的普通浏览器),
    ///    并杀掉 9222 占用者的进程树(浏览器子进程/后台进程残留是持锁主因);
    /// 2) 重试 5 次并轮换 Edge/Chrome(避免单一浏览器单实例/后台进程冲突);
    /// 3) 增强启动参数(--no-first-run/--remote-allow-origins 等);
    /// 4) 用 CDP 接口(/json/version)真实验证;
    /// 5) 全部失败时【不再回退普通打开】, 明确弹窗提示原因 — 确保不会出现"非调试浏览器".
    static async System.Threading.Tasks.Task LaunchDebugBrowser()
    {
        string url = "http://localhost:" + BACKEND_PORT + "/";
        string[] browsers = GetAllStableBrowsers();
        if (browsers.Length == 0)
        {
            LogErr("未找到 Edge/Chrome, 无法启动调试浏览器");
            try
            {
                MessageBox.Show("未找到 Edge 或 Chrome，无法启动调试浏览器。\n请先安装 Microsoft Edge 或 Google Chrome 后重试。",
                    "PanDownloader", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
            catch (Exception) { }
            return;
        }

        string profile = BrowserProfilePath;
        Directory.CreateDirectory(profile);
        LogErr("Browser Session 启动：profile=" + profile + ", cdp=" + CurrentCdpPort + "。LauncherPID=" + Process.GetCurrentProcess().Id);
        string args = "--no-first-run --no-default-browser-check --remote-allow-origins=*"
            + " --remote-debugging-port=" + CurrentCdpPort
            + " --user-data-dir=\"" + profile + "\" \"" + url + "\"";

        bool debugUp = false;
        for (int attempt = 0; attempt < 5 && !debugUp; attempt++)
        {
            string browser = browsers[attempt % browsers.Length];
            // 不再在每次重试时扫描并强杀 profile 浏览器。
            // 当前主 Launcher 自己拥有本次 Browser Session；重复启动请求不会创建第二个 Launcher，
            // 因而不需要通过全局进程扫描去“清场”。只确保本实例选择的 CDP 端口已释放。
            await System.Threading.Tasks.Task.Run(() => WaitForPortClosed(CurrentCdpPort, 3000));
            if (attempt > 0) await System.Threading.Tasks.Task.Delay(2000);   // 重试前多等, 让进程完全退出/profile 锁释放
            // 清理 profile 残留锁文件(被锁会导致调试实例启动失败)
            try
            {
                foreach (string lockName in new string[] { "SingletonLock", "SingletonCookie", "SingletonSocket" })
                {
                    string lockFile = Path.Combine(profile, lockName);
                    if (File.Exists(lockFile)) File.Delete(lockFile);
                }
            }
            catch (Exception) { }

            ProcessStartInfo psi = new ProcessStartInfo();
            psi.FileName = browser;
            psi.Arguments = args;
            // UseShellExecute=false: 直接 CreateProcess 启动, 避免 shell 转义导致启动失败
            psi.UseShellExecute = false;
            psi.CreateNoWindow = true;
            Process p = new Process();
            p.StartInfo = psi;
            p.Start();
            BrowserProcessId = p.Id;
            LogErr("Browser Session 已创建：BrowserPID=" + BrowserProcessId + ", CDP=" + CurrentCdpPort);
            await System.Threading.Tasks.Task.Delay(300);
            RefreshOwnBrowserPid();

            // 启动后校验: 20 秒内 CDP 接口(/json/version)可用
            // v21: IsCdpUp 后台执行 + Task.Delay, UI 线程空闲动画持续流畅(无需 DoEvents 泵)
            DateTime cdpDeadline = DateTime.Now.AddMilliseconds(20000);
            while (!debugUp && DateTime.Now < cdpDeadline)
            {
                debugUp = await System.Threading.Tasks.Task.Run(() => IsCdpUp(CurrentCdpPort));
                if (!debugUp) await System.Threading.Tasks.Task.Delay(30);
            }
            if (!debugUp)
            {
                LogErr("调试浏览器启动失败: " + browser + " (第" + (attempt + 1) + "次)");
                // 杀掉可能以普通模式启动的实例(避免残留普通浏览器窗口), 稍候重试
                try { if (p != null && !p.HasExited) p.Kill(); } catch (Exception) { }
                await System.Threading.Tasks.Task.Delay(1500);
            }
        }
        if (!debugUp)
        {
            // v13: profile 可能因多次异常强杀而损坏(Edge/Chrome 启动即退出, 表现为"多次启动都打不开"),
            // 自动备份并重建 profile 后再启动一次
            LogErr("5 次启动失败, 尝试重置 browser-profile 后重试");
            try
            {
                string backup = profile + ".bak_" + DateTime.Now.ToString("yyyyMMddHHmmss");
                if (Directory.Exists(profile)) Directory.Move(profile, backup);
                Directory.CreateDirectory(profile);
            }
            catch (Exception) { }
            try
            {
                    await System.Threading.Tasks.Task.Run(() => WaitForPortClosed(CurrentCdpPort, 3000));
                string browser = browsers[0];
                ProcessStartInfo psi = new ProcessStartInfo();
                psi.FileName = browser;
                psi.Arguments = args;
                psi.UseShellExecute = false;
                psi.CreateNoWindow = true;
                Process p = new Process();
                p.StartInfo = psi;
                p.Start();
                BrowserProcessId = p.Id;
                LogErr("Browser Session 重置后创建：BrowserPID=" + BrowserProcessId + ", CDP=" + CurrentCdpPort);
                await System.Threading.Tasks.Task.Delay(300);
                RefreshOwnBrowserPid();
                DateTime resetDeadline = DateTime.Now.AddMilliseconds(15000);
                while (!debugUp && DateTime.Now < resetDeadline)
                {
                    debugUp = await System.Threading.Tasks.Task.Run(() => IsCdpUp(CurrentCdpPort));
                    if (!debugUp) await System.Threading.Tasks.Task.Delay(30);
                }
                if (debugUp)
                {
                    LogErr("browser-profile 已重置, 调试浏览器启动成功");
                    try
                    {
                        MessageBox.Show("调试浏览器数据目录已自动重置(此前可能因多次异常退出而损坏)。\n\n请重新登录各网盘网站后, 再使用「自动获取」功能。",
                            "PanDownloader", MessageBoxButtons.OK, MessageBoxIcon.Information);
                    }
                    catch (Exception) { }
                }
                else
                {
                    try { if (p != null && !p.HasExited) p.Kill(); } catch (Exception) { }
                }
            }
            catch (Exception) { }
        }
        if (!debugUp)
        {
            // v9: 不再回退普通打开 — 保证不会出现"非调试浏览器";
            // 失败时明确提示原因, 用户可查看日志或手动处理
            LogErr("调试浏览器启动失败(含 profile 重置后仍失败), 已放弃打开页面");
            try
            {
                MessageBox.Show(
                    "调试浏览器启动失败(多次尝试且已重置数据目录仍未检测到 CDP 端口 " + CurrentCdpPort + ")。\n\n"
                    + "可能原因: 杀毒软件/系统策略拦截了浏览器的调试端口, 或浏览器安装异常。\n"
                    + "详情请查看 launcher_error.log。\n\n"
                    + "可手动打开页面: " + url,
                    "PanDownloader", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
            catch (Exception) { }
        }
    }

    /// 查找全部可用调试浏览器(优先 Edge, 其次 Chrome), 供重试时轮换
    static string[] GetAllStableBrowsers()
    {
        List<string> found = new List<string>();
        string[] candidates = {
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), "Microsoft", "Edge", "Application", "msedge.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Microsoft", "Edge", "Application", "msedge.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Google", "Chrome", "Application", "chrome.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), "Google", "Chrome", "Application", "chrome.exe")
        };
        foreach (string c in candidates)
        {
            if (File.Exists(c) && !found.Contains(c)) found.Add(c);
        }
        return found.ToArray();
    }

    /// 快速判断是否有 Edge/Chrome 进程在运行(tasklist, 供 KillProfileBrowsers 空跑跳过)
    static bool HasBrowserProcess()
    {
        try
        {
            foreach (string name in new string[] { "msedge.exe", "chrome.exe" })
            {
                ProcessStartInfo psi = new ProcessStartInfo("tasklist", "/FI \"IMAGENAME eq " + name + "\"");
                psi.CreateNoWindow = true;
                psi.UseShellExecute = false;
                psi.RedirectStandardOutput = true;
                Process p = Process.Start(psi);
                if (p == null) continue;
                string output = p.StandardOutput.ReadToEnd();
                p.WaitForExit(2000);
                if (output.IndexOf(name, StringComparison.OrdinalIgnoreCase) >= 0) return true;
            }
        }
        catch (Exception) { }
        return false;
    }

    /// 精确杀掉所有使用本项目 browser-profile 的浏览器进程(不影响用户自开的普通浏览器),
    /// 防止后台/子进程残留持有 profile 锁导致调试实例启动失败
    static void KillProfileBrowsers()
    {
        // v14: 无浏览器进程时跳过 PowerShell 枚举(干净启动省 1-3 秒)
        if (!HasBrowserProcess()) return;
        try
        {
            // 用 PowerShell 替代 wmic(新 Windows 已弃用 wmic), 更可靠
            string psCmd = "Get-CimInstance Win32_Process -Filter \"name='msedge.exe' or name='chrome.exe'\" "
                + "| Where-Object { $_.CommandLine -like '*browser-profile*' } "
                + "| ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }";
            ProcessStartInfo psi = new ProcessStartInfo("powershell",
                "-NoProfile -ExecutionPolicy Bypass -Command \"" + psCmd + "\"");
            psi.CreateNoWindow = true;
            psi.UseShellExecute = false;
            Process p = Process.Start(psi);
            if (p != null)
            {
                // v20: 轮询等待 PowerShell 完成, 期间持续泵消息保持动画流畅
                DateTime dl = DateTime.Now.AddMilliseconds(15000);
                while (!p.HasExited && DateTime.Now < dl)
                {
                    Application.DoEvents();
                    Thread.Sleep(30);
                }
            }
        }
        catch (Exception) { }
    }

    /// 校验 9222 的 CDP 服务真实可用(HTTP /json/version 返回 200), 而非仅 TCP 端口开放
    static bool IsCdpUp(int port)
    {
        try
        {
            HttpWebRequest req = (HttpWebRequest)WebRequest.Create("http://127.0.0.1:" + port + "/json/version");
            req.Timeout = 400;   // v20: 短超时, 未监听时快速失败, 配合 30ms 高频轮询
            using (HttpWebResponse resp = (HttpWebResponse)req.GetResponse())
            {
                return (int)resp.StatusCode == 200;
            }
        }
        catch (Exception) { return false; }
    }

    /// 若 CDP 调试浏览器在运行, 通过 PUT /json/new 在其新标签页中打开页面(不弹出普通浏览器);
    /// 调试浏览器不可用时由本实例拉起调试浏览器(aria2/后端服务已由驻留实例管理)
    static async System.Threading.Tasks.Task OpenPageInDebugBrowserAsync(string url)
    {
        try
        {
            if (IsCdpUp(CurrentCdpPort))
            {
                HttpWebRequest req = (HttpWebRequest)WebRequest.Create(
                    "http://127.0.0.1:" + CurrentCdpPort + "/json/new?" + Uri.EscapeDataString(url));
                req.Timeout = 3000;
                req.Method = "PUT";
                using (HttpWebResponse resp = (HttpWebResponse)req.GetResponse())
                {
                    if ((int)resp.StatusCode == 200) return;
                }
            }
        }
        catch (Exception ex)
        {
            LogErr("打开现有调试浏览器标签失败: " + ex);
        }

        await LaunchDebugBrowser();
    }

    /// 读取系统默认浏览器可执行路径: UserChoice → ProgId → shell\open\command
    static string GetDefaultBrowserPath()
    {
        try
        {
            string progId = null;
            using (Microsoft.Win32.RegistryKey key = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(
                @"Software\Microsoft\Windows\Shell\Associations\UrlAssociations\http\UserChoice"))
            {
                if (key != null) progId = key.GetValue("ProgId") as string;
            }
            if (string.IsNullOrEmpty(progId)) return null;

            string cmd = null;
            using (Microsoft.Win32.RegistryKey key = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(
                @"Software\Classes\" + progId + @"\shell\open\command"))
            {
                if (key != null) cmd = key.GetValue(null) as string;
            }
            if (string.IsNullOrEmpty(cmd))
            {
                using (Microsoft.Win32.RegistryKey key = Microsoft.Win32.Registry.ClassesRoot.OpenSubKey(
                    progId + @"\shell\open\command"))
                {
                    if (key != null) cmd = key.GetValue(null) as string;
                }
            }
            if (string.IsNullOrEmpty(cmd)) return null;

            // 解析 exe 路径: 优先引号内, 否则取第一个空格前 token
            cmd = cmd.Trim();
            string path = null;
            if (cmd.StartsWith("\""))
            {
                int end = cmd.IndexOf('"', 1);
                if (end > 0) path = cmd.Substring(1, end - 1);
            }
            else
            {
                int sp = cmd.IndexOf(' ');
                path = sp > 0 ? cmd.Substring(0, sp) : cmd;
            }
            return string.IsNullOrEmpty(path) ? null : path;
        }
        catch (Exception) { return null; }
    }

    /// 判断默认浏览器是否为 Chromium 内核(支持 CDP)
    static bool IsChromiumDefault()
    {
        try
        {
            using (Microsoft.Win32.RegistryKey key = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(
                @"Software\Microsoft\Windows\Shell\Associations\UrlAssociations\http\UserChoice"))
            {
                string progId = key == null ? null : key.GetValue("ProgId") as string;
                if (string.IsNullOrEmpty(progId)) return true;   // 未知时按 Chromium 处理(尝试调试启动)
                string p = progId.ToLower();
                return p.Contains("chrome") || p.Contains("edge") || p.Contains("chromium")
                    || p.Contains("brave") || p.Contains("360") || p.Contains("qqbrowser")
                    || p.Contains("centbrowser") || p.Contains("vivaldi") || p.Contains("opera")
                    || p.Contains("sogou") || p.Contains("ucbrowser");
            }
        }
        catch (Exception) { return true; }
    }

    static void StartHidden(string exe, string args, string workDir)
    {
        try
        {
            ProcessStartInfo psi = new ProcessStartInfo();
            psi.FileName = exe;
            psi.Arguments = args;
            psi.WorkingDirectory = workDir;
            psi.UseShellExecute = false;
            psi.CreateNoWindow = true;
            psi.WindowStyle = ProcessWindowStyle.Hidden;
            // 清除系统代理环境变量: 用户 VPN 可能设置 HTTPS_PROXY=127.0.0.1:8080 死代理,
            // aria2 读环境变量作代理默认值(conf 空值覆盖不了), 导致 https 直链下载被拒挂起
            psi.EnvironmentVariables["HTTP_PROXY"] = "";
            psi.EnvironmentVariables["HTTPS_PROXY"] = "";
            psi.EnvironmentVariables["ALL_PROXY"] = "";
            Process p = new Process();
            p.StartInfo = psi;
            p.Start();
        }
        catch (Exception e)
        {
            MessageBox.Show("启动失败: " + exe + "\n" + e.Message,
                "PanDownloader", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    /// 杀掉占用指定端口的进程(netstat 定位监听 PID 后 taskkill /F).
    /// 本应用端口均为非熟知端口, 端口被占用基本可断定是上次残留的旧进程.
    static void KillPortOwner(int port)
    {
        // v14: 快速跳过 — 端口未被占用时无需执行 netstat/taskkill(干净启动省约 1 秒)
        if (!IsPortOpen(port, 200)) return;
        try
        {
            ProcessStartInfo psi = new ProcessStartInfo("netstat", "-ano -p tcp");
            psi.CreateNoWindow = true;
            psi.UseShellExecute = false;
            psi.RedirectStandardOutput = true;
            Process p = Process.Start(psi);
            if (p == null) return;
            string output = p.StandardOutput.ReadToEnd();
            p.WaitForExit(2000);
            foreach (string line in output.Split('\n'))
            {
                string[] parts = line.Split(new char[] { ' ' }, StringSplitOptions.RemoveEmptyEntries);
                if (parts.Length >= 5
                    && parts[0].StartsWith("TCP")
                    && parts[1].EndsWith(":" + port)
                    && parts[3] == "LISTENING")
                {
                    int pid;
                    if (int.TryParse(parts[4], out pid))
                    {
                        try
                        {
                            // /T: 杀整个进程树(浏览器子进程/后台进程可能残留持有 profile 锁)
                            ProcessStartInfo ki = new ProcessStartInfo("taskkill", "/F /T /PID " + pid);
                            ki.CreateNoWindow = true;
                            ki.UseShellExecute = false;
                            Process k = Process.Start(ki);
                            if (k != null) k.WaitForExit(3000);
                        }
                        catch (Exception) { }
                    }
                }
            }
        }
        catch (Exception) { }
    }

    static bool IsPortOpen(int port, int timeoutMs)
    {
        try
        {
            System.Net.Sockets.TcpClient client = new System.Net.Sockets.TcpClient();
            IAsyncResult ar = client.BeginConnect("127.0.0.1", port, null, null);
            if (ar.AsyncWaitHandle.WaitOne(timeoutMs))
            {
                client.EndConnect(ar);
                client.Close();
                return true;
            }
            client.Close();
            return false;
        }
        catch (Exception) { return false; }
    }

    /// v21: 异步等待端口就绪(Task.Delay 不阻塞 UI 线程, 动画持续流畅)
    static async System.Threading.Tasks.Task<bool> WaitForPortAsync(int port, int timeoutMs)
    {
        DateTime deadline = DateTime.Now.AddMilliseconds(timeoutMs);
        while (DateTime.Now < deadline)
        {
            bool open = await System.Threading.Tasks.Task.Run(() => IsPortOpen(port, 200));
            if (open) return true;
            await System.Threading.Tasks.Task.Delay(30);
        }
        return false;
    }

    /// 检查并应用更新: 对比服务器 version.json 与本地 app/version.txt,
    /// 有新版时询问用户, 确认后下载 → MD5 校验 → 备份旧 jar → 替换(须在服务启动前调用, 保证 jar 未被占用)
    static async System.Threading.Tasks.Task CheckUpdateAsync()
    {
        try
        {
            string localVer = "0.0.1";
            string verFile = Path.Combine(BaseDir, "app", "version.txt");
            if (File.Exists(verFile)) localVer = File.ReadAllText(verFile).Trim();

            // TLS1.2: .NET Framework 默认可能不启用, 会导致 HTTPS 更新源访问失败(静默无提示)
            System.Net.ServicePointManager.SecurityProtocol =
                System.Net.SecurityProtocolType.Tls | System.Net.SecurityProtocolType.Tls11 | System.Net.SecurityProtocolType.Tls12;
            SetSplashStatus("正在检查更新…");
            string json = await System.Threading.Tasks.Task.Run(() =>
            {
                HttpWebRequest req = (HttpWebRequest)WebRequest.Create(UPDATE_JSON);
                req.Timeout = 6000;
                using (HttpWebResponse resp = (HttpWebResponse)req.GetResponse())
                using (StreamReader sr = new StreamReader(resp.GetResponseStream(), Encoding.UTF8))
                {
                    return sr.ReadToEnd();
                }
            });
            string remoteVer = ExtractJson(json, "version");
            string jarUrl = ExtractJson(json, "url");
            string remoteMd5 = ExtractJson(json, "md5");
            string note = ExtractJson(json, "note");
            if (remoteVer.Length == 0 || CompareVersion(remoteVer, localVer) <= 0) return;   // 无更新

            // 弹窗前先关闭启动画面: 模态弹窗与 TopMost 启动画面纠缠(owner 消息交互)易致卡顿、鼠标转圈点不动
            CloseSplash();
            if (MessageBox.Show(
                "发现新版本 v" + remoteVer + "\n\n更新说明: " + (note.Length > 0 ? note : "—") + "\n\n是否立即下载更新?",
                "PanDownloader", MessageBoxButtons.YesNo, MessageBoxIcon.Information) != DialogResult.Yes)
            {
                ShowSplash();
                return;
            }

            // 恢复启动画面并显示下载状态
            ShowSplash();
            SetSplashStatus("正在下载更新 v" + remoteVer + "…");
            string tmpJar = Path.Combine(BaseDir, "app", "Aria2-PanFlow.jar.new");
            // 下载与校验全部后台执行, UI 线程空闲不卡顿
            await System.Threading.Tasks.Task.Run(() => DownloadFile(jarUrl, tmpJar));
            bool md5Ok = await System.Threading.Tasks.Task.Run(() =>
            {
                if (!File.Exists(tmpJar)) return false;
                return Md5File(tmpJar).ToLower() == remoteMd5.ToLower();
            });
            if (!md5Ok)
            {
                CloseSplash();
                try { File.Delete(tmpJar); } catch (Exception) { }
                MessageBox.Show("下载校验失败, 已取消更新(可稍后重试)。", "PanDownloader", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                ShowSplash();
                return;
            }

            // 备份旧 jar 并替换(后台执行, 此时服务未启动)
            CloseSplash();
            await System.Threading.Tasks.Task.Run(() =>
            {
                string jarPath = Path.Combine(BaseDir, "app", "Aria2-PanFlow.jar");
                string bakPath = jarPath + ".bak";
                if (File.Exists(bakPath)) File.Delete(bakPath);
                if (File.Exists(jarPath)) File.Move(jarPath, bakPath);
                File.Move(tmpJar, jarPath);
                try { File.WriteAllText(verFile, remoteVer); } catch (Exception) { }
            });
            LogErr("已自动更新至 v" + remoteVer);
            MessageBox.Show("已更新至 v" + remoteVer + ", 正在以新版本启动…", "PanDownloader", MessageBoxButtons.OK, MessageBoxIcon.Information);
            // 恢复启动画面, 继续正常启动流程
            ShowSplash();
        }
        catch (Exception) { }   // 更新失败(离线/网络异常)静默, 不影响正常启动
    }

    /// 从简单 JSON 中提取指定键的字符串值
    static string ExtractJson(string json, string key)
    {
        try
        {
            System.Text.RegularExpressions.Match m = System.Text.RegularExpressions.Regex.Match(json,
                "\"" + key + "\"\\s*:\\s*\"([^\"]*)\"");
            return m.Success ? m.Groups[1].Value : "";
        }
        catch (Exception) { return ""; }
    }

    /// 比较 x.y.z 版本号: 大于返回 1, 小于返回 -1, 相等返回 0
    static int CompareVersion(string a, string b)
    {
        string[] pa = a.Split('.');
        string[] pb = b.Split('.');
        for (int i = 0; i < Math.Max(pa.Length, pb.Length); i++)
        {
            int x = i < pa.Length ? 0 : 0;
            int na = 0, nb = 0;
            int.TryParse(i < pa.Length ? pa[i] : "0", out na);
            int.TryParse(i < pb.Length ? pb[i] : "0", out nb);
            if (na != nb) return na > nb ? 1 : -1;
        }
        return 0;
    }

    /// 下载文件到本地
    static void DownloadFile(string url, string dest)
    {
        HttpWebRequest req = (HttpWebRequest)WebRequest.Create(url);
        req.Timeout = 20000;
        using (HttpWebResponse resp = (HttpWebResponse)req.GetResponse())
        using (Stream src = resp.GetResponseStream())
        using (FileStream fs = new FileStream(dest, FileMode.Create, FileAccess.Write))
        {
            src.CopyTo(fs);
        }
    }

    /// 计算文件 MD5(小写)
    static string Md5File(string path)
    {
        using (System.Security.Cryptography.MD5 md5 = System.Security.Cryptography.MD5.Create())
        using (FileStream fs = File.OpenRead(path))
        {
            byte[] hash = md5.ComputeHash(fs);
            StringBuilder sb = new StringBuilder();
            foreach (byte b in hash) sb.Append(b.ToString("x2"));
            return sb.ToString();
        }
    }

    static bool WaitForPort(int port, int timeoutMs)
    {
        DateTime deadline = DateTime.Now.AddMilliseconds(timeoutMs);
        while (DateTime.Now < deadline)
        {
            if (IsPortOpen(port, 200)) return true;
            Application.DoEvents();   // v20: 30ms 高频轮询, 启动画面动画流畅不卡顿
            Thread.Sleep(30);
        }
        return false;
    }

    /// 等待指定端口释放(不再监听), 用于杀掉旧进程后确保端口可复用
    static void WaitForPortClosed(int port, int timeoutMs)
    {
        DateTime deadline = DateTime.Now.AddMilliseconds(timeoutMs);
        while (DateTime.Now < deadline)
        {
            if (!IsPortOpen(port, 300)) return;
            Thread.Sleep(300);
        }
    }
}
