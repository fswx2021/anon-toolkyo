param([string]$OutputFile)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if (!$OutputFile) {
    $OutputFile = Join-Path $repo 'shengjian-reader-workbench.jar'
}
$OutputFile = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputFile)
$javac = (Get-Command javac -CommandType Application | Select-Object -First 1).Source
$jarTool = (Get-Command jar -CommandType Application | Select-Object -First 1).Source
Add-Type -AssemblyName System.IO.Compression.FileSystem

$shared = Join-Path $repo 'app/src/main/java/com/shengjian/player'
$sharedFiles = @('ReaderSources.java', 'ReaderEngine.java', 'ReaderRuleSupport.java', 'ReaderCache.java', 'ReaderLocalBooks.java',
    'BackupArchive.java', 'AnimeSource.java', 'AnimeEngine.java', 'AnimeSchedule.java', 'AnimeDanmaku.java',
    'AnimeDanmakuCommunity.java',
    'ReaderAggregation.java', 'ReaderRepositoryIndex.java', 'ReaderSearchQueue.java', 'ReaderSearchRanking.java',
    'ReaderSourceHealth.java', 'ReaderSourceDiagnostics.java', 'ReaderWorkMatcher.java', 'NovelTextTools.java', 'YiciyuanRules.java', 'YiciyuanScript.java',
    'PpcatRules.java', 'PpcatStore.java',
    'Lyrics.java', 'LinkResolver.java', 'MusicCollections.java', 'MusicImportTask.java') |
    ForEach-Object { Join-Path $shared $_ }
$desktopFiles = (Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src/main/java') -Recurse -Filter '*.java').FullName
$libraries = @(Get-ChildItem -LiteralPath (Join-Path $repo 'app/libs'), (Join-Path $PSScriptRoot 'lib') -Filter '*.jar')
$platformPaths = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $repo 'build/android-sdk')) |
    Where-Object { $_ } | ForEach-Object { Join-Path $_ 'platforms' } |
    Where-Object { Test-Path -LiteralPath $_ -PathType Container }
$androidJar = $platformPaths | ForEach-Object { Get-ChildItem -LiteralPath $_ -Directory } |
    Where-Object { $_.Name -match '^android-\d+$' } |
    Sort-Object { [int]($_.Name -replace '^android-', '') } -Descending |
    ForEach-Object { Join-Path $_.FullName 'android.jar' } |
    Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
if (!$androidJar) { throw 'Android SDK platform not found. Set ANDROID_HOME or install a platform under build/android-sdk.' }
$classpath = ($libraries.FullName + $androidJar) -join [IO.Path]::PathSeparator
$build = New-Item -ItemType Directory -Force -Path (Join-Path $PSScriptRoot 'build')
$stage = New-Item -ItemType Directory -Path (Join-Path $build.FullName ('stage-' + [guid]::NewGuid().ToString('N')))
$prefix = $stage.FullName + [IO.Path]::DirectorySeparatorChar

try {
    & $javac --release 17 -encoding UTF-8 -cp $classpath -d $stage.FullName @sharedFiles @desktopFiles
    if ($LASTEXITCODE) { throw 'Java compilation failed' }

    foreach ($library in $libraries) {
        $archive = [IO.Compression.ZipFile]::OpenRead($library.FullName)
        try {
            foreach ($entry in $archive.Entries) {
                $name = $entry.FullName
                if (!$entry.Name -or $name -match '(?i)(^|/)module-info\.class$|^META-INF/(MANIFEST\.MF|maven/|versions/|[^/]+\.(SF|RSA|DSA)$)') {
                    continue
                }
                if ($name -match '(?i)^META-INF/(licen[cs]e|notice|copying)') {
                    $name = "META-INF/dependencies/$($library.BaseName)/$($entry.Name)"
                }
                $destination = [IO.Path]::GetFullPath((Join-Path $stage.FullName $name))
                if (!$destination.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
                    throw "Unexpected archive path in $($library.Name)"
                }
                [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination))
                if ($name.StartsWith('META-INF/services/') -and [IO.File]::Exists($destination)) {
                    $reader = [IO.StreamReader]::new($entry.Open())
                    try { [IO.File]::AppendAllText($destination, "`n" + $reader.ReadToEnd()) }
                    finally { $reader.Dispose() }
                } else {
                    [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $destination)
                }
            }
        } finally {
            $archive.Dispose()
        }
    }

    Copy-Item -LiteralPath (Join-Path $repo 'app/src/main/assets/reader-samples') -Destination $stage.FullName -Recurse
    Copy-Item -LiteralPath (Join-Path $repo 'app/src/main/assets/reader-sources') -Destination $stage.FullName -Recurse
    $licenses = New-Item -ItemType Directory -Force -Path (Join-Path $stage.FullName 'licenses')
    Copy-Item -LiteralPath (Join-Path $repo 'app/src/main/assets/licenses/ppcat-store-GPL-3.0.txt') -Destination $licenses.FullName
    $stagedJar = Join-Path $build.FullName ($stage.Name + '.jar')
    & $jarTool --create --file $stagedJar --main-class com.shengjian.player.ReaderWorkbench -C $stage.FullName .
    if ($LASTEXITCODE) { throw 'JAR packaging failed' }
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($OutputFile))
    Move-Item -LiteralPath $stagedJar -Destination $OutputFile -Force
    Write-Output $OutputFile
} finally {
    $resolvedStage = (Resolve-Path -LiteralPath $stage.FullName).Path
    if ((Split-Path -Parent $resolvedStage) -eq (Resolve-Path -LiteralPath $build.FullName).Path) {
        Remove-Item -LiteralPath $resolvedStage -Force -Recurse
    }
}
