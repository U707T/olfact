param(
    [string]$ToolRoot = '',
    [string]$DependencyDirectory = '',
    [string]$Java = ''
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if (!$ToolRoot) { $ToolRoot = Join-Path (Split-Path $repoRoot -Parent) '.panelfm-tools' }
if (!$DependencyDirectory) { $DependencyDirectory = Join-Path $repoRoot 'web/build/pure-jvm-deps' }
if (!$Java) { $Java = Join-Path $ToolRoot 'jdk21/jdk-21.0.13.1+2/bin/java.exe' }
$kotlinLib = Join-Path $ToolRoot 'kotlin/kotlinc/lib'
$outDirectory = Join-Path $repoRoot 'web/build/pure-jvm'
New-Item -ItemType Directory -Path $outDirectory -Force | Out-Null
$dependencies = @(
    (Join-Path $kotlinLib 'kotlin-stdlib.jar'),
    (Join-Path $ToolRoot 'lib/junit-4.13.2.jar'),
    (Join-Path $ToolRoot 'lib/hamcrest-core-1.3.jar'),
    (Join-Path $ToolRoot 'lib/kotlinx-coroutines-core-jvm-1.11.0.jar'),
    (Join-Path $ToolRoot 'lib/kotlinx-coroutines-test-jvm-1.11.0.jar'),
    (Join-Path $DependencyDirectory 'okhttp-jvm-5.5.1.jar'),
    (Join-Path $DependencyDirectory 'okio-jvm-3.18.1.jar'),
    (Join-Path $DependencyDirectory 'json-20240303.jar')
)
foreach ($dependency in (@($Java) + $dependencies + @((Join-Path $kotlinLib 'kotlin-compiler.jar')))) {
    if (!(Test-Path -LiteralPath $dependency -PathType Leaf)) { throw "Missing dependency: $dependency (see web/README.md)" }
}
$classpath = $dependencies -join [IO.Path]::PathSeparator
# DataStore persistence and Android layout inflation need the Android Gradle/CI environment.
$sources = @(Get-ChildItem (Join-Path $repoRoot 'web/src/main/java') -Recurse -Filter '*.kt' |
    Where-Object Name -NotIn @('SiteConfigStore.kt', 'DohPanelView.kt') | ForEach-Object FullName)
$sources += @(Get-ChildItem (Join-Path $repoRoot 'web/src/test/java') -Recurse -Filter '*.kt' | ForEach-Object FullName)
$sources += @(Get-ChildItem (Join-Path $PSScriptRoot 'support') -Filter '*.kt' | ForEach-Object FullName)
$sources += @(Get-ChildItem (Join-Path $PSScriptRoot 'tests') -Filter '*.kt' | ForEach-Object FullName)
$sources += Join-Path $repoRoot 'core/src/main/java/cn/olfact/core/api/Media.kt'
$sources += Join-Path $repoRoot 'core/src/main/java/cn/olfact/core/api/AdBlock.kt'
$classes = Join-Path $outDirectory 'tests.jar'
& $Java '-cp' (Join-Path $kotlinLib '*') 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler' `
    '-no-stdlib' '-no-reflect' '-jvm-target' '17' '-classpath' $classpath '-d' $classes @sources
if ($LASTEXITCODE -ne 0) { throw "Kotlin compilation failed: $LASTEXITCODE" }
$testRoot = Join-Path $repoRoot 'web/src/test/java'
$tests = @(Get-ChildItem $testRoot -Recurse -Filter '*Test.kt' | ForEach-Object {
    $_.FullName.Substring($testRoot.Length + 1).Replace('\', '.').Replace('/', '.').Replace('.kt', '')
})
$tests += 'cn.olfact.web.tools.PlatformBoundaryTest'
& $Java '-Dolfact.liveDoh=0' '-cp' ($classes + [IO.Path]::PathSeparator + $classpath) 'org.junit.runner.JUnitCore' @tests
if ($LASTEXITCODE -ne 0) { throw "JVM regression tests failed: $LASTEXITCODE" }
