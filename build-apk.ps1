$ErrorActionPreference = "Stop"

$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $projectDir
$wrapper = Join-Path $repoRoot "android\gradlew.bat"

if (Test-Path "D:\DevSoftware\JDK11\bin\java.exe") {
    $env:JAVA_HOME = "D:\DevSoftware\JDK11"
}
if (Test-Path "D:\DevSoftware\ANSDK\platforms\android-33\android.jar") {
    $env:ANDROID_HOME = "D:\DevSoftware\ANSDK"
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
}

& $wrapper -p $projectDir --no-daemon assembleDebug
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Output (Join-Path $projectDir "app\build\outputs\apk\debug\app-debug.apk")
