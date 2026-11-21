$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$workspaceTools = Join-Path (Split-Path -Parent $projectRoot) '.panelfm-tools'
$javaHome = Join-Path $workspaceTools 'jdk21\jdk-21.0.13.1+2'
$kotlinHome = Join-Path $workspaceTools 'kotlin\kotlinc'
$compiler = Join-Path $kotlinHome 'bin\kotlinc.bat'
$testOutput = Join-Path $workspaceTools 'olfact-ui-test-classes'
$libs = Join-Path $workspaceTools 'lib'
$required = @(
    (Join-Path $javaHome 'bin\java.exe'),
    $compiler,
    (Join-Path $libs 'junit-4.13.2.jar'),
    (Join-Path $libs 'hamcrest-core-1.3.jar'),
    (Join-Path $libs 'kotlinx-coroutines-core-jvm-1.11.0.jar')
)
foreach ($path in $required) {
    if (-not (Test-Path -LiteralPath $path)) { throw "Missing isolated test dependency: $path" }
}
New-Item -ItemType Directory -Path $testOutput -Force | Out-Null
$classpath = @(
    (Join-Path $libs 'junit-4.13.2.jar'),
    (Join-Path $libs 'hamcrest-core-1.3.jar'),
    (Join-Path $libs 'kotlinx-coroutines-core-jvm-1.11.0.jar')
) -join ';'
$sources = @(
    (Join-Path $projectRoot 'app\src\main\java\cn\olfact\app\ui\ai\AiRequestHandle.kt'),
    (Join-Path $projectRoot 'app\src\main\java\cn\olfact\app\ui\player\MediaDownloadContext.kt'),
    (Join-Path $projectRoot 'app\src\test\java\cn\olfact\app\ui\auditui\AiRequestHandleTest.kt'),
    (Join-Path $projectRoot 'app\src\test\java\cn\olfact\app\ui\auditui\MediaDownloadContextTest.kt')
)
& (Join-Path $javaHome 'bin\java.exe') "-Dkotlin.home=$kotlinHome" '-classpath' (Join-Path $kotlinHome 'lib\*') `
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler' '-jvm-target' '17' '-classpath' $classpath '-d' $testOutput @sources
if ($LASTEXITCODE -ne 0) { throw 'Olfact pure UI Kotlin compilation failed' }
$runtime = $testOutput + ';' + $classpath + ';' + (Join-Path $kotlinHome 'lib\kotlin-stdlib.jar')
& (Join-Path $javaHome 'bin\java.exe') '-classpath' $runtime 'org.junit.runner.JUnitCore' `
    'cn.olfact.app.ui.auditui.AiRequestHandleTest' `
    'cn.olfact.app.ui.auditui.MediaDownloadContextTest'
exit $LASTEXITCODE
