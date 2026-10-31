; Saylo installer (Inno Setup 6). Built by build.ps1 into dist\Saylo-Setup.exe.
; Installs per user (no admin) to %LOCALAPPDATA%\Programs\Saylo. User data in %APPDATA%\Saylo
; (setup answers, history, own API key) is left in place on uninstall.

#define AppName "Saylo"
#ifndef AppVersion
  #define AppVersion "0.1.0"
#endif

[Setup]
AppId={{6F4C2A1E-6E1B-4C55-9C2B-5A1D7E3B9A10}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=Saylo
DefaultDirName={localappdata}\Programs\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=dist
OutputBaseFilename=Saylo-Setup
SetupIconFile=assets\saylo.ico
UninstallDisplayIcon={app}\Saylo.exe
UninstallDisplayName={#AppName}
WizardStyle=modern
Compression=lzma2
SolidCompression=yes
CloseApplications=no

[Tasks]
Name: "startup"; Description: "Start Saylo when Windows starts (it waits quietly in the tray)"
Name: "desktopicon"; Description: "Create a desktop shortcut"

[Files]
Source: "dist\Saylo.exe"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{userprograms}\{#AppName}"; Filename: "{app}\Saylo.exe"
Name: "{userdesktop}\{#AppName}"; Filename: "{app}\Saylo.exe"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "Saylo"; \
    ValueData: """{app}\Saylo.exe"" --tray"; Tasks: startup; Flags: uninsdeletevalue

[Run]
Filename: "{app}\Saylo.exe"; Description: "Open Saylo now"; Flags: postinstall nowait skipifsilent
; In-app updates run this installer silently with /relaunch=1: open the new version when it's done.
Filename: "{app}\Saylo.exe"; Flags: nowait; Check: Relaunch

[Code]
function SayloRunning(): Boolean;
var
  Locator, Wmi, Found: Variant;
begin
  try
    Locator := CreateOleObject('WbemScripting.SWbemLocator');
    Wmi := Locator.ConnectServer('.', 'root\CIMV2');
    Found := Wmi.ExecQuery('SELECT ProcessId FROM Win32_Process WHERE Name = ''Saylo.exe''');
    Result := Found.Count > 0;
  except
    Result := False;
  end;
end;

// Saylo keeps running in the tray, which locks Saylo.exe. Ask it to quit before files are replaced or removed,
// and wait (up to 10 s) until it has exited: an in-app update starts this installer while Saylo is closing.
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
  QuitRunningSaylo(ExpandConstant('{app}\Saylo.exe'));
  Result := '';
end;

function Relaunch(): Boolean;
begin
  Result := ExpandConstant('{param:relaunch|0}') = '1';
end;

function InitializeUninstall(): Boolean;
begin
  QuitRunningSaylo(ExpandConstant('{app}\Saylo.exe'));
  Result := True;
end;

// Upgrades: if the startup box is now unticked, remove an entry left by an earlier install.
procedure CurStepChanged(CurStep: TSetupStep);
begin
  if (CurStep = ssPostInstall) and not WizardIsTaskSelected('startup') then
    RegDeleteValue(HKEY_CURRENT_USER, 'Software\Microsoft\Windows\CurrentVersion\Run', 'Saylo');
end;
