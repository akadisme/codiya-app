; 코디야 전화 메모 — 설치 파일 (관리자 권한 없이 내 계정에만 설치)
#define AppVer "1.0.2"
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
CloseApplications=force

[Languages]
Name: "korean"; MessagesFile: "compiler:Languages\Korean.isl"

[Tasks]
Name: "desktopicon"; Description: "바탕화면에 아이콘 만들기"; Flags: unchecked

[Files]
Source: "publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{userprograms}\코디야 전화 메모"; Filename: "{app}\CodiyaMemo.exe"
Name: "{userdesktop}\코디야 전화 메모"; Filename: "{app}\CodiyaMemo.exe"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "CodiyaMemo"; ValueData: """{app}\CodiyaMemo.exe"" /tray"; Flags: uninsdeletevalue

[Run]
Filename: "{app}\CodiyaMemo.exe"; Description: "지금 실행 (코디야 아이디로 로그인)"; Flags: nowait postinstall

[UninstallRun]
Filename: "{cmd}"; Parameters: "/C taskkill /IM CodiyaMemo.exe /F"; Flags: runhidden; RunOnceId: "KillApp"
