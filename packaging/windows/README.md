# Windows packaging

The release uses a stable Inno Setup AppId. Compose/jpackage supplies the application image and bundled Java, not the installation wizard.

1. Install the official [Inno Setup](https://jrsoftware.org/isdl.php) compiler, version 6.7.3 or newer. Verify the downloaded compiler's Authenticode signature before installing it.
2. Set `LERNET_ISCC` to the absolute path of `ISCC.exe` if it is outside the default `Program Files (x86)\Inno Setup 6` directory. The build also discovers the ignored `agent-tools/inno/compiler` directory.
3. Run `./build-windows.ps1 -PackageOnly`. This creates the portable ZIP and installer without launching LerNET. Running without `-PackageOnly` additionally invokes the desktop smoke test, which launches the packaged application.

## Update lifecycle

- Detection reads the stable Inno uninstall key, then exact-name LerNET Windows Installer entries in both machine registry views. Multiple legacy installations block migration rather than choosing one silently.
- The wizard displays the previous version, preserves its directory, and explains the temporary VPN interruption. Cancelling before Install never stops the application.
- `PrepareToInstall` runs `UpdateProcesses.ps1` as administrator. It signals the per-installation named event and waits for a graceful exit. Older versions use a scoped fallback: only the exact installed launcher and its Java/core descendants are stopped, after checking PID creation time. Another portable copy, another VPN core, the installer and PowerShell are excluded.
- The helper checks legacy product identity and installation directory before calling `msiexec /x`. Failure blocks file replacement. A restart-required result requests a reboot and another installation attempt.
- The installer does not change or remove `%APPDATA%\LerNET`. The finish-page launch checkbox is checked by default and skipped during silent installation. Automatic Restart Manager relaunch is disabled to prevent duplicate starts.
- Inno's setup log is written to the Windows temporary directory. The preparation exit code is logged without command lines or profile contents.

## Verification

`powershell.exe -NoProfile -File packaging/windows/Test-UpdateProcesses.ps1` uses synthetic process records only. JVM tests check event identity and shared release version/asset validation. Inno compiler verification checks script syntax and embeds the application image.

Do not run update integration tests against a machine whose live VPN supplies the development connection. Actual MSI migration, locked-file replacement and post-install launch require a disposable Windows VM or a user-authorized installation. These are not claimed as exercised by fixture tests.

Downloaded update installers remain in a uniquely named Windows temporary directory, so a failed UAC request can be retried; Windows temporary-file cleanup can remove them later. Portable users should use ZIP replacement; invoking the installation wizard creates or updates the machine installation.
