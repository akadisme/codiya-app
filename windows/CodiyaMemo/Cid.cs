// ☎ 유선전화 CID 장치 (발신번호 표시 장치 · USB/시리얼 COM 포트)
// 장치가 보내는 글자에서 전화번호를 찾아 → 고객 메모 창
//  · 장치마다 보내는 모양이 달라서, 글자 속 번호 모양(0으로 시작 9~11자리)을 찾는 방식
//  · 모뎀형(음성 모뎀)은 'AT+VCID=1' 을 보내면 "NMBR = 0212345678" 처럼 알려줌
using System;
using System.Drawing;
using System.IO.Ports;
using System.Text;
using System.Text.RegularExpressions;
using System.Windows.Forms;

namespace CodiyaMemo
{
    class CidReader : IDisposable
    {
        SerialPort port;
        readonly StringBuilder buf = new StringBuilder();
        readonly Timer retry;
        readonly Control ui;
        string lastNum = "";
        DateTime lastAt = DateTime.MinValue;

        public Action<string> OnNumber;   // 찾은 번호 (UI 스레드)
        public Action<string> OnRaw;      // 받은 글자 그대로 (설정 창 보기용)
        public string Status { get; private set; } = "꺼짐";

        public CidReader(Control uiOwner)
        {
            ui = uiOwner;
            retry = new Timer { Interval = 10000 };
            retry.Tick += (s, e) => { if (port == null || !port.IsOpen) Open(); };
        }

        public static bool Configured => Settings.Get("cid_port") != "";

        public void Start() { if (Configured) { Open(); retry.Start(); } }

        public void Stop()
        {
            retry.Stop();
            Close();
            Status = "꺼짐";
        }

        void Close()
        {
            try { if (port != null) { port.DataReceived -= Got; if (port.IsOpen) port.Close(); port.Dispose(); } } catch { }
            port = null;
        }

        public void Open()
        {
            Close();
            var name = Settings.Get("cid_port");
            if (name == "") { Status = "꺼짐"; return; }
            try
            {
                int baud; if (!int.TryParse(Settings.Get("cid_baud", "9600"), out baud)) baud = 9600;
                port = new SerialPort(name, baud, Parity.None, 8, StopBits.One)
                {
                    Encoding = Encoding.GetEncoding(949), // 한글 섞여 와도 깨지지 않게
                    DtrEnable = true, RtsEnable = true, NewLine = "\r"
                };
                port.DataReceived += Got;
                port.Open();
                if (Settings.Get("cid_modem") == "1")
                {
                    port.Write("ATZ\r"); System.Threading.Thread.Sleep(400);
                    port.Write("AT+VCID=1\r"); System.Threading.Thread.Sleep(200);
                    port.Write("AT#CID=1\r");
                }
                Status = "🟢 " + name + " 연결됨";
            }
            catch (Exception e)
            {
                Status = "⚠️ " + name + " 연결 안 됨 (" + e.GetType().Name + ")";
                Close();
            }
        }

        void Got(object sender, SerialDataReceivedEventArgs e)
        {
            string s;
            try { s = port.ReadExisting(); } catch { return; }
            if (string.IsNullOrEmpty(s)) return;
            string found = null;
            lock (buf)
            {
                buf.Append(s);
                if (buf.Length > 600) buf.Remove(0, buf.Length - 300);
                found = Find(buf.ToString());
                if (found != null) buf.Clear();
            }
            try { ui.BeginInvoke((Action)(() => { OnRaw?.Invoke(s); if (found != null) Hit(found); })); } catch { }
        }

        void Hit(string num)
        {
            if (num == lastNum && (DateTime.Now - lastAt).TotalSeconds < 25) return; // 같은 전화 신호가 여러 번 와도 한 번만
            lastNum = num; lastAt = DateTime.Now;
            OnNumber?.Invoke(num);
        }

        /// <summary>글자 속 전화번호 찾기: 010-1234-5678 / 0212345678 / NMBR=031... / 1588xxxx 는 제외(대표번호는 걸려오는 번호가 아님)</summary>
        public static string Find(string text)
        {
            var t = Regex.Replace(text, "(?<=\\d)[ \\-.)](?=\\d)", ""); // 숫자 사이 - . 공백 지우기
            foreach (Match m in Regex.Matches(t, "(?<!\\d)0\\d{8,10}(?!\\d)"))
            {
                var d = m.Value;
                if (d.StartsWith("00")) continue;
                return d;
            }
            return null;
        }

        public void Dispose() { Stop(); retry.Dispose(); }
    }

