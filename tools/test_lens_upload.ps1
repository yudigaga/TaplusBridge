param(
    [string]$OutputDirectory,
    [string]$EvidencePath
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$javacCommand = Get-Command javac.exe -ErrorAction SilentlyContinue
if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
    $javacPath = Join-Path $env:JAVA_HOME 'bin\javac.exe'
} elseif ($javacCommand) {
    $javacPath = $javacCommand.Source
} else {
    throw 'JDK 21 is required. Set JAVA_HOME to the installed JDK.'
}
$javaPath = Join-Path (Split-Path -Parent $javacPath) 'java.exe'
$jdkVersion = (& $javacPath -version 2>&1 | Out-String).Trim()
if ($jdkVersion -notmatch '^javac 21\.') { throw "JDK 21 is required; found $jdkVersion" }
$gradleCacheBase = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
$dependencyCache = Join-Path $gradleCacheBase 'caches\modules-2\files-2.1'
$junit = Get-ChildItem -LiteralPath (Join-Path $dependencyCache 'junit\junit\4.13.2') -Recurse -Filter 'junit-4.13.2.jar' |
    Select-Object -First 1 -ExpandProperty FullName
$hamcrest = Get-ChildItem -LiteralPath (Join-Path $dependencyCache 'org.hamcrest\hamcrest-core\1.3') -Recurse -Filter 'hamcrest-core-1.3.jar' |
    Select-Object -First 1 -ExpandProperty FullName
if (!$junit -or !$hamcrest) { throw 'JUnit 4.13.2 and Hamcrest 1.3 must already be present in the Gradle dependency cache.' }
if (!$OutputDirectory) {
    $OutputDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ('taplus-lens-http-' + [Guid]::NewGuid().ToString('N'))
}
$OutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
if (!$EvidencePath) { $EvidencePath = Join-Path $repoRoot 'artifacts\lens-upload-client-unit.txt' }
elseif (![System.IO.Path]::IsPathRooted($EvidencePath)) { $EvidencePath = Join-Path $repoRoot $EvidencePath }
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
New-Item -ItemType Directory -Path (Split-Path -Parent $EvidencePath) -Force | Out-Null
$separator = [System.IO.Path]::PathSeparator
$dependencies = $junit + $separator + $hamcrest
$source = Join-Path $repoRoot 'app\src\main\java\io\github\yudigaga\taplusbridge\lens\LensUploadClient.java'
$tests = Join-Path $PSScriptRoot 'tests\LensUploadClientTest.java'
# AGP compiles against Android's bootclasspath, which intentionally excludes the JDK HTTP test server.
& $javacPath -encoding UTF-8 -cp $dependencies -d $OutputDirectory $source $tests
if ($LASTEXITCODE -ne 0) { throw 'Lens upload transport test compilation failed.' }
& $javaPath -cp ($OutputDirectory + $separator + $dependencies) org.junit.runner.JUnitCore io.github.yudigaga.taplusbridge.lens.LensUploadClientTest 2>&1 |
    Tee-Object -FilePath $EvidencePath
if ($LASTEXITCODE -ne 0) { throw 'Lens upload transport tests failed.' }
Write-Output "JDK: $jdkVersion"
Write-Output "JUnit: $junit"
Write-Output "Hamcrest: $hamcrest"
Write-Output "Evidence: $EvidencePath"
