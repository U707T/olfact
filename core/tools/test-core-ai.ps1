param(
    [string]$ToolRoot,
    [string]$DependencyRoot
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$workspace = Split-Path $repo -Parent
if (-not $ToolRoot) { $ToolRoot = Join-Path $workspace '.panelfm-tools' }
if (-not $DependencyRoot) { $DependencyRoot = Join-Path $workspace '.tools/olfact/lib' }
$java = Get-ChildItem (Join-Path $ToolRoot 'jdk21') -Filter java.exe -Recurse -File | Select-Object -First 1
if (-not $java) { throw 'JDK 21 java.exe is missing.' }
$kotlin = Join-Path $ToolRoot 'kotlin/kotlinc/lib'
$plugin = Join-Path $kotlin 'kotlin-serialization-compiler-plugin.jar'
$runtime = @(
    (Join-Path $kotlin 'kotlin-stdlib.jar'),
    (Join-Path $ToolRoot 'lib/junit-4.13.2.jar'),
    (Join-Path $ToolRoot 'lib/hamcrest-core-1.3.jar'),
    (Join-Path $ToolRoot 'lib/kotlinx-coroutines-core-jvm-1.11.0.jar'),
    (Join-Path $DependencyRoot 'kotlinx-serialization-core-jvm-1.11.0.jar'),
    (Join-Path $DependencyRoot 'kotlinx-serialization-json-jvm-1.11.0.jar'),
    (Join-Path $DependencyRoot 'okhttp-jvm-5.5.1.jar'),
    (Join-Path $DependencyRoot 'okio-jvm-3.18.1.jar')
)
foreach ($file in $runtime + @($plugin, (Join-Path $kotlin 'kotlin-compiler.jar'))) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing JVM dependency: $file" }
}

$build = Join-Path (Join-Path $workspace '.tools/olfact/build/core-ai') ([guid]::NewGuid().ToString('N'))
$out = Join-Path $build 'tests.jar'
$tmp = Join-Path $build 'junit-tmp'
$null = New-Item -ItemType Directory -Force -Path $build, $tmp
$core = Join-Path $repo 'core/src/main/java/cn/olfact/core'
$ai = Join-Path $repo 'ai/src/main/java/cn/olfact/ai'
$testFiles = Get-ChildItem -Recurse -Filter '*.kt' -File -Path (Join-Path $repo 'core/src/test/java'), (Join-Path $repo 'ai/src/test/java')
$purePaths = @('adblock','b4','backup','net','network','script') | ForEach-Object { Join-Path $core $_ }
$sources = @(
    Get-ChildItem -Recurse -Filter '*.kt' -File -Path (Join-Path $PSScriptRoot 'jvm-stubs')
    Get-ChildItem -Recurse -Filter '*.kt' -File -Path $purePaths |
        Where-Object Name -NotIn @('AutoBackupWorker.kt', 'PrivacyCleaner.kt', 'GmJsInterface.kt')
    Get-Item (Join-Path $core 'api/AdBlock.kt'), (Join-Path $core 'api/Injection.kt'), (Join-Path $core 'api/Settings.kt'), (Join-Path $core 'storage/Settings.kt')
    Get-ChildItem -Recurse -Filter '*.kt' -File -Path $ai | Where-Object Name -NotIn @('AiFacade.kt', 'SelectionTranslator.kt')
    $testFiles
)
$compilerArgs = Join-Path $build 'compiler.args'
$classpath = $runtime -join ';'
@(
    '-no-stdlib', '-no-reflect', '-jvm-target', '17',
    '-classpath', ('"' + ($classpath -replace '\\','/') + '"'),
    ('-Xplugin="' + ($plugin -replace '\\','/') + '"'),
    '-d', ('"' + ($out -replace '\\','/') + '"')
    foreach ($file in $sources) { '"' + ($file.FullName -replace '\\','/') + '"' }
) | Set-Content -Encoding utf8 -LiteralPath $compilerArgs

Push-Location $repo
try {
    & $java.FullName -cp (Join-Path $kotlin '*') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler "@$compilerArgs"
    if ($LASTEXITCODE -ne 0) { throw "Kotlin compilation failed ($LASTEXITCODE)." }
    $tests = $testFiles | Where-Object Name -Like '*Test.kt' | ForEach-Object {
        $javaPath = $_.FullName -split '[\\/]src[\\/]test[\\/]java[\\/]', 2
        (($javaPath[1] -replace '[\\/]', '.') -replace '\.kt$', '')
    }
    & $java.FullName "-Djava.io.tmpdir=$tmp" -cp "$out;$classpath" org.junit.runner.JUnitCore @tests
    if ($LASTEXITCODE -ne 0) { throw "JUnit failed ($LASTEXITCODE)." }
    $out | Set-Content -Encoding utf8 -LiteralPath (Join-Path (Split-Path $build -Parent) 'latest-success.txt')
    Write-Output "Verified core/AI artifact: $out"
} finally {
    Pop-Location
}
