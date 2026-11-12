// 코디야 전화 메모 (PC 프로그램)
// 휴대폰에 전화가 울리면 → 코디야 서버 → 이 프로그램이 2.5초마다 확인 → 화면 맨 앞에 고객 메모 창
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Runtime.InteropServices;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;
using Microsoft.Win32;

namespace CodiyaMemo
{
    static class Program
    {
        public const string Site = "https://codiya.kr/appapi/memo_pc.php";
        public const string AppName = "코디야 전화 메모";

        [STAThread]
        static void Main(string[] args)
        {
            bool created;
            using (var mtx = new Mutex(true, "CodiyaMemo_single", out created))
            {
                if (!created) return; // 이미 켜져 있음
                ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12 | (SecurityProtocolType)12288; // TLS 1.2 / 1.3
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                Application.Run(new TrayApp(Array.IndexOf(args, "/tray") >= 0));
            }
        }

        public static string DataDir
        {
            get
            {
                var d = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "CodiyaMemo");
                Directory.CreateDirectory(d);
                return d;
            }
        }

        static CoreWebView2Environment _env;
        public static async Task<CoreWebView2Environment> Env()
        {
            if (_env == null) _env = await CoreWebView2Environment.CreateAsync(null, Path.Combine(DataDir, "web"));
            return _env;
        }

