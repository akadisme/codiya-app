; 코디야 전화 메모 — 설치 파일 (관리자 권한 없이 내 계정에만 설치)
#define AppVer "1.1.3"
[Setup]
AppId={{C0D1A7A0-3F2E-4B9C-8D61-7E5A2C9B4F10}
AppName=코디야 전화 메모
AppVersion={#AppVer}
AppPublisher=코디야
AppPublisherURL=https://codiya.kr
DefaultDirName={localappdata}\CodiyaMemo
DisableDirPage=yes
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=out
OutputBaseFilename=CodiyaMemoSetup
SetupIconFile=CodiyaMemo\app.ico
UninstallDisplayIcon={app}\CodiyaMemo.exe
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
CloseApplications=no
LicenseFile=license.txt
DisableReadyPage=yes

[Languages]
Name: "korean"; MessagesFile: "compiler:Languages\Korean.isl"

[Files]
Source: "publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{userprograms}\코디야 전화 메모"; Filename: "{app}\CodiyaMemo.exe"
; 바탕화면 아이콘: 직접 설치할 때 항상 만들기 (자동 업데이트 때는 다시 만들지 않음 · 사용자가 지운 아이콘을 되살리지 않게)
Name: "{userdesktop}\코디야 전화 메모"; Filename: "{app}\CodiyaMemo.exe"; Check: not WizardSilent

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "CodiyaMemo"; ValueData: """{app}\CodiyaMemo.exe"" /tray"; Flags: uninsdeletevalue

[Run]
Filename: "{app}\CodiyaMemo.exe"; Description: "지금 실행 (코디야 아이디로 로그인)"; Flags: nowait postinstall skipifsilent
; 자동 업데이트(조용히 설치)일 때는 화면 없이 다시 켜기
Filename: "{app}\CodiyaMemo.exe"; Parameters: "/tray"; Flags: nowait skipifnotsilent

[UninstallRun]
Filename: "{cmd}"; Parameters: "/C taskkill /IM CodiyaMemo.exe /F"; Flags: runhidden; RunOnceId: "KillApp"

[Code]
// 설치 전에 켜져 있는 프로그램을 조용히 끄기 (「응용 프로그램 자동 닫기」 화면 없이) — 설치가 끝나면 다시 켜져요
function PrepareToInstall(var NeedsRestart: Boolean): String;
var R: Integer;
begin
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM CodiyaMemo.exe', '', SW_HIDE, ewWaitUntilTerminated, R);
  Sleep(800);
  Result := '';
end;
