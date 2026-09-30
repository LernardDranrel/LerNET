#ifndef AppVersion
  #error AppVersion is required
#endif
#ifndef ImageDir
  #error ImageDir is required
#endif
#ifndef OutputDir
  #error OutputDir is required
#endif

[Setup]
AppId={{F503F0CF-7CE2-4ECB-96F5-841508E0992B}
AppName=LerNET
AppVersion={#AppVersion}
AppPublisher=LernardDranrel
AppPublisherURL=https://github.com/LernardDranrel/LerNET
DefaultDirName={autopf}\LerNET
DefaultGroupName=LerNET
DisableProgramGroupPage=yes
UninstallDisplayIcon={app}\LerNET.exe
SetupIconFile=..\..\desktop-app\src\main\resources\lernet.ico
OutputDir={#OutputDir}
OutputBaseFilename=LerNET-{#AppVersion}-install
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
WizardStyle=modern dynamic
Compression=lzma2
SolidCompression=yes
CloseApplications=yes
RestartApplications=no
SetupLogging=yes
VersionInfoVersion={#AppVersion}

[Languages]
Name: "russian"; MessagesFile: "compiler:Languages\Russian.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Создать ярлык на рабочем столе"; Flags: unchecked

[Files]
Source: "{#ImageDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "UpdateProcesses.ps1"; Flags: dontcopy

[Icons]
Name: "{group}\LerNET"; Filename: "{app}\LerNET.exe"
Name: "{autodesktop}\LerNET"; Filename: "{app}\LerNET.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\LerNET.exe"; Description: "Запустить LerNET"; Flags: postinstall nowait skipifsilent

[Code]
const
  UninstallRoot = 'Software\Microsoft\Windows\CurrentVersion\Uninstall';
  InnoKey = UninstallRoot + '\{F503F0CF-7CE2-4ECB-96F5-841508E0992B}_is1';
var
  PreviousVersion, PreviousDir, LegacyProduct: String;
  Prepared: Boolean;

procedure FindLegacy(Root: Integer);
var Names: TArrayOfString; I: Integer; Key, Name, Location: String; IsMsi: Cardinal;
begin
  if not RegGetSubkeyNames(Root, UninstallRoot, Names) then exit;
  for I := 0 to GetArrayLength(Names) - 1 do begin
    Key := UninstallRoot + '\' + Names[I];
    if RegQueryStringValue(Root, Key, 'DisplayName', Name) and (Name = 'LerNET') and
       RegQueryDWordValue(Root, Key, 'WindowsInstaller', IsMsi) and (IsMsi = 1) and
       RegQueryStringValue(Root, Key, 'InstallLocation', Location) and
       FileExists(AddBackslash(Location) + 'LerNET.exe') then begin
      if PreviousDir <> '' then begin
        RaiseException('Найдены несколько установок LerNET. Удалите лишние установки через параметры Windows и повторите обновление.');
      end;
      LegacyProduct := Names[I]; PreviousDir := Location;
      RegQueryStringValue(Root, Key, 'DisplayVersion', PreviousVersion);
    end;
  end;
end;

function InitializeSetup(): Boolean;
begin
  RegQueryStringValue(HKLM64, InnoKey, 'InstallLocation', PreviousDir);
  RegQueryStringValue(HKLM64, InnoKey, 'DisplayVersion', PreviousVersion);
  if PreviousDir = '' then begin FindLegacy(HKLM64); FindLegacy(HKLM32); end;
  Result := True;
end;

procedure InitializeWizard();
begin
  if PreviousDir <> '' then begin
    WizardForm.DirEdit.Text := RemoveBackslashUnlessRoot(PreviousDir);
    WizardForm.Caption := 'Обновление LerNET';
    WizardForm.WelcomeLabel1.Caption := 'Обновление LerNET до {#AppVersion}';
    WizardForm.WelcomeLabel2.Caption := 'Найдена версия ' + PreviousVersion + '. Мастер обновит файлы приложения. Ваши профили, маршруты и настройки сохранятся.';
    WizardForm.SelectDirPage.Visible := False;
  end;
end;

function ShouldSkipPage(PageID: Integer): Boolean;
begin
  Result := (PreviousDir <> '') and (PageID = wpSelectDir);
end;

function UpdateReadyMemo(Space, NewLine, MemoUserInfoInfo, MemoDirInfo, MemoTypeInfo, MemoComponentsInfo, MemoGroupInfo, MemoTasksInfo: String): String;
begin
  if PreviousDir <> '' then
    Result := 'Обновление LerNET: ' + PreviousVersion + ' → {#AppVersion}' + NewLine + NewLine +
      'Запущенная установленная копия LerNET будет остановлена. VPN временно отключится. Профили и настройки сохранятся.' + NewLine + NewLine + MemoDirInfo
  else Result := MemoDirInfo;
  Result := Result + NewLine + MemoTasksInfo;
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
var Code: Integer; Params: String;
begin
  Result := '';
  if Prepared then exit;
  ExtractTemporaryFile('UpdateProcesses.ps1');
  Params := '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + ExpandConstant('{tmp}\UpdateProcesses.ps1') +
    '" -InstallDir "' + ExpandConstant('{app}') + '"';
  if LegacyProduct <> '' then Params := Params + ' -LegacyProduct "' + LegacyProduct + '"';
  if not Exec(ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe'), Params, '', SW_HIDE, ewWaitUntilTerminated, Code) then
    Result := 'Не удалось подготовить обновление. Закройте LerNET через меню в трее и повторите.'
  else if Code = 10 then begin
    NeedsRestart := True;
    Result := 'Windows запросила перезагрузку после удаления старого установщика. Перезагрузите компьютер и повторите установку LerNET.';
  end else if Code <> 0 then
    Result := 'Не удалось остановить LerNET или удалить предыдущую MSI-установку. Закройте LerNET через меню в трее и повторите. Код подготовки: ' + IntToStr(Code)
  else Prepared := True;
  Log('LerNET preparation exit code: ' + IntToStr(Code));
end;

procedure CurPageChanged(CurPageID: Integer);
begin
  if PreviousDir <> '' then begin
    if CurPageID = wpInstalling then WizardForm.StatusLabel.Caption := 'Обновляем LerNET…';
    if CurPageID = wpFinished then WizardForm.FinishedHeadingLabel.Caption := 'LerNET обновлён до {#AppVersion}';
  end;
end;
