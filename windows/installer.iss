; 코디야 전화팝업 — 설치 파일 (관리자 권한 없이 내 계정에만 설치)
#define AppVer "1.1.4"
[Setup]
AppId={{C0D1A7A0-3F2E-4B9C-8D61-7E5A2C9B4F10}
AppName=코디야 전화팝업
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

[InstallDelete]
; 예전 이름(코디야 전화 메모) 아이콘 정리 — 바탕화면 아이콘이 있던 사람은 새 이름으로 다시 만들어 줌
Type: files; Name: "{userprograms}\코디야 전화 메모.lnk"
Type: files; Name: "{userdesktop}\코디야 전화 메모.lnk"

[Files]
Source: "publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{userprograms}\코디야 전화팝업"; Filename: "{app}\CodiyaMemo.exe"
; 바탕화면 아이콘: 직접 설치하면 항상 · 자동 업데이트 때는 원래 있던 사람만 (WantDesktop)
Name: "{userdesktop}\코디야 전화팝업"; Filename: "{app}\CodiyaMemo.exe"; Check: WantDesktop

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "CodiyaMemo"; ValueData: """{app}\CodiyaMemo.exe"" /tray"; Flags: uninsdeletevalue

[Run]
Filename: "{app}\CodiyaMemo.exe"; Description: "지금 실행 (코디야 아이디로 로그인)"; Flags: nowait postinstall skipifsilent
; 자동 업데이트(조용히 설치)일 때는 화면 없이 다시 켜기
Filename: "{app}\CodiyaMemo.exe"; Parameters: "/tray"; Flags: nowait skipifnotsilent

[UninstallRun]
Filename: "{cmd}"; Parameters: "/C taskkill /IM CodiyaMemo.exe /F"; Flags: runhidden; RunOnceId: "KillApp"

[Code]
var HadOldDesktop: Boolean;

// 설치 시작 때: 예전 이름 바탕화면 아이콘이 있었는지 기억
function InitializeSetup(): Boolean;
begin
  HadOldDesktop := FileExists(ExpandConstant('{userdesktop}\코디야 전화 메모.lnk')) or FileExists(ExpandConstant('{userdesktop}\코디야 전화팝업.lnk'));
  Result := True;
end;

// 바탕화면 아이콘: 직접 설치하면 항상 · 자동 업데이트면 원래 아이콘이 있던 사람만 (지운 사람은 되살리지 않음)
function WantDesktop(): Boolean;
begin
  Result := (not WizardSilent) or HadOldDesktop;
end;

// 설치 전에 켜져 있는 프로그램을 조용히 끄기 (「응용 프로그램 자동 닫기」 화면 없이) — 설치가 끝나면 다시 켜져요
function PrepareToInstall(var NeedsRestart: Boolean): String;
var R: Integer;
begin
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM CodiyaMemo.exe', '', SW_HIDE, ewWaitUntilTerminated, R);
  Sleep(800);
  Result := '';
end;
