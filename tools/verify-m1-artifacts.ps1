# =====================================================================
# tools/verify-m1-artifacts.ps1
# M1-T11 Step 1 - bounded, read-only M1 artifact verifier (fail-closed).
#
# Input universe (nothing else is resolved, ever):
#   - -ProjectRoot (repo files under it: manifests, rule XML, catalog TOML,
#     build files, app/build + benchmark/build outputs)
#   - SDK location parsed from local.properties (aapt2 build-tools 36.0.0)
#   - exact package names / component names / versions frozen below
# Any parsing need outside this universe must fail, not guess.
#
# Read-only guarantee: this script performs no file system, registry, or
# environment writes; it writes nothing except stdout and its exit code.
#   exit 0 = every family PASS; exit 1 = at least one FAIL; exit 2 = fatal
#   input error (bad root / missing SDK).
#
# Families (plan M1-T11 Step 1; spec criteria 15/17/18 + 12.3):
#   1 network-permission-policy   source/merged/final manifests: no INTERNET,
#                                 no network-class permission, no network
#                                 security config. Negative controls: INTERNET
#                                 and ACCESS_NETWORK_STATE.
#   2 exported-components         aapt2 xmltree enumeration vs frozen table
#                                 (coordinator-corrected): debug/release carry
#                                 MainActivity plus the standard toolchain
#                                 exports (PreviewActivity/ComponentActivity in
#                                 debug, profileinstaller receiver everywhere);
#                                 the benchmark variant additionally exports
#                                 M1StartupFixtureActivity and the P6
#                                 TimelineBenchmarkActivity. Whitelisted
#                                 receivers must carry their pinned
#                                 android:permission guard (ProfileInstallReceiver
#                                 = android.permission.DUMP) or the check FAILs;
#                                 any other exported component still FAILs.
#   3 package-identity            badging package name per variant, exact match.
#   4 allow-backup                allowBackup="false" in main source + release
#                                 (merged, final) + benchmark (merged, final).
#   5 backup-nine-domains         data_extraction_rules.xml (cloud-backup +
#                                 device-transfer) and backup_rules.xml
#                                 (full-backup-content) exclude exactly the
#                                 nine approved Android domains with path=".",
#                                 in source and in packaged rule resources.
#   6 icon-starting-theme         adaptive launcher icon (foreground/background/
#                                 monochrome), Theme.Record starting-window
#                                 items, values-night continuity, manifest icon/
#                                 roundIcon/theme references, packaged resources.
#   7 release-test-fixtures       T08 debug-only host + benchmark-only +
#                                 androidTest class tokens must not appear in
#                                 the release APK (dex/manifest/arsc scan) nor
#                                 in the release merged/packaged manifest text.
#   8 tracking-sdk-strings        bounded tracking/analytics/ads token scan of
#                                 the release APK dex/manifest/arsc.
#   9 dependency-versions         frozen T01-approved catalog/versions vs
#                                 gradle/libs.versions.toml, manifest-merger
#                                 reports, APK META-INF *.version entries, and
#                                 frozen benchmark build-file coordinates.
# =====================================================================
param(
    [Parameter(Mandatory = $true)]
    [string]$ProjectRoot
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------
# Frozen contract constants (do not edit without reapproval)
# ---------------------------------------------------------------------
$script:SdkBuildToolsVersion = '36.0.0'
$script:NetworkPermissionPattern = 'INTERNET|NETWORK|WIFI|SOCKET|HTTP'

$script:NineDomains = @(
    'root', 'file', 'database', 'sharedpref', 'external',
    'device_root', 'device_file', 'device_database', 'device_sharedpref'
)

$script:LauncherComponent = 'com.paherdin.record.MainActivity'

# Frozen exported-component table (dispatch M1-T11 fail item 2, corrected by
# the coordinator's component-origin adjudication: ProfileInstallReceiver is
# the androidx profileinstaller toolchain receiver, PreviewActivity/
# ComponentActivity are the repo's own standard Compose debug dependencies,
# TimelineBenchmarkActivity is the T01-era P6 harness activity of the benchmark
# variant). Hardened semantics: an entry with a Guard requirement must be found
# carrying exactly that android:permission attribute; a missing or different
# guard FAILs. Every other exported component still FAILs (fail-closed).
$script:ExpectedExported = @{
    'app-debug' = @(
        @{ Name = 'com.paherdin.record.MainActivity'; Guard = $null },
        @{ Name = 'androidx.compose.ui.tooling.PreviewActivity'; Guard = $null },
        @{ Name = 'androidx.activity.ComponentActivity'; Guard = $null },
        @{ Name = 'androidx.profileinstaller.ProfileInstallReceiver'; Guard = 'android.permission.DUMP' }
    )
    'app-release' = @(
        @{ Name = 'com.paherdin.record.MainActivity'; Guard = $null },
        @{ Name = 'androidx.profileinstaller.ProfileInstallReceiver'; Guard = 'android.permission.DUMP' }
    )
    'app-benchmark' = @(
        @{ Name = 'com.paherdin.record.MainActivity'; Guard = $null },
        @{ Name = 'com.paherdin.record.benchmark.M1StartupFixtureActivity'; Guard = $null },
        @{ Name = 'com.paherdin.record.benchmark.TimelineBenchmarkActivity'; Guard = $null },
        @{ Name = 'androidx.profileinstaller.ProfileInstallReceiver'; Guard = 'android.permission.DUMP' }
    )
}

# Frozen package identity table (dispatch M1-T11 fail item 3).
$script:ExpectedPackages = @{
    'app-debug'       = 'com.paherdin.record'
    'app-release'     = 'com.paherdin.record'
    'app-benchmark'   = 'com.paherdin.record.benchmark'
    'app-androidTest' = 'com.paherdin.record.test'
    'benchmark-tool'  = 'com.paherdin.record.benchmark.test'
}

# Frozen artifact locations relative to the project root.
$script:ApkRelPaths = @{
    'app-debug'       = 'app/build/outputs/apk/debug/app-debug.apk'
    'app-release'     = 'app/build/outputs/apk/release/app-release-unsigned.apk'
    'app-benchmark'   = 'app/build/outputs/apk/benchmark/app-benchmark.apk'
    'app-androidTest' = 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
    'benchmark-tool'  = 'benchmark/build/outputs/apk/benchmark/benchmark-benchmark.apk'
}
$script:MergedManifestTexts = @{
    'debug'     = 'app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml'
    'release'   = 'app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml'
    'benchmark' = 'app/build/intermediates/merged_manifests/benchmark/processBenchmarkManifest/AndroidManifest.xml'
}
$script:PackagedManifestTexts = @{
    'release'   = 'app/build/intermediates/packaged_manifests/release/processReleaseManifestForPackage/AndroidManifest.xml'
    'benchmark' = 'app/build/intermediates/packaged_manifests/benchmark/processBenchmarkManifestForPackage/AndroidManifest.xml'
}

# T01-approved frozen dependency baseline (spec section 10 + section 2).
$script:FrozenTomlVersions = @{
    'agp'                = '9.3.0'
    'kotlin'             = '2.2.10'
    'composeBom'         = '2026.06.00'
    'activity'           = '1.13.0'
    'navigation'         = '2.9.8'
    'serialization'      = '1.9.0'
    'lifecycle'          = '2.10.0'
    'room'               = '2.8.4'
    'ksp'                = '2.3.9'
    'androidxTestRunner' = '1.6.2'
    'androidxTestJunit'  = '1.2.1'
    'junit'              = '4.13.2'
    'androidxBenchmark'  = '1.4.1'
    'profileinstaller'   = '1.4.1'
}

$script:FrozenTomlLibraries = @{
    'compose-bom'                     = 'androidx.compose:compose-bom'
    'activity-compose'                = 'androidx.activity:activity-compose'
    'navigation-compose'              = 'androidx.navigation:navigation-compose'
    'navigation-testing'              = 'androidx.navigation:navigation-testing'
    'kotlinx-serialization-json'      = 'org.jetbrains.kotlinx:kotlinx-serialization-json'
    'lifecycle-runtime'               = 'androidx.lifecycle:lifecycle-runtime'
    'lifecycle-runtime-compose'       = 'androidx.lifecycle:lifecycle-runtime-compose'
    'compose-ui'                      = 'androidx.compose.ui:ui'
    'compose-foundation'              = 'androidx.compose.foundation:foundation'
    'compose-material3'               = 'androidx.compose.material3:material3'
    'compose-ui-tooling-preview'      = 'androidx.compose.ui:ui-tooling-preview'
    'compose-ui-tooling'              = 'androidx.compose.ui:ui-tooling'
    'compose-ui-test-junit4'          = 'androidx.compose.ui:ui-test-junit4'
    'compose-ui-test-manifest'        = 'androidx.compose.ui:ui-test-manifest'
    'room-runtime'                    = 'androidx.room:room-runtime'
    'room-compiler'                   = 'androidx.room:room-compiler'
    'kotlin-compiler-embeddable'      = 'org.jetbrains.kotlin:kotlin-compiler-embeddable'
    'kotlin-metadata-jvm'             = 'org.jetbrains.kotlin:kotlin-metadata-jvm'
    'androidx-test-runner'            = 'androidx.test:runner'
    'androidx-test-ext-junit'         = 'androidx.test.ext:junit'
    'junit'                           = 'junit:junit'
    'androidx-benchmark-macro-junit4' = 'androidx.benchmark:benchmark-macro-junit4'
    'androidx-profileinstaller'       = 'androidx.profileinstaller:profileinstaller'
}

$script:FrozenTomlPlugins = @{
    'android-application'  = @('com.android.application', 'agp')
    'android-library'      = @('com.android.library', 'agp')
    'android-test'         = @('com.android.test', 'agp')
    'compose-compiler'     = @('org.jetbrains.kotlin.plugin.compose', 'kotlin')
    'kotlin-serialization' = @('org.jetbrains.kotlin.plugin.serialization', 'kotlin')
    'kotlin-jvm'           = @('org.jetbrains.kotlin.jvm', 'kotlin')
    'ksp'                  = @('com.google.devtools.ksp', 'ksp')
    'androidx-room'        = @('androidx.room', 'room')
}

# Artifact-layer require map: normalized coordinate -> frozen version.
$script:MergerRequireRelease = @{
    'androidx.navigation:navigation-compose'       = '2.9.8'
    'androidx.lifecycle:lifecycle-runtime-compose' = '2.10.0'
    'androidx.activity:activity-compose'           = '1.13.0'
    'androidx.compose.material3:material3'         = '1.4.0'
    'androidx.compose.ui:ui'                       = '1.11.3'
    'androidx.room:room-runtime'                   = '2.8.4'
}
$script:MergerRequireBenchmark = $script:MergerRequireRelease + @{ 'androidx.profileinstaller:profileinstaller' = '1.4.1' }

# Unapproved dependency categories (spec criterion 15): no DI / tracker /
# analytics / crash / ad / network library anywhere in production artifacts.
$script:DependencyDenyTokens = @(
    'com.google.dagger', 'io.insert-koin', 'org.koin',
    'com.squareup.okhttp', 'com.squareup.retrofit', 'com.android.volley',
    'com.google.android.gms', 'com.google.firebase', 'com.google.android.play',
    'com.appsflyer', 'com.adjust', 'com.mixpanel', 'com.amplitude',
    'com.segment', 'io.sentry', 'io.fabric', 'com.crashlytics', 'com.bugsnag',
    'com.flurry', 'com.umeng', 'com.tencent.bugly', 'io.branch',
    'com.kochava', 'com.yandex.metrica', 'com.facebook.android',
    'org.robolectric', 'io.mockk',
    'junit:junit', 'androidx.test:', 'androidx.compose.ui:ui-test',
    'kotlinx-coroutines-debug'
)

# Frozen benchmark build-file dependency versions (P6-aligned baseline).
$script:FrozenBenchmarkBuildDeps = @(
    'androidx.collection:collection:1.5.0',
    'androidx.core:core:1.18.0'
)

# Test-fixture / benchmark-only class tokens that must not reach release
# (T08 debug-only host, androidTest classes, app benchmark source set).
$script:ReleaseFixtureTokens = @(
    'com.paherdin.record.M1DebugTestHostActivity',
    'com.paherdin.record.M1TestContent',
    'com.paherdin.record.M1NavigationTest',
    'com.paherdin.record.M1RetainedStateTest',
    'com.paherdin.record.M1AccessibilityTest',
    'com.paherdin.record.M1RetentionProbeContent',
    'com.paherdin.record.benchmark.M1StartupFixtureActivity',
    'com.paherdin.record.benchmark.M1StartupSentinelRecorder',
    'com.paherdin.record.benchmark.TimelineBenchmarkActivity',
    'com.paherdin.record.benchmark.TimelineRenderedPayloadRecorder',
    'com.paherdin.record.benchmark.TimelineRenderSurface',
    'com.paherdin.record.benchmark.TimelineSentinelBridge'
)

# Bounded tracking/analytics/ads token list (spec criterion 15 / fail item 8).
$script:TrackingSdkTokens = @(
    'com.google.android.gms.ads', 'com.google.android.gms.measurement',
    'com.google.firebase.analytics', 'com.google.firebase.crashlytics',
    'com.appsflyer', 'com.adjust.sdk', 'com.mixpanel', 'com.amplitude',
    'com.segment.analytics', 'io.segment.android', 'io.sentry', 'io.fabric',
    'com.crashlytics', 'com.facebook.appevents', 'com.flurry', 'com.umeng',
    'com.tencent.bugly', 'com.tencent.stat', 'com.applovin', 'com.inmobi',
    'com.mopub', 'com.unity3d.ads', 'com.vungle', 'ir.tapsell',
    'com.yandex.metrica', 'com.bugsnag', 'org.matomo', 'com.kochava',
    'io.branch', 'com.singular.sdk', 'com.datadog.android'
)

# Frozen packaged-resource expectations (spec 5.2 A/A1 light/dark background),
# stored as the RGB hex pair; source '#RRGGBB' and packaged '#AARRGGBB' forms
# are both normalized to the last six hex digits before comparison.
$script:RecordAppBackgroundLight = 'f6f2e9'
$script:RecordAppBackgroundNight = '171c1a'

$script:Families = @(
    'family-1-network-permission',
    'family-2-exported-components',
    'family-3-package-identity',
    'family-4-allow-backup',
    'family-5-backup-nine-domains',
    'family-6-icon-starting-theme',
    'family-7-release-test-fixtures',
    'family-8-tracking-sdk-strings',
    'family-9-dependency-versions'
)

$script:FamilyProblems = @{}
$script:FamilyChecks = @{}
foreach ($f in $script:Families) {
    $script:FamilyProblems[$f] = New-Object System.Collections.Generic.List[string]
    $script:FamilyChecks[$f] = 0
}

function Add-Problem {
    param([string]$Family, [string]$Text)
    $script:FamilyProblems[$Family].Add($Text) | Out-Null
}

function Add-Check {
    param([string]$Family)
    $script:FamilyChecks[$Family] = $script:FamilyChecks[$Family] + 1
}

function Join-RootPath {
    param([string]$Rel)
    return Join-Path $script:Root ($Rel -replace '/', [System.IO.Path]::DirectorySeparatorChar)
}

function Test-InputFile {
    param([string]$Family, [string]$Rel)
    Add-Check -Family $Family
    $p = Join-RootPath -Rel $Rel
    if (-not (Test-Path -LiteralPath $p -PathType Leaf)) {
        Add-Problem -Family $Family -Text ("missing artifact '" + $Rel + "' (input of " + $Family + ")")
        return $null
    }
    return $p
}

# ---------------------------------------------------------------------
# Fatal environment resolution (exit 2)
# ---------------------------------------------------------------------
$script:Root = [System.IO.Path]::GetFullPath($ProjectRoot)
if (-not (Test-Path -LiteralPath (Join-Path $script:Root 'app') -PathType Container)) {
    Write-Output ("FATAL: -ProjectRoot '" + $ProjectRoot + "' is not an M1 project root (app/ directory missing)")
    exit 2
}

$localProps = Join-Path $script:Root 'local.properties'
if (-not (Test-Path -LiteralPath $localProps -PathType Leaf)) {
    Write-Output 'FATAL: local.properties not found in project root'
    exit 2
}
$localPropsText = Get-Content -LiteralPath $localProps -Raw
$sdkMatch = [regex]::Match($localPropsText, '(?m)^\s*sdk\.dir\s*=\s*(.+?)\s*$')
if (-not $sdkMatch.Success) {
    Write-Output 'FATAL: local.properties does not define sdk.dir'
    exit 2
}
$sdkDir = $sdkMatch.Groups[1].Value.Trim()
$sdkDir = $sdkDir.Replace('\\', '\').Replace('\:', ':')
$script:Aapt2Path = Join-Path $sdkDir ("build-tools\" + $script:SdkBuildToolsVersion + "\aapt2.exe")
if (-not (Test-Path -LiteralPath $script:Aapt2Path -PathType Leaf)) {
    Write-Output ("FATAL: aapt2 not found at '" + $script:Aapt2Path + "' (SDK from local.properties, build-tools " + $script:SdkBuildToolsVersion + ")")
    exit 2
}

function Invoke-Aapt2 {
    param([string[]]$Aapt2Args)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $raw = & $script:Aapt2Path @Aapt2Args 2>&1
        $code = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $prev
    }
    $lines = @()
    foreach ($item in @($raw)) {
        if ($null -eq $item) { continue }
        if ($item -is [System.Management.Automation.ErrorRecord]) { $lines += $item.ToString() }
        else { $lines += [string]$item }
    }
    return @{ Ok = ($code -eq 0); Lines = $lines }
}

function Get-TreeLevel {
    param([string]$Line)
    $trimmed = $Line.TrimStart()
    return [int]((($Line.Length - $trimmed.Length) / 2))
}

function Get-AttrValue {
    # Extracts an attribute value from an aapt2 xmltree attribute line.
    # Handles both `A: <ns>:exported(0x...)=true` (boolean, unquoted) and
    # `A: [ns:]name(0x...)="value" (Raw: "value")` / `A: package="v" (Raw: "v")`.
    param([string[]]$AttrLines, [string]$AttrName)
    foreach ($line in $AttrLines) {
        if ($line -notmatch '^\s*A: ') { continue }
        $m = [regex]::Match($line, ('(?i)' + [regex]::Escape($AttrName) + '(?:\([^)]*\))?\s*=\s*"?([^"\r\n]*?)"?\s*(?:\(Raw:|"|$)'))
        if ($m.Success) { return $m.Groups[1].Value }
    }
    return $null
}

function Get-ManifestTreeFacts {
    param([string[]]$Lines)
    $facts = @{
        Package         = $null
        AppAttrs        = @{}
        UsesPermissions = @()
        Exported        = @()
    }
    $count = $Lines.Count
    for ($i = 0; $i -lt $count; $i++) {
        $line = $Lines[$i]
        $elementMatch = [regex]::Match($line, '^\s*E: ([^ ]+)')
        if (-not $elementMatch.Success) { continue }
        $element = $elementMatch.Groups[1].Value
        $level = Get-TreeLevel -Line $line

        # collect the element's immediate attribute lines
        $attrLines = @()
        $j = $i + 1
        while ($j -lt $count) {
            $nextLine = $Lines[$j]
            if ($nextLine -notmatch '^\s*A: ') { break }
            if ((Get-TreeLevel -Line $nextLine) -ne ($level + 1)) { break }
            $attrLines += $nextLine
            $j++
        }

        if ($element -eq 'manifest') {
            $facts.Package = Get-AttrValue -AttrLines $attrLines -AttrName 'package'
        }
        elseif ($element -eq 'application') {
            $facts.AppAttrs['allowBackup'] = Get-AttrValue -AttrLines $attrLines 'allowBackup'
            $facts.AppAttrs['icon'] = Get-AttrValue -AttrLines $attrLines 'icon'
            $facts.AppAttrs['roundIcon'] = Get-AttrValue -AttrLines $attrLines 'roundIcon'
            $facts.AppAttrs['theme'] = Get-AttrValue -AttrLines $attrLines 'theme'
            $facts.AppAttrs['label'] = Get-AttrValue -AttrLines $attrLines 'label'
            $facts.AppAttrs['dataExtractionRules'] = Get-AttrValue -AttrLines $attrLines 'dataExtractionRules'
            $facts.AppAttrs['fullBackupContent'] = Get-AttrValue -AttrLines $attrLines 'fullBackupContent'
            $facts.AppAttrs['networkSecurityConfig'] = Get-AttrValue -AttrLines $attrLines 'networkSecurityConfig'
        }
        elseif ($element -like 'uses-permission*') {
            $name = Get-AttrValue -AttrLines $attrLines 'name'
            if ($name) { $facts.UsesPermissions += $name }
        }
        elseif ($element -in @('activity', 'service', 'receiver', 'provider')) {
            $name = Get-AttrValue -AttrLines $attrLines 'name'
            $exported = Get-AttrValue -AttrLines $attrLines 'exported'
            if ($exported -eq 'true') {
                # scan all descendants for the MAIN/LAUNCHER intent-filter
                $descendantText = New-Object System.Text.StringBuilder
                $k = $j
                while ($k -lt $count) {
                    $dLine = $Lines[$k]
                    if ((Get-TreeLevel -Line $dLine) -le $level) { break }
                    [void]$descendantText.AppendLine($dLine)
                    $k++
                }
                $blob = $descendantText.ToString()
                $isLauncher = ($blob.IndexOf('android.intent.action.MAIN', [System.StringComparison]::Ordinal) -ge 0) -and
                              ($blob.IndexOf('android.intent.category.LAUNCHER', [System.StringComparison]::Ordinal) -ge 0)
                $permission = Get-AttrValue -AttrLines $attrLines 'permission'
                $facts.Exported += @{ Type = $element; Name = $name; Launcher = $isLauncher; Permission = $permission }
            }
        }
    }
    return $facts
}

function Get-PackagedXmlResourcePath {
    param([string]$Family, [string]$Apk, [string]$ResName, [string]$ApkLabel)
    $dump = Invoke-Aapt2 -Aapt2Args @('dump', 'resources', $Apk)
    if (-not $dump.Ok) {
        Add-Problem -Family $Family -Text ("aapt2 dump resources failed for " + $ApkLabel)
        return $null
    }
    $lines = @($dump.Lines)
    $count = $lines.Count
    for ($i = 0; $i -lt $count; $i++) {
        if ($lines[$i] -match ('^\s*resource\s+0x[0-9a-f]+\s+xml/' + [regex]::Escape($ResName) + '\s*$')) {
            for ($j = $i + 1; $j -lt $count; $j++) {
                if ($lines[$j] -match '^\s*resource\s+0x') { break }
                $m = [regex]::Match($lines[$j], '^\s*\(\)\s*\(file\)\s*(\S+)\s+type=XML')
                if ($m.Success) { return $m.Groups[1].Value }
            }
        }
    }
    Add-Problem -Family $Family -Text ("packaged resource xml/" + $ResName + " not found in " + $ApkLabel)
    return $null
}

function Get-RuleTreeEntries {
    # Parses an aapt2 xmltree of a backup rule resource into
    # @{ Section; Domain; Path } records. Root element is at indent level 0;
    # cloud-backup/device-transfer sections and bare excludes are supported.
    param([string[]]$Lines)
    $entries = @()
    $section = $null
    $count = $Lines.Count
    for ($i = 0; $i -lt $count; $i++) {
        $line = $Lines[$i]
        $m = [regex]::Match($line, '^\s*E: ([^ ]+)')
        if (-not $m.Success) { continue }
        $element = $m.Groups[1].Value
        $level = Get-TreeLevel -Line $line
        if ($level -eq 0) { $section = $element; continue }
        if ($element -in @('cloud-backup', 'device-transfer')) { $section = $element; continue }
        if ($element -eq 'exclude') {
            $attrs = @()
            $j = $i + 1
            while ($j -lt $count) {
                if ($Lines[$j] -notmatch '^\s*A: ') { break }
                $attrs += $Lines[$j]
                $j++
            }
            $domain = Get-AttrValue -AttrLines $attrs 'domain'
            $path = Get-AttrValue -AttrLines $attrs 'path'
            if ($null -ne $section) { $entries += @{ Section = $section; Domain = $domain; Path = $path } }
        }
    }
    return $entries
}

function Get-SourceRuleEntries {
    param([System.Xml.XmlDocument]$Doc, [string]$RootName)
    $entries = @()
    $root = $Doc.DocumentElement
    if ($null -eq $root -or $root.LocalName -ne $RootName) { return $entries }
    foreach ($node in $root.ChildNodes) {
        if ($node -isnot [System.Xml.XmlElement]) { continue }
        if ($node.LocalName -in @('cloud-backup', 'device-transfer')) {
            foreach ($sub in $node.ChildNodes) {
                if ($sub -is [System.Xml.XmlElement] -and $sub.LocalName -eq 'exclude') {
                    $entries += @{
                        Section = $node.LocalName
                        Domain  = $sub.Attributes['domain'].Value
                        Path    = $sub.Attributes['path'].Value
                    }
                }
            }
        }
        elseif ($node.LocalName -eq 'exclude') {
            $entries += @{
                Section = $RootName
                Domain  = $node.Attributes['domain'].Value
                Path    = $node.Attributes['path'].Value
            }
        }
    }
    return $entries
}

function Test-RuleEntriesCoverNineDomains {
    param([string]$Family, $Entries, [string[]]$ExpectedSections, [string]$Where)
    Add-Check -Family $Family
    $sections = @()
    foreach ($e in $Entries) {
        if ($e.Section -and ($sections -notcontains $e.Section)) { $sections += $e.Section }
    }
    foreach ($section in $ExpectedSections) {
        if ($sections -notcontains $section) {
            Add-Problem -Family $Family -Text ("backup rule section '" + $section + "' missing from " + $Where)
            continue
        }
        foreach ($domain in $script:NineDomains) {
            $found = $false
            foreach ($e in $Entries) {
                if ($e.Section -eq $section -and $e.Domain -eq $domain) {
                    if ($e.Path -ne '.') {
                        Add-Problem -Family $Family -Text ("backup rule section '" + $section + "' excludes domain '" + $domain + "' with path '" + $e.Path + "' instead of '.' in " + $Where)
                    }
                    $found = $true
                    break
                }
            }
            if (-not $found) {
                Add-Problem -Family $Family -Text ("backup rule section '" + $section + "' does not exclude approved domain '" + $domain + "' (path '.') in " + $Where)
            }
        }
    }
}

function Get-UsesPermissionNamesFromXml {
    param([System.Xml.XmlDocument]$Doc)
    $names = @()
    foreach ($el in $Doc.SelectNodes('//*[local-name()="uses-permission" or local-name()="uses-permission-sdk-23" or local-name()="uses-permission-sdk-m"]')) {
        $a = $el.Attributes['android:name']
        if ($null -ne $a) { $names += $a.Value }
    }
    return $names
}

function Test-NetworkPermissionsInText {
    param([string]$Family, [string]$Layer, [string]$Label, [System.Xml.XmlDocument]$Doc)
    Add-Check -Family $Family
    $names = Get-UsesPermissionNamesFromXml -Doc $Doc
    $regex = [regex]$script:NetworkPermissionPattern
    foreach ($name in $names) {
        if ($regex.IsMatch($name)) {
            Add-Problem -Family $Family -Text ("network-class permission '" + $name + "' declared in " + $Layer + " manifest " + $Label)
        }
    }
    $appNode = $Doc.SelectSingleNode('//*[local-name()="application"]')
    if ($null -ne $appNode) {
        $nsc = $appNode.Attributes['android:networkSecurityConfig']
        if ($null -ne $nsc) {
            Add-Problem -Family $Family -Text ("unexpected network capability attribute android:networkSecurityConfig='" + $nsc.Value + "' declared in " + $Layer + " manifest " + $Label)
        }
    }
}

function Test-AllowBackupText {
    param([string]$Family, [string]$Layer, [string]$Label, [System.Xml.XmlDocument]$Doc)
    Add-Check -Family $Family
    $appNode = $Doc.SelectSingleNode('//*[local-name()="application"]')
    if ($null -eq $appNode) {
        Add-Problem -Family $Family -Text ("no <application> element in " + $Layer + " manifest " + $Label)
        return
    }
    $attr = $appNode.Attributes['android:allowBackup']
    if ($null -eq $attr) {
        Add-Problem -Family $Family -Text ("allowBackup attribute missing in " + $Layer + " manifest " + $Label + " (expected 'false')")
        return
    }
    if ($attr.Value -ne 'false') {
        Add-Problem -Family $Family -Text ("allowBackup is '" + $attr.Value + "' in " + $Layer + " manifest " + $Label + " (expected 'false')")
    }
}

# Resolves the APK set once; missing artifacts are recorded as problems on
# every family that consumes them.
$script:ApkPaths = @{}
foreach ($key in @('app-debug', 'app-benchmark', 'app-androidTest', 'benchmark-tool')) {
    $script:ApkPaths[$key] = Test-InputFile -Family 'family-3-package-identity' -Rel $script:ApkRelPaths[$key]
}
$releaseExact = Join-RootPath -Rel $script:ApkRelPaths['app-release']
if (Test-Path -LiteralPath $releaseExact -PathType Leaf) {
    $script:ApkPaths['app-release'] = $releaseExact
}
else {
    $releaseDir = Join-RootPath -Rel 'app/build/outputs/apk/release'
    $script:ApkPaths['app-release'] = $null
    if (Test-Path -LiteralPath $releaseDir -PathType Container) {
        $candidates = @(Get-ChildItem -LiteralPath $releaseDir -Filter 'app-release*.apk' -File | Where-Object { $_.Name -notlike '*androidTest*' })
        if ($candidates.Count -eq 1) {
            $script:ApkPaths['app-release'] = $candidates[0].FullName
        }
        elseif ($candidates.Count -gt 1) {
            Add-Problem -Family 'family-3-package-identity' -Text ("ambiguous release APK in app/build/outputs/apk/release: " + (($candidates | ForEach-Object { $_.Name }) -join ', '))
        }
        else {
            Add-Problem -Family 'family-3-package-identity' -Text ("missing artifact '" + $script:ApkRelPaths['app-release'] + "'")
        }
    }
    else {
        Add-Problem -Family 'family-3-package-identity' -Text ("missing artifact '" + $script:ApkRelPaths['app-release'] + "'")
    }
}
foreach ($family in @('family-1-network-permission', 'family-2-exported-components', 'family-4-allow-backup', 'family-5-backup-nine-domains', 'family-6-icon-starting-theme', 'family-7-release-test-fixtures', 'family-8-tracking-sdk-strings', 'family-9-dependency-versions')) {
    foreach ($key in @('app-release', 'app-benchmark')) {
        if ($null -eq $script:ApkPaths[$key]) {
            Add-Problem -Family $family -Text ("missing artifact '" + $script:ApkRelPaths[$key] + "' (required by " + $family + ")")
        }
    }
}

# =====================================================================
# family-1: network-permission-policy
# =====================================================================
$sourceManifestRel = 'app/src/main/AndroidManifest.xml'
$sourceManifestPath = Test-InputFile -Family 'family-1-network-permission' -Rel $sourceManifestRel
if ($null -ne $sourceManifestPath) {
    $doc = New-Object System.Xml.XmlDocument
    try {
        $doc.Load($sourceManifestPath)
        Test-NetworkPermissionsInText -Family 'family-1-network-permission' -Layer 'source' -Label $sourceManifestRel -Doc $doc
    }
    catch { Add-Problem -Family 'family-1-network-permission' -Text ("cannot parse source manifest " + $sourceManifestRel + ": " + $_.Exception.Message) }
}
foreach ($variant in @('debug', 'release', 'benchmark')) {
    $rel = $script:MergedManifestTexts[$variant]
    $p = Test-InputFile -Family 'family-1-network-permission' -Rel $rel
    if ($null -ne $p) {
        $doc = New-Object System.Xml.XmlDocument
        try {
            $doc.Load($p)
            Test-NetworkPermissionsInText -Family 'family-1-network-permission' -Layer 'merged' -Label $rel -Doc $doc
        }
        catch { Add-Problem -Family 'family-1-network-permission' -Text ("cannot parse merged manifest " + $rel + ": " + $_.Exception.Message) }
    }
}
foreach ($variant in @('app-debug', 'app-release', 'app-benchmark')) {
    $apkPath = $script:ApkPaths[$variant]
    if ($null -eq $apkPath) { continue }
    $tree = Invoke-Aapt2 -Aapt2Args @('dump', 'xmltree', $apkPath, '--file', 'AndroidManifest.xml')
    if (-not $tree.Ok) {
        Add-Problem -Family 'family-1-network-permission' -Text ("aapt2 dump xmltree failed for final manifest of '" + $script:ApkRelPaths[$variant] + "'")
        continue
    }
    Add-Check -Family 'family-1-network-permission'
    $facts = Get-ManifestTreeFacts -Lines $tree.Lines
    $regex = [regex]$script:NetworkPermissionPattern
    foreach ($name in $facts.UsesPermissions) {
        if ($regex.IsMatch($name)) {
            Add-Problem -Family 'family-1-network-permission' -Text ("network-class permission '" + $name + "' declared in final APK manifest '" + $script:ApkRelPaths[$variant] + "'")
        }
    }
    if ($null -ne $facts.AppAttrs['networkSecurityConfig']) {
        Add-Problem -Family 'family-1-network-permission' -Text ("unexpected network capability attribute android:networkSecurityConfig declared in final APK manifest '" + $script:ApkRelPaths[$variant] + "'")
    }
}

# =====================================================================
# family-2: exported-components
# =====================================================================
foreach ($variant in @('app-debug', 'app-release', 'app-benchmark')) {
    $apkPath = $script:ApkPaths[$variant]
    if ($null -eq $apkPath) { continue }
    $tree = Invoke-Aapt2 -Aapt2Args @('dump', 'xmltree', $apkPath, '--file', 'AndroidManifest.xml')
    if (-not $tree.Ok) {
        Add-Problem -Family 'family-2-exported-components' -Text ("aapt2 dump xmltree failed for final manifest of '" + $script:ApkRelPaths[$variant] + "'")
        continue
    }
    Add-Check -Family 'family-2-exported-components'
    $facts = Get-ManifestTreeFacts -Lines $tree.Lines
    $expected = $script:ExpectedExported[$variant]
    $expectedLabel = (($expected | ForEach-Object {
        if ($_.Guard) { $_.Name + '[guard=' + $_.Guard + ']' } else { $_.Name }
    }) -join ', ')
    $found = @()
    foreach ($component in $facts.Exported) {
        $found += $component.Name
        $entry = $null
        foreach ($want in $expected) {
            if ($want.Name -eq $component.Name) { $entry = $want; break }
        }
        if ($null -eq $entry) {
            Add-Problem -Family 'family-2-exported-components' -Text ("unexpected exported " + $component.Type + " '" + $component.Name + "' in final APK '" + $script:ApkRelPaths[$variant] + "' (expected exactly: " + $expectedLabel + ")")
        }
        elseif ($entry.Guard) {
            $actualGuardText = $component.Permission
            if ([string]::IsNullOrEmpty($actualGuardText)) { $actualGuardText = 'none' }
            if ($actualGuardText -ne $entry.Guard) {
                Add-Problem -Family 'family-2-exported-components' -Text ("exported " + $component.Type + " '" + $component.Name + "' in final APK '" + $script:ApkRelPaths[$variant] + "' must carry android:permission='" + $entry.Guard + "' but found '" + $actualGuardText + "'")
            }
        }
    }
    foreach ($want in $expected) {
        if ($found -notcontains $want.Name) {
            Add-Problem -Family 'family-2-exported-components' -Text ("missing expected exported component '" + $want.Name + "' in final APK '" + $script:ApkRelPaths[$variant] + "'")
        }
    }
    foreach ($component in $facts.Exported) {
        if ($component.Name -eq $script:LauncherComponent -and -not $component.Launcher) {
            Add-Problem -Family 'family-2-exported-components' -Text ("expected launcher component '" + $script:LauncherComponent + "' lacks MAIN/LAUNCHER intent-filter in final APK '" + $script:ApkRelPaths[$variant] + "'")
        }
    }
}

# =====================================================================
# family-3: package-identity
# =====================================================================
foreach ($key in @('app-debug', 'app-release', 'app-benchmark', 'app-androidTest', 'benchmark-tool')) {
    $apkPath = $script:ApkPaths[$key]
    if ($null -eq $apkPath) { continue }
    $badging = Invoke-Aapt2 -Aapt2Args @('dump', 'badging', $apkPath)
    if (-not $badging.Ok) {
        Add-Problem -Family 'family-3-package-identity' -Text ("aapt2 dump badging failed for '" + $script:ApkRelPaths[$key] + "'")
        continue
    }
    Add-Check -Family 'family-3-package-identity'
    $found = $null
    foreach ($line in $badging.Lines) {
        if ($line -match "^package: name='([^']+)'") { $found = $Matches[1]; break }
    }
    $expected = $script:ExpectedPackages[$key]
    if ($null -eq $found) {
        Add-Problem -Family 'family-3-package-identity' -Text ("no package name reported by badging for '" + $script:ApkRelPaths[$key] + "'")
    }
    elseif ($found -ne $expected) {
        Add-Problem -Family 'family-3-package-identity' -Text ("package name mismatch for '" + $script:ApkRelPaths[$key] + "': found '" + $found + "', expected '" + $expected + "'")
    }
}

# =====================================================================
# family-4: allow-backup
# =====================================================================
$sourceManifestPath = Test-InputFile -Family 'family-4-allow-backup' -Rel $sourceManifestRel
if ($null -ne $sourceManifestPath) {
    $doc = New-Object System.Xml.XmlDocument
    try { $doc.Load($sourceManifestPath) } catch { $doc = $null }
    if ($null -ne $doc) { Test-AllowBackupText -Family 'family-4-allow-backup' -Layer 'source' -Label $sourceManifestRel -Doc $doc }
}
foreach ($variant in @('release', 'benchmark')) {
    $rel = $script:MergedManifestTexts[$variant]
    $p = Test-InputFile -Family 'family-4-allow-backup' -Rel $rel
    if ($null -ne $p) {
        $doc = New-Object System.Xml.XmlDocument
        try { $doc.Load($p) } catch { $doc = $null }
        if ($null -ne $doc) { Test-AllowBackupText -Family 'family-4-allow-backup' -Layer 'merged' -Label $rel -Doc $doc }
    }
}
foreach ($variant in @('app-release', 'app-benchmark')) {
    $apkPath = $script:ApkPaths[$variant]
    if ($null -eq $apkPath) { continue }
    $tree = Invoke-Aapt2 -Aapt2Args @('dump', 'xmltree', $apkPath, '--file', 'AndroidManifest.xml')
    if (-not $tree.Ok) {
        Add-Problem -Family 'family-4-allow-backup' -Text ("aapt2 dump xmltree failed for final manifest of '" + $script:ApkRelPaths[$variant] + "'")
        continue
    }
    Add-Check -Family 'family-4-allow-backup'
    $facts = Get-ManifestTreeFacts -Lines $tree.Lines
    $value = $facts.AppAttrs['allowBackup']
    if ($null -eq $value) {
        Add-Problem -Family 'family-4-allow-backup' -Text ("allowBackup attribute missing in final APK manifest '" + $script:ApkRelPaths[$variant] + "' (expected 'false')")
    }
    elseif ($value -ne 'false') {
        Add-Problem -Family 'family-4-allow-backup' -Text ("allowBackup is '" + $value + "' in final APK manifest '" + $script:ApkRelPaths[$variant] + "' (expected 'false')")
    }
}

# =====================================================================
# family-5: backup-nine-domains
# =====================================================================
$sourceDataExtractionRel = 'app/src/main/res/xml/data_extraction_rules.xml'
$sourceBackupRulesRel = 'app/src/main/res/xml/backup_rules.xml'
$p = Test-InputFile -Family 'family-5-backup-nine-domains' -Rel $sourceDataExtractionRel
if ($null -ne $p) {
    $doc = New-Object System.Xml.XmlDocument
    try {
        $doc.Load($p)
        $entries = Get-SourceRuleEntries -Doc $doc -RootName 'data-extraction-rules'
        Test-RuleEntriesCoverNineDomains -Family 'family-5-backup-nine-domains' -Entries $entries -ExpectedSections @('cloud-backup', 'device-transfer') -Where $sourceDataExtractionRel
    }
    catch { Add-Problem -Family 'family-5-backup-nine-domains' -Text ("cannot parse " + $sourceDataExtractionRel + ": " + $_.Exception.Message) }
}
$p = Test-InputFile -Family 'family-5-backup-nine-domains' -Rel $sourceBackupRulesRel
if ($null -ne $p) {
    $doc = New-Object System.Xml.XmlDocument
    try {
        $doc.Load($p)
        $entries = Get-SourceRuleEntries -Doc $doc -RootName 'full-backup-content'
        Test-RuleEntriesCoverNineDomains -Family 'family-5-backup-nine-domains' -Entries $entries -ExpectedSections @('full-backup-content') -Where $sourceBackupRulesRel
    }
    catch { Add-Problem -Family 'family-5-backup-nine-domains' -Text ("cannot parse " + $sourceBackupRulesRel + ": " + $_.Exception.Message) }
}
foreach ($variant in @('app-release', 'app-benchmark')) {
    $apkPath = $script:ApkPaths[$variant]
    if ($null -eq $apkPath) { continue }
    foreach ($resName in @('data_extraction_rules', 'backup_rules')) {
        $packagedPath = Get-PackagedXmlResourcePath -Family 'family-5-backup-nine-domains' -Apk $apkPath -ResName $resName -ApkLabel $script:ApkRelPaths[$variant]
        if ($null -eq $packagedPath) { continue }
        $tree = Invoke-Aapt2 -Aapt2Args @('dump', 'xmltree', $apkPath, '--file', $packagedPath)
        if (-not $tree.Ok) {
            Add-Problem -Family 'family-5-backup-nine-domains' -Text ("aapt2 dump xmltree failed for packaged rule " + $packagedPath + " of '" + $script:ApkRelPaths[$variant] + "'")
            continue
        }
        $entries = Get-RuleTreeEntries -Lines $tree.Lines
        $where = ("packaged rule " + $packagedPath + " of '" + $script:ApkRelPaths[$variant] + "'")
        if ($resName -eq 'data_extraction_rules') {
            Test-RuleEntriesCoverNineDomains -Family 'family-5-backup-nine-domains' -Entries $entries -ExpectedSections @('cloud-backup', 'device-transfer') -Where $where
        }
        else {
            Test-RuleEntriesCoverNineDomains -Family 'family-5-backup-nine-domains' -Entries $entries -ExpectedSections @('full-backup-content') -Where $where
        }
    }
}
foreach ($variant in @('release', 'benchmark')) {
    $rel = $script:PackagedManifestTexts[$variant]
    $p = Test-InputFile -Family 'family-5-backup-nine-domains' -Rel $rel
    if ($null -eq $p) { continue }
    Add-Check -Family 'family-5-backup-nine-domains'
    $text = Get-Content -LiteralPath $p -Raw
    if ($text -notmatch 'dataExtractionRules="@xml/data_extraction_rules"') {
        Add-Problem -Family 'family-5-backup-nine-domains' -Text ("packaged manifest " + $rel + " does not reference @xml/data_extraction_rules")
    }
    if ($text -notmatch 'fullBackupContent="@xml/backup_rules"') {
        Add-Problem -Family 'family-5-backup-nine-domains' -Text ("packaged manifest " + $rel + " does not reference @xml/backup_rules")
    }
}

# =====================================================================
# family-6: icon-starting-theme
# =====================================================================
foreach ($rel in @(
    'app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml',
    'app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml'
)) {
    $p = Test-InputFile -Family 'family-6-icon-starting-theme' -Rel $rel
    if ($null -eq $p) { continue }
    Add-Check -Family 'family-6-icon-starting-theme'
    $doc = New-Object System.Xml.XmlDocument
    try {
        $doc.Load($p)
        $iconRoot = $doc.DocumentElement
        if ($iconRoot.LocalName -ne 'adaptive-icon') {
            Add-Problem -Family 'family-6-icon-starting-theme' -Text ($rel + " is not an adaptive-icon document (root '" + $iconRoot.LocalName + "')")
            continue
        }
        foreach ($child in @('background', 'foreground', 'monochrome')) {
            $found = $false
            foreach ($node in $iconRoot.ChildNodes) {
                if ($node -is [System.Xml.XmlElement] -and $node.LocalName -eq $child -and $null -ne $node.Attributes['android:drawable']) { $found = $true; break }
            }
            if (-not $found) {
                Add-Problem -Family 'family-6-icon-starting-theme' -Text ($rel + " missing <" + $child + "> adaptive-icon element")
            }
        }
    }
    catch { Add-Problem -Family 'family-6-icon-starting-theme' -Text ("cannot parse " + $rel + ": " + $_.Exception.Message) }
}
foreach ($rel in @(
    'app/src/main/res/drawable/ic_record_foreground.xml',
    'app/src/main/res/drawable/ic_record_monochrome.xml'
)) {
    $p = Test-InputFile -Family 'family-6-icon-starting-theme' -Rel $rel
    if ($null -eq $p) { continue }
    Add-Check -Family 'family-6-icon-starting-theme'
    $doc = New-Object System.Xml.XmlDocument
    try {
        $doc.Load($p)
        if ($doc.DocumentElement.LocalName -ne 'vector') {
            Add-Problem -Family 'family-6-icon-starting-theme' -Text ($rel + " is not a vector drawable document")
        }
    }
    catch { Add-Problem -Family 'family-6-icon-starting-theme' -Text ("cannot parse " + $rel + ": " + $_.Exception.Message) }
}
function Test-ThemeRecordItems {
    param([string]$Rel, [string[]]$RequiredItems)
    $p = Test-InputFile -Family 'family-6-icon-starting-theme' -Rel $Rel
    if ($null -eq $p) { return }
    Add-Check -Family 'family-6-icon-starting-theme'
    $doc = New-Object System.Xml.XmlDocument
    try { $doc.Load($p) } catch {
        Add-Problem -Family 'family-6-icon-starting-theme' -Text ("cannot parse " + $Rel + ": " + $_.Exception.Message)
        return
    }
    $style = $null
    foreach ($node in $doc.SelectNodes('//*[local-name()="style"]')) {
        $n = $node.Attributes['name']
        if ($null -ne $n -and $n.Value -eq 'Theme.Record') { $style = $node; break }
    }
    if ($null -eq $style) {
        Add-Problem -Family 'family-6-icon-starting-theme' -Text ("starting-window theme 'Theme.Record' not defined in " + $Rel)
        return
    }
    foreach ($item in $RequiredItems) {
        $found = $false
        foreach ($node in $style.ChildNodes) {
            if ($node -is [System.Xml.XmlElement] -and $node.LocalName -eq 'item') {
                $n = $node.Attributes['name']
                if ($null -ne $n -and $n.Value -eq $item) { $found = $true; break }
            }
        }
        if (-not $found) {
            Add-Problem -Family 'family-6-icon-starting-theme' -Text ("starting-window theme 'Theme.Record' missing item '" + $item + "' in " + $Rel)
        }
    }
}
function Test-ColorValue {
    param([string]$Rel, [string]$Name, [string]$Expected)
    $p = Test-InputFile -Family 'family-6-icon-starting-theme' -Rel $Rel
    if ($null -eq $p) { return }
    Add-Check -Family 'family-6-icon-starting-theme'
    $doc = New-Object System.Xml.XmlDocument
    try { $doc.Load($p) } catch {
        Add-Problem -Family 'family-6-icon-starting-theme' -Text ("cannot parse " + $Rel + ": " + $_.Exception.Message)
        return
    }
    $color = $null
    foreach ($node in $doc.SelectNodes('//*[local-name()="color"]')) {
        $n = $node.Attributes['name']
        if ($null -ne $n -and $n.Value -eq $Name) { $color = $node; break }
    }
    if ($null -eq $color) {
        Add-Problem -Family 'family-6-icon-starting-theme' -Text ("color '" + $Name + "' not defined in " + $Rel)
        return
    }
    # accept both '#RRGGBB' source form and alpha-prefixed '#AARRGGBB' form
    $value = $color.InnerText.Trim().TrimStart('#').ToLowerInvariant()
    if ($value.Length -gt 6) { $value = $value.Substring($value.Length - 6) }
    if ($value -ne $Expected) {
        Add-Problem -Family 'family-6-icon-starting-theme' -Text ("color '" + $Name + "' is '" + $color.InnerText.Trim() + "' in " + $Rel + " (expected '#" + $Expected + "')")
    }
}
Test-ThemeRecordItems -Rel 'app/src/main/res/values/themes.xml' -RequiredItems @('android:windowBackground', 'android:windowSplashScreenBackground')
Test-ThemeRecordItems -Rel 'app/src/main/res/values-night/themes.xml' -RequiredItems @('android:windowBackground', 'android:windowSplashScreenBackground')
Test-ColorValue -Rel 'app/src/main/res/values/colors.xml' -Name 'record_app_background' -Expected $script:RecordAppBackgroundLight
Test-ColorValue -Rel 'app/src/main/res/values-night/colors.xml' -Name 'record_app_background' -Expected $script:RecordAppBackgroundNight

foreach ($variant in @('release', 'benchmark')) {
    $rel = $script:PackagedManifestTexts[$variant]
    $p = Test-InputFile -Family 'family-6-icon-starting-theme' -Rel $rel
    if ($null -eq $p) { continue }
    Add-Check -Family 'family-6-icon-starting-theme'
    $text = Get-Content -LiteralPath $p -Raw
    if ($text -notmatch 'icon="@mipmap/ic_launcher"') {
        Add-Problem -Family 'family-6-icon-starting-theme' -Text ("packaged manifest " + $rel + " does not reference @mipmap/ic_launcher")
    }
    if ($text -notmatch 'roundIcon="@mipmap/ic_launcher_round"') {
        Add-Problem -Family 'family-6-icon-starting-theme' -Text ("packaged manifest " + $rel + " does not reference @mipmap/ic_launcher_round")
    }
    if ($text -notmatch 'theme="@style/Theme.Record"') {
        Add-Problem -Family 'family-6-icon-starting-theme' -Text ("packaged manifest " + $rel + " does not reference @style/Theme.Record")
    }
}
foreach ($variant in @('app-release', 'app-benchmark')) {
    $apkPath = $script:ApkPaths[$variant]
    if ($null -eq $apkPath) { continue }
    $tree = Invoke-Aapt2 -Aapt2Args @('dump', 'xmltree', $apkPath, '--file', 'AndroidManifest.xml')
    if (-not $tree.Ok) {
        Add-Problem -Family 'family-6-icon-starting-theme' -Text ("aapt2 dump xmltree failed for final manifest of '" + $script:ApkRelPaths[$variant] + "'")
        continue
    }
    Add-Check -Family 'family-6-icon-starting-theme'
    $facts = Get-ManifestTreeFacts -Lines $tree.Lines
    foreach ($attr in @('icon', 'roundIcon', 'theme')) {
        $value = $facts.AppAttrs[$attr]
        if ($null -eq $value) {
            Add-Problem -Family 'family-6-icon-starting-theme' -Text ("final APK manifest '" + $script:ApkRelPaths[$variant] + "' is missing the android:" + $attr + " attribute")
        }
    }
}

$releaseApkPath = $script:ApkPaths['app-release']
if ($null -ne $releaseApkPath) {
    $dump = Invoke-Aapt2 -Aapt2Args @('dump', 'resources', $releaseApkPath)
    if (-not $dump.Ok) {
        Add-Problem -Family 'family-6-icon-starting-theme' -Text ("aapt2 dump resources failed for '" + $script:ApkRelPaths['app-release'] + "'")
    }
    else {
        Add-Check -Family 'family-6-icon-starting-theme'
        $lines = @($dump.Lines)
        $count = $lines.Count
        for ($i = 0; $i -lt $count; $i++) {
            if ($lines[$i] -match '^\s*resource\s+0x[0-9a-f]+\s+color/record_app_background\s*$') {
                $hasDefault = $false; $defaultOk = $false; $hasNight = $false; $nightOk = $false
                for ($j = $i + 1; $j -lt $count; $j++) {
                    if ($lines[$j] -match '^\s*resource\s+0x[0-9a-f]+\s+') { break }
                    $mDefault = [regex]::Match($lines[$j], '^\s*\(\)\s*#([0-9a-fA-F]+)\s*$')
                    if ($mDefault.Success) { $hasDefault = $true; if ($mDefault.Groups[1].Value.ToLowerInvariant().Substring(([Math]::Max(0, $mDefault.Groups[1].Value.Length - 6))) -eq $script:RecordAppBackgroundLight) { $defaultOk = $true } }
                    $mNight = [regex]::Match($lines[$j], '^\s*\(night\)\s*#([0-9a-fA-F]+)\s*$')
                    if ($mNight.Success) { $hasNight = $true; if ($mNight.Groups[1].Value.ToLowerInvariant().Substring(([Math]::Max(0, $mNight.Groups[1].Value.Length - 6))) -eq $script:RecordAppBackgroundNight) { $nightOk = $true } }
                }
                if (-not ($hasDefault -and $defaultOk)) {
                    Add-Problem -Family 'family-6-icon-starting-theme' -Text ("packaged color/record_app_background default configuration missing or not '#" + $script:RecordAppBackgroundLight + "' in '" + $script:ApkRelPaths['app-release'] + "'")
                }
                if (-not ($hasNight -and $nightOk)) {
                    Add-Problem -Family 'family-6-icon-starting-theme' -Text ("packaged color/record_app_background night configuration missing or not '#" + $script:RecordAppBackgroundNight + "' in '" + $script:ApkRelPaths['app-release'] + "'")
                }
                break
            }
        }
        foreach ($res in @('drawable/ic_record_foreground', 'drawable/ic_record_monochrome', 'mipmap/ic_launcher', 'mipmap/ic_launcher_round', 'style/Theme.Record')) {
            $found = $false
            foreach ($line in $lines) {
                if ($line -match ('^\s*resource\s+0x[0-9a-f]+\s+' + [regex]::Escape($res) + '\s*$')) { $found = $true; break }
            }
            if (-not $found) {
                Add-Problem -Family 'family-6-icon-starting-theme' -Text ("packaged resource '" + $res + "' not found in '" + $script:ApkRelPaths['app-release'] + "'")
            }
        }
        $hasNightStyle = $false
        for ($i = 0; $i -lt $count; $i++) {
            if ($lines[$i] -match '^\s*resource\s+0x[0-9a-f]+\s+style/Theme\.Record\s*$') {
                for ($j = $i + 1; $j -lt $count; $j++) {
                    if ($lines[$j] -match '^\s*resource\s+0x[0-9a-f]+\s+') { break }
                    if ($lines[$j] -match '^\s*\(night\)\s*\(style\)') { $hasNightStyle = $true }
                }
                break
            }
        }
        if (-not $hasNightStyle) {
            Add-Problem -Family 'family-6-icon-starting-theme' -Text ("packaged style/Theme.Record night configuration not found in '" + $script:ApkRelPaths['app-release'] + "'")
        }
    }
}

# =====================================================================
# family-7 + family-8: release APK token scans
# =====================================================================
Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null

function Test-ApkTokenScan {
    param([string]$Family, [string]$ApkPath, [string]$ApkLabel, [string[]]$Tokens, [string]$TokenKind)
    $zip = $null
    try { $zip = [System.IO.Compression.ZipFile]::OpenRead($ApkPath) } catch {
        Add-Problem -Family $Family -Text ("cannot open " + $ApkLabel + " as a zip archive: " + $_.Exception.Message)
        return
    }
    try {
        $enc = [System.Text.Encoding]::GetEncoding('ISO-8859-1')
        foreach ($entry in $zip.Entries) {
            if ($entry.FullName -notmatch '^classes.*\.dex$|^AndroidManifest\.xml$|^resources\.arsc$') { continue }
            Add-Check -Family $Family
            $stream = $entry.Open()
            $ms = New-Object System.IO.MemoryStream
            try {
                $stream.CopyTo($ms)
                $bytes = $ms.ToArray()
            }
            finally {
                $ms.Dispose()
                $stream.Dispose()
            }
            $text = $enc.GetString($bytes)
            foreach ($token in $Tokens) {
                # scan both the plain form and the dex descriptor form
                # (class tokens appear as Lcom/paherdin/record/X; inside dex)
                $needles = @(($enc.GetString($enc.GetBytes($token))))
                if ($token -match '\.') {
                    $needles += $enc.GetString($enc.GetBytes(('L' + ($token.Replace('.', '/')) + ';')))
                }
                foreach ($needle in $needles) {
                    if ($text.IndexOf($needle, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
                        Add-Problem -Family $Family -Text ($TokenKind + " token '" + $token + "' found in entry '" + $entry.FullName + "' of " + $ApkLabel)
                        break
                    }
                }
            }
        }
    }
    finally {
        $zip.Dispose()
    }
}

if ($null -ne $releaseApkPath) {
    Test-ApkTokenScan -Family 'family-7-release-test-fixtures' -ApkPath $releaseApkPath -ApkLabel ("release APK '" + $script:ApkRelPaths['app-release'] + "'") -Tokens $script:ReleaseFixtureTokens -TokenKind 'test fixture class'
    Test-ApkTokenScan -Family 'family-8-tracking-sdk-strings' -ApkPath $releaseApkPath -ApkLabel ("release APK '" + $script:ApkRelPaths['app-release'] + "'") -Tokens $script:TrackingSdkTokens -TokenKind 'tracking SDK'
}
foreach ($rel in @($script:MergedManifestTexts['release'], $script:PackagedManifestTexts['release'])) {
    $p = Test-InputFile -Family 'family-7-release-test-fixtures' -Rel $rel
    if ($null -eq $p) { continue }
    Add-Check -Family 'family-7-release-test-fixtures'
    $text = Get-Content -LiteralPath $p -Raw
    foreach ($token in $script:ReleaseFixtureTokens) {
        if ($text.IndexOf($token, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
            Add-Problem -Family 'family-7-release-test-fixtures' -Text ("test fixture class token '" + $token + "' found in merged/packaged manifest " + $rel)
        }
    }
}

# =====================================================================
# family-9: dependency-versions
# =====================================================================
$tomlRel = 'gradle/libs.versions.toml'
$p = Test-InputFile -Family 'family-9-dependency-versions' -Rel $tomlRel
if ($null -ne $p) {
    Add-Check -Family 'family-9-dependency-versions'
    $section = $null
    $foundVersions = @{}
    $foundLibraries = @{}
    $foundPlugins = @{}
    foreach ($line in @(Get-Content -LiteralPath $p)) {
        if ($line -match '^\s*\[([a-zA-Z0-9-]+)\]\s*$') { $section = $Matches[1]; continue }
        if ($null -eq $section) { continue }
        if ($section -eq 'versions' -and $line -match '^\s*([A-Za-z0-9_-]+)\s*=\s*"([^"]*)"\s*$') {
            $foundVersions[$Matches[1]] = $Matches[2]
        }
        elseif ($section -eq 'libraries' -and $line -match '^\s*([A-Za-z0-9_-]+)\s*=\s*\{\s*group\s*=\s*"([^"]+)"\s*,\s*name\s*=\s*"([^"]+)"\s*(?:,\s*version\.ref\s*=\s*"([^"]*)")?\s*\}\s*$') {
            $foundLibraries[$Matches[1]] = @{ Coordinate = ($Matches[2] + ':' + $Matches[3]); VersionRef = $Matches[4] }
        }
        elseif ($section -eq 'plugins' -and $line -match '^\s*([A-Za-z0-9_-]+)\s*=\s*\{\s*id\s*=\s*"([^"]+)"\s*(?:,\s*version\.ref\s*=\s*"([^"]*)")?\s*\}\s*$') {
            $foundPlugins[$Matches[1]] = @{ Id = $Matches[2]; VersionRef = $Matches[3] }
        }
    }
    foreach ($key in $script:FrozenTomlVersions.Keys) {
        if (-not $foundVersions.ContainsKey($key)) {
            Add-Problem -Family 'family-9-dependency-versions' -Text ("catalog [versions] key '" + $key + "' missing from " + $tomlRel)
        }
        elseif ($foundVersions[$key] -ne $script:FrozenTomlVersions[$key]) {
            Add-Problem -Family 'family-9-dependency-versions' -Text ("catalog version '" + $key + "' is '" + $foundVersions[$key] + "', expected '" + $script:FrozenTomlVersions[$key] + "'")
        }
    }
    foreach ($key in $foundVersions.Keys) {
        if (-not $script:FrozenTomlVersions.ContainsKey($key)) {
            Add-Problem -Family 'family-9-dependency-versions' -Text ("unknown catalog [versions] key '" + $key + "' = '" + $foundVersions[$key] + "' in " + $tomlRel + " (no approved counterpart)")
        }
    }
    foreach ($key in $script:FrozenTomlLibraries.Keys) {
        if (-not $foundLibraries.ContainsKey($key)) {
            Add-Problem -Family 'family-9-dependency-versions' -Text ("catalog [libraries] alias '" + $key + "' missing from " + $tomlRel)
        }
        elseif ($foundLibraries[$key].Coordinate -ne $script:FrozenTomlLibraries[$key]) {
            Add-Problem -Family 'family-9-dependency-versions' -Text ("catalog alias '" + $key + "' is '" + $foundLibraries[$key].Coordinate + "', expected '" + $script:FrozenTomlLibraries[$key] + "'")
        }
    }
    foreach ($key in $foundLibraries.Keys) {
        if (-not $script:FrozenTomlLibraries.ContainsKey($key)) {
            Add-Problem -Family 'family-9-dependency-versions' -Text ("unknown catalog [libraries] alias '" + $key + "' in " + $tomlRel + " (no approved counterpart)")
        }
        if ($foundLibraries[$key].VersionRef -and -not $foundVersions.ContainsKey($foundLibraries[$key].VersionRef)) {
            Add-Problem -Family 'family-9-dependency-versions' -Text ("catalog alias '" + $key + "' references unknown version.ref '" + $foundLibraries[$key].VersionRef + "'")
        }
    }
    foreach ($key in $script:FrozenTomlPlugins.Keys) {
        $want = $script:FrozenTomlPlugins[$key]
        if (-not $foundPlugins.ContainsKey($key)) {
            Add-Problem -Family 'family-9-dependency-versions' -Text ("catalog [plugins] alias '" + $key + "' missing from " + $tomlRel)
        }
        elseif ($foundPlugins[$key].Id -ne $want[0]) {
            Add-Problem -Family 'family-9-dependency-versions' -Text ("catalog plugin alias '" + $key + "' is '" + $foundPlugins[$key].Id + "', expected '" + $want[0] + "'")
        }
        elseif ($foundPlugins[$key].VersionRef -ne $want[1]) {
            Add-Problem -Family 'family-9-dependency-versions' -Text ("catalog plugin alias '" + $key + "' version.ref is '" + $foundPlugins[$key].VersionRef + "', expected '" + $want[1] + "'")
        }
    }
    foreach ($key in $foundPlugins.Keys) {
        if (-not $script:FrozenTomlPlugins.ContainsKey($key)) {
            Add-Problem -Family 'family-9-dependency-versions' -Text ("unknown catalog [plugins] alias '" + $key + "' in " + $tomlRel + " (no approved counterpart)")
        }
    }
}

function Test-MergerReport {
    param([string]$Family, [string]$Rel, [hashtable]$Require)
    $p = Test-InputFile -Family $Family -Rel $Rel
    if ($null -eq $p) { return }
    Add-Check -Family $Family
    $text = Get-Content -LiteralPath $p -Raw
    $coords = @{}
    foreach ($m in [regex]::Matches($text, '\[((?:[A-Za-z0-9._-]+):(?:[A-Za-z0-9._-]+):([0-9][A-Za-z0-9.+.-]*))\]')) {
        $parts = ($m.Groups[1].Value -split ':')
        # normalize the -android artifact suffix so 'navigation-compose-android'
        # matches the frozen 'androidx.navigation:navigation-compose' key
        $base = $parts[0] + ':' + ($parts[1] -replace '-android$', '')
        if (-not $coords.ContainsKey($base)) { $coords[$base] = $parts[2] }
    }
    foreach ($key in $Require.Keys) {
        $want = $Require[$key]
        if (-not $coords.ContainsKey($key)) {
            Add-Problem -Family $Family -Text ("required dependency '" + $key + ":" + $want + "' absent from " + $Rel)
        }
        elseif ($coords[$key] -ne $want) {
            Add-Problem -Family $Family -Text ("dependency '" + $key + "' resolved at '" + $coords[$key] + "', expected '" + $want + "' in " + $Rel)
        }
    }
    foreach ($token in $script:DependencyDenyTokens) {
        if ($text.IndexOf($token, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
            Add-Problem -Family $Family -Text ("forbidden dependency token '" + $token + "' present in " + $Rel)
        }
    }
}
Test-MergerReport -Family 'family-9-dependency-versions' -Rel 'app/build/outputs/logs/manifest-merger-release-report.txt' -Require $script:MergerRequireRelease
Test-MergerReport -Family 'family-9-dependency-versions' -Rel 'app/build/outputs/logs/manifest-merger-benchmark-report.txt' -Require $script:MergerRequireBenchmark

function Test-ApkVersionEntries {
    param([string]$Family, [string]$ApkPath, [string]$ApkLabel, [hashtable]$Require)
    $zip = $null
    try { $zip = [System.IO.Compression.ZipFile]::OpenRead($ApkPath) } catch {
        Add-Problem -Family $Family -Text ("cannot open " + $ApkLabel + " as a zip archive: " + $_.Exception.Message)
        return
    }
    try {
        Add-Check -Family $Family
        $versionEntries = @{}
        foreach ($entry in $zip.Entries) {
            if ($entry.FullName -notmatch '^META-INF/.+\.version$') { continue }
            $stream = $entry.Open()
            $reader = New-Object System.IO.StreamReader($stream)
            $content = $reader.ReadToEnd().Trim()
            $reader.Dispose()
            $versionEntries[$entry.FullName] = $content
            foreach ($token in @('junit_', 'androidx_test_', 'com_google_android_gms', 'com_google_android_firebase', 'com_google_dagger', 'io_insert_koin', 'com_squareup_okhttp', 'com_squareup_retrofit', 'com_appsflyer', 'com_adjust')) {
                if ($entry.FullName.IndexOf($token, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
                    Add-Problem -Family $Family -Text ("forbidden dependency version entry '" + $entry.FullName + "' present in " + $ApkLabel)
                }
            }
        }
        foreach ($key in $Require.Keys) {
            $parts = $key -split ':'
            # META-INF dependency-info entries keep the group dots:
            # androidx.lifecycle_lifecycle-runtime-compose.version
            $entryName = 'META-INF/' + $parts[0] + '_' + $parts[1] + '.version'
            if (-not $versionEntries.ContainsKey($entryName)) {
                Add-Problem -Family $Family -Text ("required dependency version entry '" + $entryName + "' (" + $key + ":" + $Require[$key] + ") absent from " + $ApkLabel)
            }
            elseif ($versionEntries[$entryName] -ne $Require[$key]) {
                Add-Problem -Family $Family -Text ("dependency '" + $key + "' embedded at '" + $versionEntries[$entryName] + "', expected '" + $Require[$key] + "' in " + $ApkLabel)
            }
        }
    }
    finally {
        $zip.Dispose()
    }
}
if ($null -ne $releaseApkPath) {
    Test-ApkVersionEntries -Family 'family-9-dependency-versions' -ApkPath $releaseApkPath -ApkLabel ("release APK '" + $script:ApkRelPaths['app-release'] + "'") -Require $script:MergerRequireRelease
}
$benchmarkApkPath = $script:ApkPaths['app-benchmark']
if ($null -ne $benchmarkApkPath) {
    Test-ApkVersionEntries -Family 'family-9-dependency-versions' -ApkPath $benchmarkApkPath -ApkLabel ("benchmark APK '" + $script:ApkRelPaths['app-benchmark'] + "'") -Require $script:MergerRequireBenchmark
}

$benchmarkBuildRel = 'benchmark/build.gradle.kts'
$p = Test-InputFile -Family 'family-9-dependency-versions' -Rel $benchmarkBuildRel
if ($null -ne $p) {
    Add-Check -Family 'family-9-dependency-versions'
    $text = Get-Content -LiteralPath $p -Raw
    # deny tokens apply to real dependency declarations, not to explanatory
    # comments (e.g. a documented 'androidx.test:monitor 1.7.2' alignment note)
    $codeText = ($text -replace '(?m)(?<!:)//.*$', '')
    foreach ($dep in $script:FrozenBenchmarkBuildDeps) {
        if ($text.IndexOf($dep, [System.StringComparison]::Ordinal) -lt 0) {
            Add-Problem -Family 'family-9-dependency-versions' -Text ("expected frozen benchmark dependency '" + $dep + "' not found in " + $benchmarkBuildRel)
        }
    }
    foreach ($token in $script:DependencyDenyTokens) {
        if ($codeText.IndexOf($token, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
            Add-Problem -Family 'family-9-dependency-versions' -Text ("forbidden dependency token '" + $token + "' present in " + $benchmarkBuildRel)
        }
    }
}
$appBuildRel = 'app/build.gradle.kts'
$p = Test-InputFile -Family 'family-9-dependency-versions' -Rel $appBuildRel
if ($null -ne $p) {
    Add-Check -Family 'family-9-dependency-versions'
    $text = Get-Content -LiteralPath $p -Raw
    $codeText = ($text -replace '(?m)(?<!:)//.*$', '')
    foreach ($token in $script:DependencyDenyTokens) {
        if ($codeText.IndexOf($token, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
            Add-Problem -Family 'family-9-dependency-versions' -Text ("forbidden dependency token '" + $token + "' present in " + $appBuildRel)
        }
    }
}

# =====================================================================
# Report
# =====================================================================
$totalChecks = 0
$totalProblems = 0
$failedFamilies = @()
foreach ($f in $script:Families) {
    $problems = $script:FamilyProblems[$f]
    $checks = $script:FamilyChecks[$f]
    $totalChecks += $checks
    $totalProblems += $problems.Count
    $row = ''
    if ($problems.Count -eq 0) {
        $row = "[PASS] " + $f + " | checks=" + $checks
    }
    else {
        $row = "[FAIL] " + $f + " | checks=" + $checks + " | problems=" + $problems.Count
        $failedFamilies += $f
    }
    Write-Output $row
    foreach ($problem in $problems) {
        Write-Output ("  problem: " + $problem)
    }
}
Write-Output '================================================================='
if ($failedFamilies.Count -eq 0) {
    Write-Output ("M1-ARTIFACT-VERIFY: PASS | families=" + $script:Families.Count + "/" + $script:Families.Count + " | checks=" + $totalChecks)
    exit 0
}
else {
    Write-Output ("M1-ARTIFACT-VERIFY: FAIL | failed-families=" + $failedFamilies.Count + "/" + $script:Families.Count + " | checks=" + $totalChecks + " | problems=" + $totalProblems)
    foreach ($f in $failedFamilies) { Write-Output ("  failed: " + $f) }
    exit 1
}