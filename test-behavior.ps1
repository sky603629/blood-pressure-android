$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$javaDir = if (Test-Path 'D:\DevSoftware\JDK11\bin\javac.exe') { 'D:\DevSoftware\JDK11\bin' } else { Join-Path $env:JAVA_HOME 'bin' }
$classesDir = Join-Path $projectDir 'build\regression-classes'
$dependenciesDir = Join-Path $projectDir 'build\test-deps'
$jsonJar = Join-Path $dependenciesDir 'android-json.jar'
New-Item -ItemType Directory -Force $classesDir, $dependenciesDir | Out-Null
if (!(Test-Path -LiteralPath $jsonJar)) {
    & curl.exe --fail --location --max-time 60 --output $jsonJar 'https://repo.maven.apache.org/maven2/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar'
    if ($LASTEXITCODE -ne 0) { throw 'Unable to download host-only Android JSON test implementation.' }
}
$sourceDir = Join-Path $projectDir 'app\src\main\java\com\pressurediary\app'
& (Join-Path $javaDir 'javac.exe') -encoding UTF-8 -cp $jsonJar -d $classesDir (Join-Path $sourceDir 'Measurement.java') (Join-Path $sourceDir 'MeasurementFileTransfer.java') (Join-Path $sourceDir 'ChartViewport.java') (Join-Path $sourceDir 'BloodPressureOcrParser.java') (Join-Path $sourceDir 'LcdReadingConsensus.java') (Join-Path $sourceDir 'LcdScreenLocator.java') (Join-Path $projectDir 'tests\com\pressurediary\app\RegressionTests.java') (Join-Path $projectDir 'tests\com\pressurediary\app\OcrParserTests.java') (Join-Path $projectDir 'tests\com\pressurediary\app\LcdOcrTests.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& (Join-Path $javaDir 'javac.exe') -encoding UTF-8 -cp "$classesDir;$jsonJar" -d $classesDir (Join-Path $sourceDir 'MeasurementSnapshot.java') (Join-Path $sourceDir 'MeasurementUploadProtocol.java') (Join-Path $projectDir 'tests\com\pressurediary\app\UploadProtocolTests.java') (Join-Path $projectDir 'tests\com\pressurediary\app\SyncProtocolTests.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& (Join-Path $javaDir 'java.exe') -cp "$classesDir;$jsonJar" com.pressurediary.app.RegressionTests
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& (Join-Path $javaDir 'java.exe') -cp "$classesDir;$jsonJar" com.pressurediary.app.OcrParserTests
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& (Join-Path $javaDir 'java.exe') -cp "$classesDir;$jsonJar" com.pressurediary.app.LcdOcrTests
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& (Join-Path $javaDir 'java.exe') -cp "$classesDir;$jsonJar" com.pressurediary.app.UploadProtocolTests
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& (Join-Path $javaDir 'java.exe') -cp "$classesDir;$jsonJar" com.pressurediary.app.SyncProtocolTests
exit $LASTEXITCODE
