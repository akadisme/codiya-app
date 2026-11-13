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
        public const string Version = "1.1.3";
        public const string CfgUrl = "https://codiya.kr/appapi/pc_ad.php"; // 서버에서 정하는 설정 (홈페이지 주소 · 광고)
        public const string VerUrl = "https://api.codiya.kr/pc/version.json"; // 자동 업데이트: 최신 버전 · 설치 파일 주소
        public static EventWaitHandle OpenSignal; // 바탕화면 아이콘을 또 누르면 켜져 있는 프로그램에 알림

        [STAThread]
        static void Main(string[] args)
        {
            bool created;
            using (var mtx = new Mutex(true, "CodiyaMemo_single", out created))
            {
                if (!created)
                {
                    // 이미 켜져 있음 → 바탕화면 아이콘을 누른 거면 켜져 있는 프로그램에게 "홈페이지 열어줘"
                    if (Array.IndexOf(args, "/tray") < 0)
                        try { EventWaitHandle.OpenExisting("CodiyaMemo_open").Set(); } catch { }
                    return;
                }
                OpenSignal = new EventWaitHandle(false, EventResetMode.AutoReset, "CodiyaMemo_open");
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

        /// <summary>브라우저로 열기: browser="chrome" 이면 크롬이 있을 때 크롬으로, 없으면 윈도우 기본 브라우저</summary>
        public static void OpenBrowser(string url, string browser)
        {
            if (!(url.StartsWith("http://") || url.StartsWith("https://"))) return;
            if (browser != "default")
            {
                var chrome = FindChrome();
                if (chrome != null) { try { Process.Start(chrome, "\"" + url + "\""); return; } catch { } }
            }
            try { Process.Start(url); } catch { }
        }

        static string FindChrome()
        {
            const string ap = @"SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\chrome.exe";
            foreach (var root in new[] { Registry.CurrentUser, Registry.LocalMachine })
            {
                try { using (var k = root.OpenSubKey(ap)) { var v = k?.GetValue(null) as string; if (!string.IsNullOrEmpty(v) && File.Exists(v.Trim('"'))) return v.Trim('"'); } } catch { }
            }
            foreach (var d in new[] { Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData) })
            {
                if (string.IsNullOrEmpty(d)) continue;
                var f = Path.Combine(d, @"Google\Chrome\Application\chrome.exe");
                if (File.Exists(f)) return f;
            }
            return null;
        }

        public static string Browser = "chrome"; // 서버 설정으로 바뀜

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
        string homeUrl = "https://codiya.kr";
        readonly System.Windows.Forms.Timer cfgTimer, updTimer;
        AdForm adWin;
        bool cfgBusy, updBusy;

        public TrayApp(bool silent)
        {
            ui.CreateControl();
            cid = new CidReader(ui) { OnNumber = n => _ = Landline(n) };
            var handler = new HttpClientHandler { CookieContainer = new CookieContainer(), UseCookies = true };
            http = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(8) };
            http.DefaultRequestHeaders.UserAgent.ParseAdd("CodiyaMemo/" + Program.Version);

            var menu = new ContextMenuStrip();
            miStatus = new ToolStripMenuItem("연결 확인 중…") { Enabled = false };
            menu.Items.Add(miStatus);
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("🏠 코디야 홈페이지", null, (s, e) => OpenHome());
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

            homeUrl = Settings.Get("home", homeUrl);
            Program.Browser = Settings.Get("browser", "chrome");
            if (Settings.Get("tok") == "") ShowLink();
            else
            {
                timer.Start();
                _ = Poll();
                if (!silent) OpenHome(); // 바탕화면 아이콘으로 켰으면 홈페이지 열기
            }

            // 바탕화면 아이콘을 또 누르면 (이미 켜져 있을 때) → 홈페이지 열기
            if (Program.OpenSignal != null)
                new Thread(() => { while (true) { try { Program.OpenSignal.WaitOne(); ui.BeginInvoke((Action)OpenHome); } catch { return; } } }) { IsBackground = true }.Start();

            // 서버 설정 (홈페이지 주소 · 광고): 시작 40초 뒤, 그다음 5분마다
            cfgTimer = new System.Windows.Forms.Timer { Interval = 40 * 1000 };
            cfgTimer.Tick += async (s, e) => { cfgTimer.Interval = 5 * 60 * 1000; await CheckCfg(); };
            cfgTimer.Start();

            // 자동 업데이트: 시작 2분 뒤, 그다음 6시간마다
            updTimer = new System.Windows.Forms.Timer { Interval = 2 * 60 * 1000 };
            updTimer.Tick += async (s, e) => { updTimer.Interval = 6 * 60 * 60 * 1000; await CheckUpdate(); };
            updTimer.Start();
        }

        bool opening;
        /// <summary>바탕화면 아이콘: 서버가 정한 주소를 (로그인된 채로 열리게 한 번 쓰는 입장권과 함께) 브라우저로 열기</summary>
        async void OpenHome()
        {
            if (Settings.Get("tok") == "") { ShowLink(); return; }
            if (opening) return;
            opening = true;
            var url = homeUrl;
            try
            {
                var body = await http.GetStringAsync(Program.CfgUrl + "?a=open&v=" + Program.Version + "&pctok=" + Uri.EscapeDataString(Settings.Get("tok")));
                var u = JStr(body, "url");
                if (u.StartsWith("http://") || u.StartsWith("https://")) url = u;
                var b = JStr(body, "browser");
                if (b != "") Program.Browser = b;
            }
            catch { }
            finally { opening = false; }
            Program.OpenBrowser(url, Program.Browser);
        }

        static string JStr(string body, string key)
        {
            var m = Regex.Match(body, "\"" + key + "\":\"((?:[^\"\\\\]|\\\\.)*)\"");
            return m.Success ? Regex.Unescape(m.Groups[1].Value) : "";
        }
        static int JInt(string body, string key, int def)
        {
            var m = Regex.Match(body, "\"" + key + "\":(\\d+)");
            return m.Success ? int.Parse(m.Groups[1].Value) : def;
        }

        /// <summary>서버에서 정한 설정 받아오기 (홈페이지 주소 · 지금 띄울 광고)</summary>
        async Task CheckCfg()
        {
            if (cfgBusy) return;
            cfgBusy = true;
            try
            {
                var body = await http.GetStringAsync(Program.CfgUrl + "?a=cfg&v=" + Program.Version + "&pctok=" + Uri.EscapeDataString(Settings.Get("tok")));
                if (!body.Contains("\"ok\":true")) return;
                var home = JStr(body, "home");
                if (home.StartsWith("http://") || home.StartsWith("https://")) { homeUrl = home; if (Settings.Get("home") != home) Settings.Set("home", home); }
                var br = JStr(body, "browser");
                if (br != "") { Program.Browser = br; if (Settings.Get("browser") != br) Settings.Set("browser", br); }
                var ad = JStr(body, "ad_url");
                if (ad.StartsWith("https://codiya.kr/") && CanShowAd())
                {
                    if (adWin != null && !adWin.IsDisposed) adWin.Bye();
                    adWin = new AdForm(ad, JInt(body, "ad_w", 320), JInt(body, "ad_h", 470), JInt(body, "ad_sec", 12));
                    adWin.Show();
                }
            }
            catch { }
            finally { cfgBusy = false; }
        }

        /// <summary>광고를 띄워도 되는 때인지 (전화 메모 창이 떠 있거나 방금 전화가 왔으면 안 띄움)</summary>
        bool CanShowAd()
        {
            if (Settings.Get("tok") == "") return false;
            if ((DateTime.Now - shownAt).TotalMinutes < 3) return false;
            if (popup != null && !popup.IsDisposed && popup.Visible && popup.WindowState != FormWindowState.Minimized) return false;
            if (adWin != null && !adWin.IsDisposed && adWin.Visible) return false;
            return true;
        }

        /// <summary>자동 업데이트: 서버에 더 새 버전이 있으면 받아서 조용히 설치 (설치가 끝나면 다시 켜져요)</summary>
        async Task CheckUpdate()
        {
            if (updBusy) return;
            updBusy = true;
            try
            {
                var body = await http.GetStringAsync(Program.VerUrl + "?t=" + DateTime.Now.Ticks);
                var ver = JStr(body, "version");
                var url = JStr(body, "url");
                if (!Version.TryParse(ver, out var nv) || nv <= Version.Parse(Program.Version)) return;
                if (!(url.StartsWith("https://api.codiya.kr/") || url.StartsWith("https://codiya.kr/"))) return;
                if ((DateTime.Now - shownAt).TotalMinutes < 5) return; // 전화 중일 수 있으면 다음에
                if (Settings.Get("upd_try") == ver + "|" + DateTime.Now.ToString("yyyyMMdd")) return; // 오늘 이미 시도한 버전이면 내일 다시
                Settings.Set("upd_try", ver + "|" + DateTime.Now.ToString("yyyyMMdd"));

                var file = Path.Combine(Path.GetTempPath(), "CodiyaMemoSetup_" + ver + ".exe");
                using (var dl = new HttpClient { Timeout = TimeSpan.FromMinutes(5) })
                {
                    var bytes = await dl.GetByteArrayAsync(url);
                    if (bytes.Length < 300 * 1024 || bytes[0] != (byte)'M' || bytes[1] != (byte)'Z') return; // 설치 파일이 아니면 그만
                    File.WriteAllBytes(file, bytes);
                }
                Process.Start(new ProcessStartInfo(file, "/VERYSILENT /SUPPRESSMSGBOXES /NORESTART /SP-") { UseShellExecute = true });
                Quit(); // 설치 프로그램이 새 버전을 깔고 다시 켜 줘요
            }
            catch { }
            finally { updBusy = false; }
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
                if (body.Contains("\"pcad\":1")) _ = CheckCfg(); // 관리자가 [시험 보내기] → 바로 광고 확인
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
            if (Settings.Get("relogin") == "1") linkUrl = "https://codiya.kr/bbs/logout.php?url=" + Uri.EscapeDataString(new Uri(linkUrl).PathAndQuery); // 돌아올 주소는 도메인 없이 // 다른 아이디로
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
            try { cfgTimer?.Stop(); updTimer?.Stop(); if (adWin != null && !adWin.IsDisposed) adWin.Bye(); } catch { }
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

    /// <summary>화면 오른쪽 아래에 올라오는 작은 광고 창 (내용은 서버 화면 · 포커스를 빼앗지 않음)
    ///  · 정한 시간(초) 동안은 다른 곳을 눌러도 맨 앞에 그대로 · 그 안에는 ×/닫기/오늘 하루 안 보기/광고 클릭으로만 닫힘</summary>
    class AdForm : Form
    {
        [DllImport("user32.dll")] static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
        static readonly IntPtr HWND_TOPMOST = new IntPtr(-1);
        const uint SWP_NOSIZE = 0x1, SWP_NOMOVE = 0x2, SWP_NOACTIVATE = 0x10, SWP_SHOWWINDOW = 0x40;

        readonly WebView2 web = new WebView2 { Dock = DockStyle.Fill };
        readonly string url;
        readonly System.Windows.Forms.Timer life = new System.Windows.Forms.Timer(), slide = new System.Windows.Forms.Timer { Interval = 15 }, keep = new System.Windows.Forms.Timer { Interval = 700 };
        int targetTop;
        bool canClose;

        public AdForm(string url, int w, int h, int sec)
        {
            this.url = url;
            FormBorderStyle = FormBorderStyle.None;
            ShowInTaskbar = false;
            TopMost = true;
            StartPosition = FormStartPosition.Manual;
            Icon = Program.AppIcon();
            var wa = Screen.PrimaryScreen.WorkingArea;
            Size = new Size(Math.Max(160, Math.Min(w, 600)), Math.Max(120, Math.Min(h, 760)));
            targetTop = wa.Bottom - Height - 12;
            Location = new Point(wa.Right - Width - 12, wa.Bottom); // 화면 아래에서 시작해서 스르륵 올라옴
            Controls.Add(web);
            life.Interval = Math.Max(5, Math.Min(sec, 300)) * 1000;
            life.Tick += (s, e) =>
            {
                if (Bounds.Contains(Cursor.Position)) return; // 마우스를 올려 두면 안 닫힘
                life.Stop(); Bye();
            };
            slide.Tick += (s, e) => { if (Top > targetTop) Top = Math.Max(targetTop, Top - 18); else slide.Stop(); };
            keep.Tick += (s, e) => Front(); // 다른 창을 눌러도 정한 시간 동안은 맨 앞에
            Load += async (s, e) => await Init();
        }

        void Front()
        {
            try
            {
                if (IsDisposed) return;
                if (!Visible) Show();
                if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal;
                SetWindowPos(Handle, HWND_TOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE | SWP_SHOWWINDOW);
            }
            catch { }
        }

        /// <summary>닫기는 여기로만 (광고 창 버튼 · 시간 끝 · 프로그램 종료)</summary>
        public void Bye()
        {
            canClose = true;
            try { Close(); } catch { }
        }

        protected override bool ShowWithoutActivation => true; // 일하던 창의 입력을 빼앗지 않음
        protected override CreateParams CreateParams
        {
            get { var cp = base.CreateParams; cp.ExStyle |= 0x08000000 | 0x00000080 | 0x00000008; return cp; } // NOACTIVATE · TOOLWINDOW · TOPMOST
        }

        async Task Init()
        {
            try
            {
                await web.EnsureCoreWebView2Async(await Program.Env());
                web.CoreWebView2.Settings.AreDevToolsEnabled = false;
                web.CoreWebView2.Settings.IsZoomControlEnabled = false;
                web.CoreWebView2.Settings.AreDefaultContextMenusEnabled = false;
                web.CoreWebView2.NewWindowRequested += (s, e) =>
                {
                    e.Handled = true;
                    Program.OpenBrowser(e.Uri, Program.Browser);
                    BeginInvoke((Action)Bye);
                };
                web.CoreWebView2.WebMessageReceived += (s, e) => { try { if (e.TryGetWebMessageAsString() == "close") BeginInvoke((Action)Bye); } catch { } };
                web.CoreWebView2.NavigationCompleted += (s, e) => { if (!e.IsSuccess) BeginInvoke((Action)Bye); };
                web.CoreWebView2.Navigate(url);
                slide.Start();
                life.Start();
                keep.Start();
            }
            catch { Bye(); }
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            // 정한 시간 안에 윈도우가 닫으려 하면 (다른 곳 클릭 · 바탕화면 보기 등) 닫지 않음
            if (!canClose && e.CloseReason != CloseReason.WindowsShutDown && e.CloseReason != CloseReason.TaskManagerClosing && e.CloseReason != CloseReason.ApplicationExitCall)
            { e.Cancel = true; Front(); return; }
            base.OnFormClosing(e);
        }

        protected override void OnFormClosed(FormClosedEventArgs e)
        {
            life.Stop(); slide.Stop(); keep.Stop();
            try { web.Dispose(); } catch { }
            base.OnFormClosed(e);
        }
    }
}
