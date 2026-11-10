; Sayvra installer (Inno Setup 6). Built by build.ps1 into dist\Sayvra-Setup.exe.
; Installs per user (no admin) to %LOCALAPPDATA%\Programs\Sayvra. User data in %APPDATA%\Sayvra
; (setup answers, history, own API key) is left in place on uninstall.
; Sayvra was called Saylo. Same AppId, so a Saylo install is upgraded in place (and keeps its Programs\Saylo
; folder); the old Saylo.exe, shortcuts and startup entry are removed below. The app moves %APPDATA%\Saylo itself.

#define AppName "Sayvra"
#ifndef AppVersion
  #define AppVersion "0.1.0"
#endif

[Setup]
AppId={{6F4C2A1E-6E1B-4C55-9C2B-5A1D7E3B9A10}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=Sayvra
DefaultDirName={localappdata}\Programs\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=dist
OutputBaseFilename=Sayvra-Setup
SetupIconFile=assets\sayvra.ico
UninstallDisplayIcon={app}\Sayvra.exe
UninstallDisplayName={#AppName}
WizardStyle=modern
Compression=lzma2
SolidCompression=yes
CloseApplications=no

[Tasks]
Name: "startup"; Description: "Start Sayvra when Windows starts (it waits quietly in the tray)"
Name: "desktopicon"; Description: "Create a desktop shortcut"

[InstallDelete]
Type: files; Name: "{app}\Saylo.exe"
Type: files; Name: "{userprograms}\Saylo.lnk"
Type: files; Name: "{userdesktop}\Saylo.lnk"

[Files]
Source: "dist\Sayvra.exe"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{userprograms}\{#AppName}"; Filename: "{app}\Sayvra.exe"
Name: "{userdesktop}\{#AppName}"; Filename: "{app}\Sayvra.exe"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "Sayvra"; \
    ValueData: """{app}\Sayvra.exe"" --tray"; Tasks: startup; Flags: uninsdeletevalue

[Run]
Filename: "{app}\Sayvra.exe"; Description: "Open Sayvra now"; Flags: postinstall nowait skipifsilent
; In-app updates run this installer silently with /relaunch=1: open the new version when it's done.
Filename: "{app}\Sayvra.exe"; Flags: nowait; Check: Relaunch

[Code]
function SayloRunning(): Boolean;
var
  Locator, Wmi, Found: Variant;
begin
  try
    Locator := CreateOleObject('WbemScripting.SWbemLocator');
    Wmi := Locator.ConnectServer('.', 'root\CIMV2');
    Found := Wmi.ExecQuery('SELECT ProcessId FROM Win32_Process WHERE Name = ''Sayvra.exe'' OR Name = ''Saylo.exe''');
    Result := Found.Count > 0;
  except
    Result := False;
  end;
end;

// Sayvra keeps running in the tray, which locks Sayvra.exe. Ask it to quit before files are replaced or removed,
// and wait (up to 10 s) until it has exited: an in-app update starts this installer while Sayvra is closing.
procedure QuitRunningSaylo(Exe: String);
var
  Code, I: Integer;
begin
  if FileExists(Exe) then
  begin
    Exec(Exe, '--quit', '', SW_HIDE, ewWaitUntilTerminated, Code);
    for I := 1 to 20 do
    begin
      if not SayloRunning() then
        Break;
      Sleep(500);
    end;
  end;
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  QuitRunningSaylo(ExpandConstant('{app}\Saylo.exe'));  // a Saylo install being upgraded
  QuitRunningSaylo(ExpandConstant('{app}\Sayvra.exe'));
  Result := '';
end;

function Relaunch(): Boolean;
begin
  Result := ExpandConstant('{param:relaunch|0}') = '1';
end;

function InitializeUninstall(): Boolean;
begin
  QuitRunningSaylo(ExpandConstant('{app}\Sayvra.exe'));
  Result := True;
end;

// Upgrades: if the startup box is now unticked, remove an entry left by an earlier install. Saylo's old entry
// always goes (it pointed at Saylo.exe, which is removed).
procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssPostInstall then
  begin
    RegDeleteValue(HKEY_CURRENT_USER, 'Software\Microsoft\Windows\CurrentVersion\Run', 'Saylo');
    if not WizardIsTaskSelected('startup') then
      RegDeleteValue(HKEY_CURRENT_USER, 'Software\Microsoft\Windows\CurrentVersion\Run', 'Sayvra');
  end;
end;
