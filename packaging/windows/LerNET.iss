#ifndef AppVersion
  #error AppVersion is required
#endif
#ifndef ImageDir
  #error ImageDir is required
#endif
#ifndef OutputDir
  #error OutputDir is required
#endif

#define GuardSource SourcePath + "..\..\desktop-app\src\main\resources\runtime\lernet-protection-service.exe"
#define GuardHash GetSHA256OfFile(GuardSource)
#define ShutdownHash GetSHA256OfFile(SourcePath + "UpdateProcesses.ps1")

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
Source: "UpdateProcesses.ps1"; DestDir: "{app}"; Flags: ignoreversion
; This explicit uninstaller entry point never launches the Java GUI or a TUN.
Source: "..\..\desktop-app\src\main\resources\runtime\lernet-protection-service.exe"; DestDir: "{app}"; Flags: ignoreversion

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

// Execute only after the user has confirmed removal, never during an update or
// an initial uninstaller prompt that may be cancelled. Failed recovery aborts
// file removal so that the remaining guard still has a working recovery tool.
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var Code: Integer; Helper, ShutdownScript, Params: String;
begin
  if CurUninstallStep <> usUninstall then exit;
  // Copy into the elevated uninstaller's private temporary folder, then verify
  // against checksums embedded in this installer before either helper executes.
  Helper := ExpandConstant('{tmp}\lernet-protection-uninstall.exe');
  ShutdownScript := ExpandConstant('{tmp}\LerNET-shutdown.ps1');
  if not FileCopy(ExpandConstant('{app}\lernet-protection-service.exe'), Helper, False) or
     not FileCopy(ExpandConstant('{app}\UpdateProcesses.ps1'), ShutdownScript, False) then
    RaiseException('Не найдены компоненты восстановления LerNET. Повторно установите эту версию и повторите удаление.');
  if (GetSHA256OfFile(Helper) <> '{#GuardHash}') or (GetSHA256OfFile(ShutdownScript) <> '{#ShutdownHash}') then
    RaiseException('Компоненты восстановления LerNET были изменены. Приложение не удалено. Повторно установите официальную сборку.');
  Params := '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + ShutdownScript +
    '" -InstallDir "' + ExpandConstant('{app}') + '"';
  if not Exec(ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe'), Params, '', SW_HIDE, ewWaitUntilTerminated, Code) then
    RaiseException('Не удалось остановить LerNET перед удалением. Закройте приложение через меню в трее и повторите.');
  if Code <> 0 then
    RaiseException('Не удалось подтвердить остановку LerNET. Приложение не удалено. Код: ' + IntToStr(Code));
  if not Exec(Helper, '--uninstall', ExpandConstant('{sys}'), SW_HIDE, ewWaitUntilTerminated, Code) then
    RaiseException('Не удалось запустить восстановление сети LerNET. Приложение не удалено.');
  if Code <> 0 then
    RaiseException('Windows не разрешила снять собственные фильтры защиты LerNET. Приложение не удалено, чтобы сохранить восстановление сети. Откройте LerNET от администратора, отключите защиту и повторите удаление. Код: ' + IntToStr(Code));
  Log('LerNET owned protection removed for explicit uninstall.');
end;
