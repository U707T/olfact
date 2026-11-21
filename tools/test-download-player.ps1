param(
    [string]$JavaHome = $env:OLFACT_JAVA_HOME,
    [string]$KotlinHome = $env:OLFACT_KOTLIN_HOME,
    [string]$LibraryDir = $env:OLFACT_TEST_LIB_DIR,
    [switch]$ResolveDependencies,
    [switch]$CheckRemuxTypes
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$workspace = Split-Path $repo -Parent
if (!$JavaHome) { $JavaHome = Join-Path $workspace '.panelfm-tools/jdk21/jdk-21.0.13.1+2' }
if (!$KotlinHome) { $KotlinHome = Join-Path $workspace '.panelfm-tools/kotlin/kotlinc' }
if (!$LibraryDir) { $LibraryDir = Join-Path $workspace '.tools/olfact/lib' }
$java = Join-Path $JavaHome 'bin/java.exe'
$compiler = Join-Path $KotlinHome 'lib/kotlin-compiler.jar'
foreach ($required in @($java, $compiler)) {
    if (!(Test-Path $required)) { throw "Missing tool: $required. Set OLFACT_JAVA_HOME / OLFACT_KOTLIN_HOME." }
}
$catalog = Get-Content (Join-Path $repo 'gradle/libs.versions.toml') -Raw
function Version([string]$key) {
    $match = [regex]::Match($catalog, ('(?m)^' + [regex]::Escape($key) + '\s*=\s*"([^"]+)"'))
    if (!$match.Success) { throw "Missing version: $key" }
    return $match.Groups[1].Value
}
$okhttp = Version 'okhttp'
$serialization = Version 'serialization'
$coroutines = Version 'coroutines'
$junit = Version 'junit'
# Okio follows okhttp-jvm's POM (5.5.1 -> 3.18.1), rather than an Android SDK dependency.
$artifacts = @(
    "com/squareup/okhttp3/okhttp-jvm/$okhttp/okhttp-jvm-$okhttp.jar",
    "com/squareup/okhttp3/mockwebserver/$okhttp/mockwebserver-$okhttp.jar",
    "com/squareup/okhttp3/mockwebserver3/$okhttp/mockwebserver3-$okhttp.jar",
    'com/squareup/okio/okio-jvm/3.18.1/okio-jvm-3.18.1.jar',
    "org/jetbrains/kotlinx/kotlinx-serialization-json-jvm/$serialization/kotlinx-serialization-json-jvm-$serialization.jar",
    "org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/$serialization/kotlinx-serialization-core-jvm-$serialization.jar",
    "org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/$coroutines/kotlinx-coroutines-core-jvm-$coroutines.jar",
    "junit/junit/$junit/junit-$junit.jar",
    'org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar'
)
if ($ResolveDependencies) {
    New-Item -ItemType Directory -Force -Path $LibraryDir | Out-Null
    # Python uses its verified TLS stack; Windows Schannel may lack sandbox credentials.
    $downloader = @'
import pathlib,sys,urllib.request
cache=pathlib.Path(sys.argv[1])
for artifact in sys.argv[2:]:
    target=cache/artifact.rsplit('/',1)[1]
    if not target.exists():
        with urllib.request.urlopen('https://repo.maven.apache.org/maven2/'+artifact,timeout=30) as response:
            data=response.read()
        target.write_bytes(data)
'@
    & python -c $downloader $LibraryDir @artifacts
    if ($LASTEXITCODE -ne 0) { throw 'Dependency download failed.' }
}
$classpath = @((Join-Path $KotlinHome 'lib/kotlin-stdlib.jar'))
foreach ($artifact in $artifacts) {
    $path = Join-Path $LibraryDir (Split-Path $artifact -Leaf)
    if (!(Test-Path $path)) { throw "Missing dependency: $path. Run with -ResolveDependencies once." }
    $classpath += $path
}
$out = Join-Path $repo ('download/build/pure-jvm/' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $out | Out-Null
$main = Join-Path $repo 'download/src/main/java/cn/olfact/download'
$sources = @('ChunkDownloader.kt','ChunkMerger.kt','ChunkPlanner.kt','DownloadConfig.kt','DownloadKind.kt',
    'DownloadPaths.kt','DownloadRecord.kt','DownloadSpec.kt','DownloadStatus.kt','MediaProbe.kt',
    'm3u8/AdBreakFilter.kt','m3u8/Aes128Decryptor.kt','m3u8/M3u8Downloader.kt','m3u8/M3u8Merger.kt',
    'm3u8/M3u8Model.kt','m3u8/M3u8Parser.kt','m3u8/SampleData.kt','queue/DownloadQueue.kt','queue/DownloadRecordCodec.kt',
    'queue/DownloadStore.kt','util/Files.kt','util/Http.kt','util/SpeedMeter.kt') | ForEach-Object { Join-Path $main $_ }
$sources += Join-Path $repo 'core/src/main/java/cn/olfact/core/net/AppDns.kt'
$sources += Join-Path $repo 'core/src/main/java/cn/olfact/core/network/HttpResponse.kt'
$player = Join-Path $repo 'player/src/main/java/cn/olfact/player'
$sources += @('MediaKind.kt','PlaybackSource.kt','PlaybackOptions.kt','PlaybackSession.kt','PlayerPrefs.kt','PlayerMath.kt') | ForEach-Object { Join-Path $player $_ }
$sources += @(Get-ChildItem (Join-Path $PSScriptRoot 'download-player-support') -Filter '*.kt' | ForEach-Object FullName)
$tests = @(Get-ChildItem (Join-Path $repo 'download/src/test') -Filter '*.kt' -Recurse) + @(Get-ChildItem (Join-Path $repo 'player/src/test') -Filter '*.kt' -Recurse)
$sources += $tests | ForEach-Object FullName
$jar = Join-Path $out 'tests.jar'
& $java '-cp' (Join-Path $KotlinHome 'lib/*') 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler' '-no-stdlib' '-no-reflect' '-jvm-target' '17' '-classpath' ($classpath -join [IO.Path]::PathSeparator) '-d' $jar @sources
if ($LASTEXITCODE -ne 0) { throw 'Kotlin compilation failed.' }
$testClasses = $tests | ForEach-Object {
    $text = Get-Content $_.FullName -Raw
    $package = [regex]::Match($text, '(?m)^package\s+([^\s]+)').Groups[1].Value
    "$package.$($_.BaseName)"
}
& $java '-cp' ((@($jar) + $classpath) -join [IO.Path]::PathSeparator) 'org.junit.runner.JUnitCore' @testClasses
if ($LASTEXITCODE -ne 0) { throw 'JVM regression tests failed.' }
if ($CheckRemuxTypes) {
    $media3 = Version 'media3'
    $mediaDir = Join-Path (Split-Path $LibraryDir -Parent) 'media3'
    if ($ResolveDependencies) {
        $mediaDownloader = @'
import pathlib,sys,urllib.request,zipfile
cache=pathlib.Path(sys.argv[1]); cache.mkdir(parents=True,exist_ok=True)
version=sys.argv[2]
for name in ['media3-common','media3-extractor','media3-muxer']:
    target=cache/f'{name}-{version}.jar'
    if not target.exists():
        aar=cache/f'{name}-{version}.aar'
        with urllib.request.urlopen(f'https://dl.google.com/dl/android/maven2/androidx/media3/{name}/{version}/{name}-{version}.aar',timeout=30) as response:
            aar.write_bytes(response.read())
        with zipfile.ZipFile(aar) as archive: target.write_bytes(archive.read('classes.jar'))
'@
        & python -c $mediaDownloader $mediaDir $media3
        if ($LASTEXITCODE -ne 0) { throw 'Media3 dependency download failed.' }
    }
    $mediaClasspath = @((Join-Path $KotlinHome 'lib/kotlin-stdlib.jar'))
    foreach ($name in @('media3-common','media3-extractor','media3-muxer')) {
        $path = Join-Path $mediaDir "$name-$media3.jar"
        if (!(Test-Path $path)) { throw "Missing Media3 artifact: $path. Add -ResolveDependencies once." }
        $mediaClasspath += $path
    }
    $annotation = Join-Path $out 'OptIn.kt'
    Set-Content $annotation 'package androidx.annotation; import kotlin.reflect.KClass; annotation class OptIn(vararg val markerClass: KClass<*>)' -Encoding utf8
    & $java '-cp' (Join-Path $KotlinHome 'lib/*') 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler' '-no-stdlib' '-no-reflect' '-jvm-target' '17' '-classpath' ($mediaClasspath -join [IO.Path]::PathSeparator) '-d' (Join-Path $out 'remux-types.jar') $annotation (Join-Path $main 'm3u8/SampleData.kt') (Join-Path $main 'm3u8/TsRemuxer.kt')
    if ($LASTEXITCODE -ne 0) { throw 'Real Media3 type checking failed.' }
    Write-Output "TsRemuxer type-check passed against actual Media3 $media3 (OptIn annotation only is substituted); no media runtime executed."
}
Write-Output 'Scope: real file/network/parser/queue logic; Android, DownloadManager, notifications and native remux are substituted. Full Gradle/Android and device checks remain required.'
