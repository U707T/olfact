param(
    [string]$ToolRoot = '',
    [string]$DependencyRoot = '',
    [string]$WebJar = '',
    [string]$CoreJar = ''
)
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$workspace = Split-Path $repoRoot -Parent
if (!$ToolRoot) { $ToolRoot = Join-Path $workspace '.panelfm-tools' }
if (!$DependencyRoot) { $DependencyRoot = Join-Path $workspace '.tools/olfact/lib' }
if (!$WebJar) { $WebJar = Join-Path $repoRoot 'web/build/pure-jvm/tests.jar' }
if (!$CoreJar) {
    $coreBuild = Join-Path $workspace '.tools/olfact/build/core-ai'
    $successMarker = Join-Path $coreBuild 'latest-success.txt'
    if (Test-Path -LiteralPath $successMarker -PathType Leaf) {
        $CoreJar = [IO.File]::ReadAllText($successMarker).Trim()
        if (![IO.Path]::IsPathRooted($CoreJar)) { $CoreJar = Join-Path $coreBuild $CoreJar }
    }
    if (!$CoreJar) { throw 'No successful core-ai artifact marker. Run core/tools/test-core-ai.ps1 first.' }
}
Write-Host "Using core artifact: $CoreJar"
$artifact = Get-Item -LiteralPath $CoreJar -ErrorAction Stop
$coreSources = @('core/src/main/java/cn/olfact/core/script', 'core/src/main/java/cn/olfact/core/b4',
    'core/src/main/java/cn/olfact/core/net', 'core/src/main/java/cn/olfact/core/network',
    'core/tools/jvm-stubs', 'ai/src/main/java/cn/olfact/ai') | ForEach-Object {
    Get-ChildItem (Join-Path $repoRoot $_) -Recurse -Filter '*.kt' -File |
        Where-Object Name -NotIn @('GmJsInterface.kt', 'AiFacade.kt', 'SelectionTranslator.kt')
}
$stale = @($coreSources | Where-Object LastWriteTimeUtc -GT $artifact.LastWriteTimeUtc)
if ($stale.Count) { throw "Core artifact predates $($stale[0].FullName); rebuild core/tools/test-core-ai.ps1 before running app tests." }
if (!(Test-Path -LiteralPath $WebJar -PathType Leaf)) { throw 'Missing Web artifact; run web/tools/test-web.ps1 first.' }
$webArtifact = Get-Item -LiteralPath $WebJar
$webSources = @(
    Get-ChildItem (Join-Path $repoRoot 'web/src/main/java') -Recurse -Filter '*.kt' -File |
        Where-Object Name -NotIn @('SiteConfigStore.kt', 'DohPanelView.kt')
    Get-ChildItem (Join-Path $repoRoot 'web/tools/support') -Recurse -Filter '*.kt' -File
)
$staleWeb = @($webSources | Where-Object LastWriteTimeUtc -GT $webArtifact.LastWriteTimeUtc)
if ($staleWeb.Count) { throw "Web artifact predates $($staleWeb[0].FullName); rebuild web/tools/test-web.ps1 before running app tests." }
$java = Join-Path $ToolRoot 'jdk21/jdk-21.0.13.1+2/bin/java.exe'
$kotlin = Join-Path $ToolRoot 'kotlin/kotlinc/lib'
$out = Join-Path $repoRoot 'app/build/pure-data'
New-Item -ItemType Directory -Path $out -Force | Out-Null
$dependencies = @(
    (Join-Path $kotlin 'kotlin-stdlib.jar'),
    (Join-Path $ToolRoot 'lib/junit-4.13.2.jar'),
    (Join-Path $ToolRoot 'lib/hamcrest-core-1.3.jar'),
    (Join-Path $ToolRoot 'lib/kotlinx-coroutines-core-jvm-1.11.0.jar'),
    (Join-Path $ToolRoot 'lib/kotlinx-coroutines-test-jvm-1.11.0.jar'),
    (Join-Path $DependencyRoot 'okhttp-jvm-5.5.1.jar'),
    (Join-Path $DependencyRoot 'okio-jvm-3.18.1.jar'),
    (Join-Path $DependencyRoot 'kotlinx-serialization-core-jvm-1.11.0.jar'),
    (Join-Path $DependencyRoot 'kotlinx-serialization-json-jvm-1.11.0.jar'),
    (Join-Path $DependencyRoot 'json-20250107.jar'),
    $WebJar, $CoreJar
)
foreach ($path in (@($java, (Join-Path $kotlin 'kotlin-compiler.jar')) + $dependencies)) {
    if (!(Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Missing dependency: $path. Run web/tools/test-web.ps1 and core/tools/test-core-ai.ps1 first; see app/tools/support-data/README.md."
    }
}
# Compile the common LRU contract, omitting only its Robolectric imports and concrete CI runner.
# The lightweight runner is named SurfaceCachePureTest and uses identity-only View tokens.
$contractPath = Join-Path $repoRoot 'app/src/test/java/cn/olfact/app/ui/browser/SurfaceCacheTest.kt'
$contract = [IO.File]::ReadAllText($contractPath)
$marker = '/** CI executes the same LRU contract'
if (!$contract.Contains($marker)) { throw 'SurfaceCache test contract marker changed; update the lightweight runner.' }
$contract = $contract.Substring(0, $contract.IndexOf($marker))
$contract = [regex]::Replace($contract, '(?m)^import (androidx\.test\.|org\.robolectric\.|org\.junit\.runner\.RunWith).*\r?\n', '')
$contractOutput = Join-Path $out 'SurfaceCacheContract.kt'
[IO.File]::WriteAllText($contractOutput, $contract)
$sources = @(
    'app/src/main/java/cn/olfact/app/data/BookmarkStore.kt',
    'app/src/main/java/cn/olfact/app/data/FavoriteStore.kt',
    'app/src/main/java/cn/olfact/app/data/BrowserSession.kt',
    'app/src/main/java/cn/olfact/app/data/Json.kt',
    'app/src/main/java/cn/olfact/app/data/DohUpstreams.kt',
    'app/src/main/java/cn/olfact/app/data/DohAdapter.kt',
    'app/src/main/java/cn/olfact/app/data/AiAdapter.kt',
    'app/src/main/java/cn/olfact/app/data/ScriptAdapter.kt',
    'app/src/main/java/cn/olfact/app/data/DownloadAdapter.kt',
    'app/src/main/java/cn/olfact/app/link/LinkExtractor.kt',
    'app/src/main/java/cn/olfact/app/ui/browser/SurfaceCache.kt',
    'app/src/main/java/cn/olfact/app/ui/browser/BrowserFileChooser.kt',
    'app/src/main/java/cn/olfact/app/ui/BottomBarSlots.kt',
    'app/src/main/java/cn/olfact/app/ui/ai/AiRequestHandle.kt',
    'app/src/main/java/cn/olfact/app/ui/player/MediaDownloadContext.kt',
    'app/src/main/java/cn/olfact/app/ui/player/SnifferFeed.kt',
    'core/src/main/java/cn/olfact/core/api/BrowserSurface.kt',
    'core/src/main/java/cn/olfact/core/api/Ai.kt',
    'core/src/main/java/cn/olfact/core/api/Doh.kt',
    'core/src/main/java/cn/olfact/core/api/Settings.kt',
    'core/src/main/java/cn/olfact/core/api/Script.kt',
    'core/src/main/java/cn/olfact/core/api/Download.kt',
    'download/src/main/java/cn/olfact/download/DownloadRecord.kt',
    'download/src/main/java/cn/olfact/download/DownloadSpec.kt',
    'download/src/main/java/cn/olfact/download/DownloadStatus.kt',
    'download/src/main/java/cn/olfact/download/DownloadKind.kt'
) | ForEach-Object { Join-Path $repoRoot $_ }
$testRoot = Join-Path $repoRoot 'app/src/test/java'
$tests = @(Get-ChildItem $testRoot -Recurse -Filter '*Test.kt' | Where-Object Name -NotIn @('SettingItemClickTest.kt', 'SurfaceCacheTest.kt'))
$sources += @($tests | ForEach-Object FullName)
$sources += $contractOutput
$sources += @(Get-ChildItem (Join-Path $PSScriptRoot 'support-data') -Filter '*.kt' | ForEach-Object FullName)
$extraTests = @(Get-ChildItem (Join-Path $PSScriptRoot 'tests-data') -Filter '*Test.kt')
$sources += @($extraTests | ForEach-Object FullName)
$classpath = $dependencies -join [IO.Path]::PathSeparator
$classes = Join-Path $out 'tests.jar'
& $java '-cp' (Join-Path $kotlin '*') 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler' `
    '-no-stdlib' '-no-reflect' '-jvm-target' '17' '-classpath' $classpath '-d' $classes @sources
if ($LASTEXITCODE -ne 0) { throw "App data compilation failed: $LASTEXITCODE" }
$testClasses = @($tests | ForEach-Object {
    $_.FullName.Substring($testRoot.Length + 1).Replace('\', '.').Replace('/', '.').Replace('.kt', '')
})
$testClasses += @($extraTests | ForEach-Object {
    $packageLine = [regex]::Match([IO.File]::ReadAllText($_.FullName), '(?m)^package ([\w.]+)').Groups[1].Value
    if (!$packageLine) { throw "Missing package in $($_.Name)" }
    "$packageLine.$($_.BaseName)"
})
& $java '-cp' ($classes + [IO.Path]::PathSeparator + $classpath) 'org.junit.runner.JUnitCore' @testClasses
if ($LASTEXITCODE -ne 0) { throw "App data JUnit regressions failed: $LASTEXITCODE" }
