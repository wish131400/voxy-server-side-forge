param(
    [string]$NeoRoot = 'C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1',
    [string]$ForgeRoot = 'C:/Users/Administrator/Desktop/voxyserverside'
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

function EntryHash($jar, [string]$name) {
    $entry = $jar.GetEntry($name)
    if ($null -eq $entry) { throw "Missing packaged entry: $name" }
    $stream = $entry.Open()
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($algorithm.ComputeHash($stream))).Replace('-', '').ToLowerInvariant() }
    finally { $stream.Dispose(); $algorithm.Dispose() }
}
function EntryText($jar, [string]$name) {
    $stream = $jar.GetEntry($name).Open()
    $buffer = [IO.MemoryStream]::new()
    try { $stream.CopyTo($buffer); return [Text.Encoding]::UTF8.GetString($buffer.ToArray()) }
    finally { $stream.Dispose(); $buffer.Dispose() }
}

$artifacts = @()
foreach ($platform in @(
    @{ root=$NeoRoot; name='neoforge-1.21.1'; major=65 },
    @{ root=$ForgeRoot; name='forge-1.20.1'; major=61 }
)) {
    $root = $platform.root
    $fileName = 'vss-0.3.5-' + $platform.name + '.jar'
    $artifactPath = Join-Path $root ('lib/visual-handoff-20261017/' + $fileName)
    $previousPath = Join-Path $root ('lib/effect-fog-20261016/' + $fileName)
    $jar = [IO.Compression.ZipFile]::OpenRead($artifactPath)
    $previous = [IO.Compression.ZipFile]::OpenRead($previousPath)
    try {
        $names = @($jar.Entries | ForEach-Object FullName)
        $mixins = ConvertFrom-Json (EntryText $jar 'vss.compat.mixins.json')
        $removed = @('dev/xantha/vss/client/prediction/PredictionVoxyBoundaryBridge.class',
            'dev/xantha/vss/mixin/voxy/VoxyBoundaryTerrainMixin.class')
        foreach ($name in $removed) { if ($names -contains $name) { throw "Retired boundary clip remains: $name" } }
        if ($mixins.client -contains 'voxy.VoxyBoundaryTerrainMixin') { throw 'Retired mixin is registered' }
        foreach ($name in @('client.PredictionFogCaptureMixin', 'voxy.PredictionFogShaderMixin')) {
            if ($mixins.client -notcontains $name) { throw "Missing required mixin registration: $name" }
        }
        foreach ($name in @('dev/xantha/vss/mixin/client/PredictionFogCaptureMixin.class',
            'dev/xantha/vss/client/prediction/PredictionFogBridge.class')) {
            if ((EntryHash $jar $name) -ne (EntryHash $previous $name)) { throw "Previous fog fix changed: $name" }
        }
        $natives = @($names | Where-Object { $_ -match '^META-INF/vss-natives/.*\.(dll|so|dylib)$' })
        if ($natives.Count -ne 5) { throw 'Missing native backend binaries' }
        foreach ($name in $natives) {
            if ((EntryHash $jar $name) -ne (EntryHash $previous $name)) { throw "Native backend changed: $name" }
        }
        foreach ($class in @('PredictionTerrainProgram', 'PredictionRenderTarget')) {
            $name = 'dev/xantha/vss/client/prediction/' + $class + '.class'
            if (!(EntryText $jar $name).Contains('vssResolveVoxyDepth')) { throw "Shared depth rule missing: $class" }
        }
        $stream = $jar.GetEntry('dev/xantha/vss/client/prediction/PredictionVoxyDepth.class').Open()
        try {
            $header = New-Object byte[] 8
            if ($stream.Read($header,0,8) -ne 8) { throw 'Truncated class header' }
            $major = [int]$header[6] * 256 + [int]$header[7]
            if ($major -ne $platform.major) { throw "Incorrect Java class version: $major" }
        } finally { $stream.Dispose() }
        if (@($names | Where-Object { $_ -match 'GpuTest\.class$|^AlgorithmBootstrap\.class$' }).Count) { throw 'Test fixture packaged' }

        $suites = @()
        foreach ($group in @('implementation-cpu','visual-repro-gpu','visual-regression-gpu')) {
            $tests = 0; $failures = 0; $skipped = 0
            $files = @(Get-ChildItem (Join-Path $root ('build-visual-handoff-20261017/test-results/' + $group)) -Filter 'TEST-*.xml')
            if ($files.Count -eq 0) { throw "No test reports: $group" }
            foreach ($file in $files) {
                $xml = [xml][IO.File]::ReadAllText($file.FullName)
                $tests += [int]$xml.testsuite.tests
                $failures += [int]$xml.testsuite.failures + [int]$xml.testsuite.errors
                $skipped += [int]$xml.testsuite.skipped
            }
            if ($failures -or $skipped) { throw "Tests failed or were skipped in $group" }
            $suites += [ordered]@{ group=$group; tests=$tests; failures=$failures; skipped=$skipped }
        }
        $artifacts += [ordered]@{
            platform=$platform.name; path=$artifactPath
            sha256=(Get-FileHash -LiteralPath $artifactPath -Algorithm SHA256).Hash.ToLowerInvariant()
            bytes=(Get-Item -LiteralPath $artifactPath).Length; javaMajor=$major
            boundaryClipRemoved=$true; fogFixIdentical=$true; nativeBackendsIdentical=$true
            sharedDepthRulePackaged=$true; testFixturesAbsent=$true; testSuites=$suites
        }
    } finally { $jar.Dispose(); $previous.Dispose() }
}
$shared = @('src/main/java/dev/xantha/vss/client/prediction/PredictionTerrainProgram.java',
    'src/main/java/dev/xantha/vss/client/prediction/PredictionRenderTarget.java',
    'src/main/java/dev/xantha/vss/client/prediction/PredictionVoxyDepth.java',
    'src/main/java/dev/xantha/vss/mixin/voxy/PredictionFogShaderMixin.java',
    'src/test/java/dev/xantha/vss/client/prediction/PredictionVoxySurfaceGpuTest.java',
    'src/test/java/dev/xantha/vss/client/prediction/PredictionVegetationDepthGpuTest.java')
foreach ($file in $shared) {
    if ((Get-FileHash -LiteralPath (Join-Path $NeoRoot $file)).Hash -ne
        (Get-FileHash -LiteralPath (Join-Path $ForgeRoot $file)).Hash) { throw "Unsynchronized fix: $file" }
}
$installed = 'F:/NovaEngineering-World整合包/.minecraft/versions/1.21.1-NeoForge_21.1.249/mods/vss-0.3.5-neoforge-1.21.1.jar'
$installedHash = (Get-FileHash -LiteralPath $installed -Algorithm SHA256).Hash.ToLowerInvariant()
if ($installedHash -ne 'eadf128b63f12225bcdbebef0907da9ea54600528387427328351e3241faa73d') {
    throw 'The active instance JAR changed; inspect before reporting its state'
}
$result = [ordered]@{ sharedSourcesIdentical=$true; installedJarUnchanged=$true
    installedSha256=$installedHash; artifacts=$artifacts }
$json = $result | ConvertTo-Json -Depth 8
foreach ($root in @($NeoRoot,$ForgeRoot)) {
    [IO.File]::WriteAllText((Join-Path $root 'tools/prediction/visual-handoff-20261017/artifact-audit.json'), $json,
        [Text.UTF8Encoding]::new($false))
}
$json
