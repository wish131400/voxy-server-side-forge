param(
    [Parameter(Mandatory = $true)][string]$Repository,
    [Parameter(Mandatory = $true)][string]$JarName,
    [Parameter(Mandatory = $true)][string]$VoxyJar,
    [Parameter(Mandatory = $true)][string]$LegacyVoxyJar
)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path -LiteralPath $Repository).Path
$taskOutput = Join-Path $taskRoot 'lib/ingest-retained-fix-20261018'
$taskJar = Join-Path $taskOutput $JarName
$taskReports = Join-Path $taskRoot 'build/test-results/ingest-retained'
$taskCounts = [ordered]@{ tests = 0; failures = 0; errors = 0; skipped = 0 }
$taskXmlFiles = @(Get-ChildItem -LiteralPath $taskReports -Filter 'TEST-*.xml' -File)
foreach ($taskXmlFile in $taskXmlFiles) {
    $taskXml = [System.Xml.XmlDocument]::new()
    try { $taskXml.Load($taskXmlFile.FullName) }
    catch { throw "Invalid JUnit report '$($taskXmlFile.FullName)': $($_.Exception.Message)" }
    foreach ($taskKey in @('tests', 'failures', 'errors', 'skipped')) {
        $taskCounts[$taskKey] += [int]$taskXml.testsuite.GetAttribute($taskKey)
    }
}
if ($taskCounts.tests -ne 54 -or $taskCounts.failures -ne 0 -or $taskCounts.errors -ne 0 -or $taskCounts.skipped -ne 0) {
    throw "Focused suite did not complete: $($taskCounts | ConvertTo-Json -Compress)"
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskArchive = [IO.Compression.ZipFile]::OpenRead($taskJar)
try {
    $taskRequired = @(
        'dev/xantha/vss/compat/VoxyIngestTracker.class',
        'dev/xantha/vss/mixin/voxy/StrictVoxyIngestPlugin.class',
        'dev/xantha/vss/mixin/voxy/StrictVoxyIngestMixin.class',
        'dev/xantha/vss/mixin/voxy/ModelFactoryFluidBakeOrderMixin.class',
        'vss.strict.mixins.json'
    )
    foreach ($taskEntryName in $taskRequired) {
        if ($null -eq $taskArchive.GetEntry($taskEntryName)) { throw "Missing JAR entry: $taskEntryName" }
    }
    $taskReader = [IO.StreamReader]::new($taskArchive.GetEntry('vss.strict.mixins.json').Open())
    try { $taskConfiguration = $taskReader.ReadToEnd() | ConvertFrom-Json }
    finally { $taskReader.Dispose() }
    if ($taskConfiguration.plugin -ne 'dev.xantha.vss.mixin.voxy.StrictVoxyIngestPlugin') {
        throw 'Packaged strict configuration does not enable the verified ingest plugin'
    }
    $taskFluidEntry = $taskArchive.GetEntry('dev/xantha/vss/mixin/voxy/ModelFactoryFluidBakeOrderMixin.class')
    $taskFluidStream = $taskFluidEntry.Open()
    $taskFluidBytes = [IO.MemoryStream]::new()
    try { $taskFluidStream.CopyTo($taskFluidBytes); $taskFluidText = [Text.Encoding]::Latin1.GetString($taskFluidBytes.ToArray()) }
    finally { $taskFluidStream.Dispose(); $taskFluidBytes.Dispose() }
    if (!$taskFluidText.Contains('blockBakeQueue') -or !$taskFluidText.Contains('bakeQueue')) {
        throw 'Packaged fluid compatibility does not contain both verified field names'
    }
    if (@($taskArchive.Entries | Where-Object { $_.FullName -match '(Test|Fixture|Harness)\.class$' }).Count -gt 0) {
        throw 'Test classes leaked into the production JAR'
    }
} finally { $taskArchive.Dispose() }

$taskHash = (Get-FileHash -LiteralPath $taskJar -Algorithm SHA256).Hash
$taskInfo = [ordered]@{
    repository = $taskRoot
    jar = $JarName
    bytes = (Get-Item -LiteralPath $taskJar).Length
    sha256 = $taskHash
    upstreamPr = 'https://github.com/wish131400/voxy-server-side-neoforge/pull/12'
    upstreamHead = '36b2087906dc43b9452e3e487082dbeac8b7c192'
    issue = 'https://github.com/wish131400/voxy-server-side-neoforge/issues/13'
    focusedTests = $taskCounts
    realSpongeApplication = $true
    asmBasicVerifier = $true
    clientWorldSmokeTest = $false
    inputs = @(
        [ordered]@{ name = (Split-Path -Leaf $VoxyJar); sha256 = (Get-FileHash -LiteralPath $VoxyJar -Algorithm SHA256).Hash },
        [ordered]@{ name = (Split-Path -Leaf $LegacyVoxyJar); sha256 = (Get-FileHash -LiteralPath $LegacyVoxyJar -Algorithm SHA256).Hash }
    )
    exactReportedVoxy0414BinaryTested = $false
}
$taskInfo | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $taskOutput 'BUILD-INFO.json') -Encoding utf8
"$taskHash  $JarName" | Set-Content -LiteralPath (Join-Path $taskOutput 'SHA256SUMS.txt') -Encoding ascii
$taskSavedReports = Join-Path $taskOutput 'test-results'
New-Item -ItemType Directory -Path $taskSavedReports -Force | Out-Null
foreach ($taskXmlFile in $taskXmlFiles) { Copy-Item -LiteralPath $taskXmlFile.FullName -Destination $taskSavedReports }
$taskInfo | ConvertTo-Json -Depth 6
