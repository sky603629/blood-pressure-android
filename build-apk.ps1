param(
    [ValidateSet('debug', 'release-test')]
    [string]$Variant = 'debug',
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $projectDir
$wrapper = Join-Path $repoRoot 'android\gradlew.bat'
$ocrJavaTmp = Join-Path $projectDir 'build\java-tmp'
New-Item -ItemType Directory -Force $ocrJavaTmp | Out-Null
# Avoid Windows JDK 17 Unix-domain socket failures with abbreviated TEMP paths.
$env:JAVA_TOOL_OPTIONS = ($env:JAVA_TOOL_OPTIONS + ' -Djdk.net.unixdomain.tmpdir="' + $ocrJavaTmp + '"').Trim()
if (Test-Path 'D:\DevSoftware\JDK17\bin\java.exe') {
    $env:JAVA_HOME = 'D:\DevSoftware\JDK17'
}
if (Test-Path 'D:\DevSoftware\ANSDK\platforms\android-33\android.jar') {
    $env:ANDROID_HOME = 'D:\DevSoftware\ANSDK'
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
}
$buildVariant = if ($Variant -eq 'release-test') { 'release' } else { 'debug' }
$task = if ($buildVariant -eq 'release') { 'assembleRelease' } else { 'assembleDebug' }
$gradleArgs = @('-p', $projectDir, '--no-daemon')
if ($Offline) { $gradleArgs += '--offline' }
$gradleArgs += $task
& $wrapper @gradleArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$buildTools = Join-Path $env:ANDROID_HOME 'build-tools\37.0.0'
if (!(Test-Path -LiteralPath $buildTools)) { $buildTools = Join-Path $env:ANDROID_HOME 'build-tools\33.0.0' }
$signer = Join-Path $buildTools 'apksigner.bat'
$aligner = Join-Path $buildTools 'zipalign.exe'
$apkDir = Join-Path $projectDir "app\build\outputs\apk\$buildVariant"
$metadata = Get-Content -Raw (Join-Path $apkDir 'output-metadata.json') | ConvertFrom-Json
$version = $metadata.elements[0].versionName
$dist = Join-Path $repoRoot 'dist'
New-Item -ItemType Directory -Force $dist | Out-Null
$destination = Join-Path $dist "blood-pressure-diary-$version-$Variant.apk"
if ($buildVariant -eq 'release') {
    $keyPath = Join-Path $env:USERPROFILE '.android\debug.keystore'
    if (!(Test-Path -LiteralPath $keyPath)) {
        throw 'Original signing key is missing. Do not generate a replacement for this upgrade.'
    }
    $aligned = Join-Path $apkDir 'app-release-aligned.apk'
    & $aligner -P 16 -f 4 (Join-Path $apkDir 'app-release-unsigned.apk') $aligned
    if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed.' }
    & $signer sign --ks $keyPath --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out $destination $aligned
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
} else {
    Copy-Item -LiteralPath (Join-Path $apkDir 'app-debug.apk') -Destination $destination -Force
}
$newCertificateOutput = & $signer verify --print-certs $destination
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
$newCertificate = $newCertificateOutput | Select-String 'certificate SHA-256 digest: ([0-9a-fA-F]{64})' | ForEach-Object { $_.Matches[0].Groups[1].Value.ToLowerInvariant() } | Select-Object -Unique
$previous = Join-Path $dist 'blood-pressure-diary-1.0.0-release-test.apk'
if (Test-Path -LiteralPath $previous) {
    $previousCertificateOutput = & $signer verify --print-certs $previous
    if ($LASTEXITCODE -ne 0) { throw 'Old APK signature verification failed.' }
    $previousCertificate = $previousCertificateOutput | Select-String 'certificate SHA-256 digest: ([0-9a-fA-F]{64})' | ForEach-Object { $_.Matches[0].Groups[1].Value.ToLowerInvariant() } | Select-Object -Unique
    if (!$newCertificate -or $newCertificate -ne $previousCertificate) { throw 'APK certificates differ. This package cannot safely upgrade the old installation.' }
}
& $aligner -P 16 -c 4 $destination
if ($LASTEXITCODE -ne 0) { throw 'APK alignment verification failed.' }
Write-Output $newCertificate
Write-Output $destination