    /// <summary>CID 장치 설정 창</summary>
    class CidForm : Form
    {
        readonly ComboBox cbPort = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Width = 160 };
        readonly ComboBox cbBaud = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Width = 110 };
        readonly CheckBox chModem = new CheckBox { Text = "모뎀형 장치 (AT+VCID=1 보내기)", AutoSize = true };
        readonly Label lbStatus = new Label { AutoSize = true, ForeColor = Color.FromArgb(0x75, 0x71, 0x6A) };
        readonly TextBox tbRaw = new TextBox { Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical, Font = new Font("Consolas", 9.5f), BackColor = Color.FromArgb(0xFB, 0xFA, 0xF7) };
        readonly CidReader cid;

        public CidForm(CidReader reader)
        {
            cid = reader;
            Text = "☎ 유선전화 CID 장치 설정 · " + Program.AppName;
            Icon = Program.AppIcon();
            Font = new Font("맑은 고딕", 10f);
            Size = new Size(620, 600);
            StartPosition = FormStartPosition.CenterScreen;
            BackColor = Color.White;

            var p = new FlowLayoutPanel { Dock = DockStyle.Top, FlowDirection = FlowDirection.TopDown, WrapContents = false, AutoSize = true, Padding = new Padding(16, 14, 16, 6) };
            p.Controls.Add(new Label
            {
                AutoSize = true, MaximumSize = new Size(570, 0),
                Text = "사무실 유선전화(휴대폰으로 착신하지 않는 전화)에 '발신번호 표시 장치(CID)'를 연결하면, " +
                       "휴대폰 없이도 전화가 올 때 PC에 고객 메모 창이 떠요.\n\n" +
                       "① 장치를 전화선 중간에 꽂고 USB를 PC에 연결\n② 아래에서 장치의 COM 포트를 고르고 [저장·연결]\n③ 그 전화로 전화를 걸어보세요. 아래 '받은 신호'에 글자가 오고 메모 창이 뜨면 성공!\n" +
                       "※ 글자는 오는데 창이 안 뜨면 아래 내용을 복사해서 코디야 고객센터에 보내주세요. 그 장치에 맞춰 드려요."
            });
            var row = new FlowLayoutPanel { AutoSize = true, Margin = new Padding(0, 10, 0, 0) };
            row.Controls.Add(new Label { Text = "COM 포트", AutoSize = true, Margin = new Padding(0, 6, 6, 0) });
            row.Controls.Add(cbPort);
            row.Controls.Add(new Label { Text = "속도", AutoSize = true, Margin = new Padding(12, 6, 6, 0) });
            row.Controls.Add(cbBaud);
            var bRefresh = new Button { Text = "새로고침", AutoSize = true };
            bRefresh.Click += (s, e) => LoadPorts();
            row.Controls.Add(bRefresh);
            p.Controls.Add(row);
            p.Controls.Add(chModem);
            var row2 = new FlowLayoutPanel { AutoSize = true, Margin = new Padding(0, 8, 0, 0) };
            var bSave = new Button { Text = "💾 저장·연결", AutoSize = true, BackColor = Color.FromArgb(0xC3, 0x92, 0x36), ForeColor = Color.White, FlatStyle = FlatStyle.Flat };
            bSave.Click += (s, e) => Save();
            var bOff = new Button { Text = "사용 안함", AutoSize = true };
            bOff.Click += (s, e) => { Settings.Set("cid_port", ""); cid.Stop(); Refresh2(); };
            var bTest = new Button { Text = "🧪 번호 찾기 시험", AutoSize = true };
            bTest.Click += (s, e) => { var n = CidReader.Find(tbRaw.Text); MessageBox.Show(n != null ? "찾은 번호: " + n : "받은 글자에서 전화번호를 못 찾았어요.", Program.AppName); };
            row2.Controls.Add(bSave); row2.Controls.Add(bOff); row2.Controls.Add(bTest);
            p.Controls.Add(row2);
            p.Controls.Add(lbStatus);
            p.Controls.Add(new Label { Text = "받은 신호 (장치가 보낸 글자 그대로)", AutoSize = true, Margin = new Padding(0, 10, 0, 2) });

            var holder = new Panel { Dock = DockStyle.Fill, Padding = new Padding(16, 0, 16, 16) };
            tbRaw.Dock = DockStyle.Fill;
            holder.Controls.Add(tbRaw);
            Controls.Add(holder);
            Controls.Add(p);

            cbBaud.Items.AddRange(new object[] { "1200", "2400", "4800", "9600", "19200", "38400", "57600", "115200" });
            cbBaud.SelectedItem = Settings.Get("cid_baud", "9600");
            if (cbBaud.SelectedIndex < 0) cbBaud.SelectedItem = "9600";
            chModem.Checked = Settings.Get("cid_modem") == "1";
            LoadPorts();
            Refresh2();
            cid.OnRaw = s => { if (!IsDisposed) { tbRaw.AppendText(s.Replace("\r\n", "\n").Replace("\r", "\n").Replace("\n", "\r\n")); } };
            FormClosed += (s, e) => cid.OnRaw = null;
        }

        void LoadPorts()
        {
            cbPort.Items.Clear();
            foreach (var n in SerialPort.GetPortNames()) cbPort.Items.Add(n);
            var cur = Settings.Get("cid_port");
            if (cur != "" && !cbPort.Items.Contains(cur)) cbPort.Items.Add(cur);
            if (cur != "") cbPort.SelectedItem = cur; else if (cbPort.Items.Count > 0) cbPort.SelectedIndex = cbPort.Items.Count - 1;
            if (cbPort.Items.Count == 0) lbStatus.Text = "⚠️ COM 포트가 없어요. 장치 USB를 꽂고 [새로고침]을 눌러주세요. (장치 드라이버 설치가 필요할 수 있어요)";
        }

        void Save()
        {
            if (cbPort.SelectedItem == null) { MessageBox.Show("COM 포트를 골라주세요.", Program.AppName); return; }
            Settings.Set("cid_port", (string)cbPort.SelectedItem);
            Settings.Set("cid_baud", (string)cbBaud.SelectedItem ?? "9600");
            Settings.Set("cid_modem", chModem.Checked ? "1" : "0");
            cid.Stop(); cid.Start();
            Refresh2();
        }

        void Refresh2() { lbStatus.Text = "상태: " + cid.Status; }
    }
}