        public static Icon AppIcon()
        {
            try
            {
                using (var s = typeof(Program).Assembly.GetManifestResourceStream("CodiyaMemo.app.ico"))
                    if (s != null) return new Icon(s);
            }
            catch { }
            return SystemIcons.Application;
        }
    }

    /// <summary>설정 (연결 키·소리) — 내 PC에만 저장</summary>
    static class Settings
    {
        static string FilePath => Path.Combine(Program.DataDir, "settings.txt");
        static Dictionary<string, string> _d;
        static Dictionary<string, string> D
        {
            get
            {
                if (_d != null) return _d;
                _d = new Dictionary<string, string>();
                try
                {
                    foreach (var line in File.ReadAllLines(FilePath))
                    {
                        int i = line.IndexOf('=');
                        if (i > 0) _d[line.Substring(0, i)] = line.Substring(i + 1);
                    }
                }
                catch { }
                return _d;
            }
        }
        public static string Get(string k, string def = "") => D.TryGetValue(k, out var v) ? v : def;
        public static void Set(string k, string v)
        {
            D[k] = v ?? "";
            try
            {
                var lines = new List<string>();
                foreach (var kv in D) lines.Add(kv.Key + "=" + kv.Value);
                File.WriteAllLines(FilePath, lines);
            }
            catch { }
        }
    }

    static class AutoStart
    {
        const string Key = @"Software\Microsoft\Windows\CurrentVersion\Run";
        const string Name = "CodiyaMemo";
        public static bool On
        {
            get { using (var k = Registry.CurrentUser.OpenSubKey(Key)) return k?.GetValue(Name) != null; }
            set
            {
                using (var k = Registry.CurrentUser.CreateSubKey(Key))
                {
                    if (value) k.SetValue(Name, "\"" + Application.ExecutablePath + "\" /tray");
                    else k.DeleteValue(Name, false);
                }
            }
        }
    }

    class TrayApp : ApplicationContext
    {
        readonly NotifyIcon tray;
        readonly System.Windows.Forms.Timer timer;
        readonly HttpClient http;
        readonly ToolStripMenuItem miStatus, miSound, miAuto;
        long last = 0;
        bool busy = false;
        int fails = 0;
        PopupForm popup;
        CidForm cidWin;
        WebForm memoWin, linkWin;
        readonly Control ui = new Control();
        readonly CidReader cid;
        string shownHash = "";
        DateTime shownAt = DateTime.MinValue;

        public TrayApp(bool silent)
        {
            ui.CreateControl();
            cid = new CidReader(ui) { OnNumber = n => _ = Landline(n) };
            var handler = new HttpClientHandler { CookieContainer = new CookieContainer(), UseCookies = true };
            http = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(8) };
            http.DefaultRequestHeaders.UserAgent.ParseAdd("CodiyaMemo/1.0");

            var menu = new ContextMenuStrip();
            miStatus = new ToolStripMenuItem("연결 확인 중…") { Enabled = false };
            menu.Items.Add(miStatus);
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("📒 메모창 열기", null, (s, e) => OpenMemo());
            miSound = new ToolStripMenuItem("🔊 전화 오면 소리", null, (s, e) => { Settings.Set("sound", miSound.Checked ? "0" : "1"); miSound.Checked = Settings.Get("sound", "1") == "1"; });
            miSound.Checked = Settings.Get("sound", "1") == "1";
            menu.Items.Add(miSound);
            miAuto = new ToolStripMenuItem("PC 켜면 자동 실행", null, (s, e) => { try { AutoStart.On = !AutoStart.On; } catch { } miAuto.Checked = AutoStart.On; });
            menu.Items.Add(miAuto);
            menu.Items.Add("🧪 창 띄우기 시험", null, (s, e) => ShowTest());
            menu.Items.Add("☎ 유선전화 CID 장치 설정", null, (s, e) => { if (cidWin == null || cidWin.IsDisposed) cidWin = new CidForm(cid); cidWin.Show(); cidWin.Activate(); });
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("연결 해제 (다른 아이디로)", null, (s, e) => Unlink());
            menu.Items.Add("종료", null, (s, e) => Quit());

            tray = new NotifyIcon { Icon = Program.AppIcon(), Text = Program.AppName, ContextMenuStrip = menu, Visible = true };
            tray.MouseClick += (s, e) => { if (e.Button == MouseButtons.Left) OpenMemo(); };

            // 처음 실행이면 PC 켤 때 자동 실행 켜기
            if (Settings.Get("auto_init") != "1") { try { AutoStart.On = true; } catch { } Settings.Set("auto_init", "1"); }
            miAuto.Checked = SafeAuto();

            cid.Start(); // 유선전화 CID 장치 (설정해 둔 경우만)
            timer = new System.Windows.Forms.Timer { Interval = 2500 };
            timer.Tick += async (s, e) => await Poll();

            if (Settings.Get("tok") == "") ShowLink();
            else
            {
                timer.Start();
                _ = Poll();
                if (!silent) tray.ShowBalloonTip(4000, Program.AppName, "켜져 있어요. 휴대폰에 전화가 오면 고객 메모 창이 화면 맨 앞에 떠요.", ToolTipIcon.Info);
            }
        }

        static bool SafeAuto() { try { return AutoStart.On; } catch { return false; } }

        string Url(string q) => Program.Site + "?" + q + "&pctok=" + Uri.EscapeDataString(Settings.Get("tok"));

        async Task Poll()
        {
            if (busy || Settings.Get("tok") == "") return;
            busy = true;
            try
            {
                var body = await http.GetStringAsync(Url("a=ring&after=" + last));
                if (body.Contains("\"login\":true")) { SetStatus("연결이 끊겼어요 · 다시 로그인해주세요"); Settings.Set("tok", ""); timer.Stop(); ShowLink(); return; }
                if (body.Contains("\"off\":true")) { SetStatus("⚠️ 이용권이 없거나 전화 메모가 꺼져 있어요"); return; }
                if (!body.Contains("\"ok\":true")) { SetStatus("서버 확인 중…"); return; }
                fails = 0;
                SetStatus("🟢 연결됨 · 전화 오면 메모 창이 떠요");
                bool first = last == 0;
                var m = Regex.Match(body, "\"last\":(\\d+)");
                if (m.Success) last = Math.Max(last, long.Parse(m.Groups[1].Value));
                if (first) return;
                Match it = null;
                foreach (Match x in Regex.Matches(body, "\\{\"id\":(\\d+),\"hash\":\"([a-f0-9]{40})\",\"phone\":\"([0-9*#]*)\",\"at\":\"([0-9:]*)\"\\}")) it = x;
                if (it != null) ShowRing(it.Groups[2].Value, it.Groups[3].Value);
            }
            catch
            {
                if (++fails >= 4) SetStatus("인터넷 연결 확인 중…");
            }
            finally { busy = false; }
        }

        void SetStatus(string s)
        {
            miStatus.Text = s;
            var t = Program.AppName + "\n" + s;
            tray.Text = t.Length > 63 ? t.Substring(0, 63) : t;
        }

        /// <summary>☎ 유선전화(CID 장치)로 온 전화: 바로 창 띄우고, 서버에도 알려서 목록·메모에 남김</summary>
        async Task Landline(string num)
        {
            if (Settings.Get("tok") == "") return;
            ShowRing(MemoKey(num), num);
            try
            {
                var res = await http.PostAsync(Program.Site + "?a=ring_add&pctok=" + Uri.EscapeDataString(Settings.Get("tok")),
                    new FormUrlEncodedContent(new Dictionary<string, string> { { "tok", Settings.Get("tok") }, { "phone", num } }));
                var body = await res.Content.ReadAsStringAsync();
                var m = Regex.Match(body, "\"id\":(\\d+)");
                if (m.Success) last = Math.Max(last, long.Parse(m.Groups[1].Value));
            }
            catch { }
        }

        /// <summary>고객 메모 키 (서버·휴대폰 앱과 같은 방식)</summary>
        static string MemoKey(string num)
        {
            var d = Regex.Replace(Regex.Replace(num, "^[*#]\\d{3}", ""), "\\D", "");
            using (var sha = System.Security.Cryptography.SHA1.Create())
            {
                var b = sha.ComputeHash(System.Text.Encoding.UTF8.GetBytes("grmemo|" + d));
                var sb = new System.Text.StringBuilder();
                foreach (var x in b) sb.Append(x.ToString("x2"));
                return sb.ToString();
            }
        }

        void ShowRing(string hash, string phone)
        {
            if (hash == shownHash && (DateTime.Now - shownAt).TotalSeconds < 45) return; // 같은 전화 신호가 여러 번 와도 한 번만 (닫은 뒤 다시 뜨지 않게)
            shownHash = hash; shownAt = DateTime.Now;
            if (popup == null || popup.IsDisposed) popup = new PopupForm();
            popup.ShowCustomer(Url("pop=1&h=" + hash + "&p=" + Uri.EscapeDataString(phone)));
            if (Settings.Get("sound", "1") == "1") PlayRing();
        }

        void ShowTest()
        {
            if (Settings.Get("tok") == "") { ShowLink(); return; }
            ShowRing(new string('0', 40), "");
        }

        static void PlayRing()
        {
            try
            {
                var f = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "ring.wav");
                if (File.Exists(f)) new System.Media.SoundPlayer(f).Play();
                else System.Media.SystemSounds.Exclamation.Play();
            }
            catch { }
        }

        void OpenMemo()
        {
            if (Settings.Get("tok") == "") { ShowLink(); return; }
            if (memoWin == null || memoWin.IsDisposed)
                memoWin = new WebForm("📒 고객 메모 · " + Program.AppName, Url("inapp=1"), 1100, 760);
            memoWin.ShowFront();
        }

        void ShowLink()
        {
            SetStatus("로그인이 필요해요");
            if (linkWin != null && !linkWin.IsDisposed) { linkWin.ShowFront(); return; }
            var linkUrl = Program.Site + "?pc=link";
            if (Settings.Get("relogin") == "1") linkUrl = "https://codiya.kr/bbs/logout.php?url=" + Uri.EscapeDataString(linkUrl); // 다른 아이디로
            linkWin = new WebForm("코디야 로그인 · " + Program.AppName, linkUrl, 520, 720);
            linkWin.OnNavigate = url =>
            {
                var m = Regex.Match(url, "[?&]pc=done&t=([a-f0-9]{40})");
                if (!m.Success) return false;
                Settings.Set("tok", m.Groups[1].Value);
                Settings.Set("relogin", "0");
                last = 0;
                timer.Start();
                _ = Poll();
                linkWin.BeginInvoke((Action)(() => linkWin.Close()));
                tray.ShowBalloonTip(5000, "✅ 연결됐어요", "이제 휴대폰에 전화가 오면 화면 맨 앞에 고객 메모 창이 떠요. (화면 오른쪽 아래 아이콘으로 켜져 있어요)", ToolTipIcon.Info);
                return true;
            };
            linkWin.ShowFront();
        }

        async void Unlink()
        {
            if (MessageBox.Show("이 PC의 연결을 해제할까요?\n(다른 아이디로 다시 로그인할 수 있어요)", Program.AppName, MessageBoxButtons.YesNo) != DialogResult.Yes) return;
            try { await http.GetStringAsync(Url("a=unlink")); } catch { }
            Settings.Set("tok", "");
            timer.Stop();
            last = 0;
            Settings.Set("relogin", "1");
            ShowLink();
        }

        void Quit()
        {
            tray.Visible = false;
            timer.Stop();
            cid.Dispose();
            ExitThread();
        }
    }

    /// <summary>웹 화면 창 (로그인 · 메모창)</summary>
    class WebForm : Form
    {
        protected readonly WebView2 web = new WebView2 { Dock = DockStyle.Fill };
        readonly string startUrl;
        public Func<string, bool> OnNavigate;

        public WebForm(string title, string url, int w, int h)
        {
            Text = title;
            Icon = Program.AppIcon();
            startUrl = url;
            var wa = Screen.PrimaryScreen.WorkingArea;
            Size = new Size(Math.Min(w, wa.Width - 40), Math.Min(h, wa.Height - 40));
            StartPosition = FormStartPosition.CenterScreen;
            Controls.Add(web);
            Load += async (s, e) => await Init();
        }

        protected virtual async Task Init()
        {
            try
            {
                await web.EnsureCoreWebView2Async(await Program.Env());
                web.CoreWebView2.Settings.AreDevToolsEnabled = false;
                web.CoreWebView2.Settings.IsZoomControlEnabled = true;
                web.CoreWebView2.NavigationStarting += (s, e) => { if (OnNavigate != null && OnNavigate(e.Uri)) e.Cancel = true; };
                web.CoreWebView2.NewWindowRequested += (s, e) => { e.Handled = true; try { Process.Start(e.Uri); } catch { } };
                web.CoreWebView2.WindowCloseRequested += (s, e) => Hide();
                web.CoreWebView2.WebMessageReceived += (s, e) => { try { if (e.TryGetWebMessageAsString() == "close") Hide(); } catch { } };
                if (startUrl != null) web.CoreWebView2.Navigate(startUrl);
            }
            catch (Exception)
            {
                if (MessageBox.Show("화면을 보여주는 윈도우 구성요소(WebView2)가 없어요.\n[예]를 누르면 마이크로소프트 설치 페이지가 열려요. 설치 후 다시 실행해주세요.",
                    Program.AppName, MessageBoxButtons.YesNo, MessageBoxIcon.Warning) == DialogResult.Yes)
                    try { Process.Start("https://go.microsoft.com/fwlink/p/?LinkId=2124703"); } catch { }
                Close();
            }
        }

        public void ShowFront()
        {
            if (!Visible) Show();
            if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal;
            Activate();
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            // 메모창은 닫아도 다시 빨리 열리게 숨기기만 (로그인 창은 진짜 닫기)
            if (e.CloseReason == CloseReason.UserClosing && OnNavigate == null) { e.Cancel = true; Hide(); return; }
            base.OnFormClosing(e);
        }
    }

    /// <summary>전화 오면 화면 맨 앞(오른쪽 위)에 뜨는 고객 메모 창</summary>
    class PopupForm : WebForm
    {
        [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr h);
        [DllImport("user32.dll")] static extern bool ShowWindow(IntPtr h, int cmd);
        [DllImport("user32.dll")] static extern bool FlashWindowEx(ref FLASHWINFO fi);
        [StructLayout(LayoutKind.Sequential)]
        struct FLASHWINFO { public uint cbSize; public IntPtr hwnd; public uint dwFlags; public uint uCount; public uint dwTimeout; }

        bool ready;
        string pending;

        public PopupForm() : base("📞 전화 왔어요 · " + Program.AppName, null, 520, 760)
        {
            TopMost = true; // 다른 프로그램을 쓰고 있어도 항상 맨 앞
            ShowInTaskbar = true;
            StartPosition = FormStartPosition.CenterScreen; // 노트북·모니터 어디서든 화면 가운데
        }

        protected override async Task Init()
        {
            await base.Init();
            ready = web.CoreWebView2 != null;
            if (ready && pending != null) { web.CoreWebView2.Navigate(pending); pending = null; }
        }

        public void ShowCustomer(string url)
        {
            if (ready) web.CoreWebView2.Navigate(url); else pending = url;
            if (!Visible) Show();
            if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal;
            // 마우스가 있는 화면의 가운데로 (모니터 여러 개여도)
            var wa = Screen.FromPoint(Cursor.Position).WorkingArea;
            Location = new Point(wa.Left + (wa.Width - Width) / 2, wa.Top + (wa.Height - Height) / 2);
            TopMost = false; TopMost = true; // 맨 앞으로 다시
            ShowWindow(Handle, 5);
            SetForegroundWindow(Handle);
            Activate();
            var fi = new FLASHWINFO { cbSize = (uint)Marshal.SizeOf(typeof(FLASHWINFO)), hwnd = Handle, dwFlags = 3 | 12, uCount = 3, dwTimeout = 0 };
            FlashWindowEx(ref fi);
        }
    }
}
