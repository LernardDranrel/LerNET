param([Parameter(ValueFromRemainingArguments = $true)][string[]]$GradleArgs)

$workspace = $PSScriptRoot
$localJdk = Join-Path $workspace 'jdk\jdk-21.0.12.1+1'
$localSdk = Join-Path $workspace 'android-sdk'
if (Test-Path -LiteralPath (Join-Path $localJdk 'bin\jlink.exe')) { $env:JAVA_HOME = $localJdk }
if (Test-Path -LiteralPath (Join-Path $localSdk 'platforms\android-36\android.jar')) { $env:ANDROID_HOME = $localSdk }
$env:ANDROID_USER_HOME = Join-Path $workspace '.android-local'
$env:GRADLE_USER_HOME = Join-Path $workspace '.gradle-local'
$env:TEMP = Join-Path $workspace '.tmp'
$env:TMP = $env:TEMP
$env:TMPDIR = $env:TEMP
$env:WIX_TEMP = $env:TEMP
$env:JAVA_TOOL_OPTIONS = "-Djava.io.tmpdir=$env:TEMP -Duser.home=$workspace"

if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\jlink.exe'))) {
    throw "JDK 17+ is missing; set JAVA_HOME or install it under $localJdk"
}
if (-not $env:ANDROID_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:ANDROID_HOME 'platforms\android-36\android.jar'))) {
    throw "Android SDK 36 is missing; set ANDROID_HOME or install it under $localSdk"
}

New-Item -ItemType Directory -Path $env:ANDROID_USER_HOME, $env:GRADLE_USER_HOME, $env:TEMP -Force | Out-Null
if (-not $GradleArgs -or $GradleArgs.Count -eq 0) {
    $GradleArgs = @(':app:assembleDebug')
}

& (Join-Path $PSScriptRoot 'gradlew.bat') @GradleArgs
exit $LASTEXITCODE
