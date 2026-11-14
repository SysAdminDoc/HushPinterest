<#
.SYNOPSIS
    Walk the whole patched app on a leased phone or an emulator and leave a walk receipt.

.DESCRIPTION
    release/publish-release.ps1 refuses to publish a bundle without a receipt that a whole-app walk
    of that exact bundle left (walk-receipt.ps1). This runs the walk: it patches Pinterest from the
    repo's release bundle with patch-for-device.ps1, installs it with the account-preserving
    installer, opens each patched screen and every Hush settings page, watches the crash buffer for
    a Pinterest crash, and writes walk-receipt.json with New-WalkReceipt. The receipt binds the
    checked patch run to the signed APK observed on the device. Publish also holds the bundle
    and catalog hashes to the release. When
    the release build stage has already staged that bundle, the two have to be the same file.

    The result the walk writes:
      PASS      the whole app was walked, every screen and settings page opened, no crash.
      PARTIAL   the settings pages were skipped, so it was not the whole app.
      FAIL      a Pinterest crash, a screen or page that would not open, or Pinterest gone at the end.
    Only a PASS backs a release.

    A screen is opened by its deep link (am start -W -a VIEW -d ... -p com.pinterest) and counts
    when the start reports ok with Pinterest in front. No link reaches the Inbox (Pinterest sends
    pinterest://notifications/ to the home feed), so the walk opens it from the home feed: the
    bottom bar's Home, then the header's Inbox button, each read by its description from a fresh
    dump, and the Inbox counts when its title shows. The Hush settings open through
    APPLICATION_PREFERENCES, the intent Android's App info page sends. Settings has no intent for a
    single page, so each page is opened from the settings list: a fresh uiautomator dump, the row
    whose text is exactly the page's name, a tap on its centre, and a page counts when its title
    shows and the list's search box is gone. Back returns to the list. Taps only ever
    land on Hush settings rows read from a dump taken after the last scroll or Back.

    The walk also holds the patches' hooks to account. Before it restarts Pinterest it turns on the
    switch of every family in -HookFamilies, and after the last settings page it saves the full
    diagnostics report from Pause, backup and diagnostics, copies it to the output folder as
    diagnostics.txt and deletes the phone's copy. A family whose case opened with its switch on
    fails the walk, by name, when [HOOK STATUS] shows its hook never ran or lists a target it
    missed or found more than once. Every family the map leaves out, or whose case didn't open, is
    reported as not exercised rather than passed. The receipt's hooks field holds each verdict.
    The switches the walk turned on are turned back off before it ends, even when it fails.

    Device safety: the walk takes an exclusive serial lease and releases it when it's done. It
    changes no device setting, only wakes the screen, and stops when the phone is locked rather than
    unlocking it. It never uninstalls or clears the installed app; an install that can't update
    the app in place is a problem the walk reports, not one it forces past. On the pin it only
    swipes, and on the Inbox, which lists suggested people, it only reads the title and goes Back.
    Every other tap lands on a Hush settings row, its export dialog, the bottom bar's Home or the
    Inbox button.

.EXAMPLE
    scripts/walk-app.ps1 -Serial emulator-5578

.EXAMPLE
    # Survey an installed build. Without build evidence this produces PARTIAL, never a release PASS:
    scripts/walk-app.ps1 -Serial <your phone's adb serial> -SkipPatch

.EXAMPLE
    # Walk an installed APK with the matching sidecar written by patch-for-device.ps1:
    scripts/walk-app.ps1 -Serial <your phone's adb serial> -SkipPatch -BuildEvidence <signed.apk.build.json>
#>
[CmdletBinding()]
param(
    [string]$Serial,
    [string]$Package = 'com.pinterest',
    # The patched screens to open, each a deep link. A link that won't open fails the walk rather
    # than skipping it. The pin goes first, straight from the home feed: opened over a search,
    # Pinterest 14.44.0 leaves the "Ideas you might love" row out of the pin's related grid.
    [string[]]$Visit = @(
        'pinterest://pin/1477812369124424/',
        'pinterest://search/pins/?q=nature',
        'pinterest://search/boards/?q=recipes',
        'pinterest://search/users/?q=art'
    ),
    # Swipes up once a case has opened, for a surface below its first screen: Hide topic
    # suggestions works on the pin's "Ideas you might love" row, in the related grid that loads
    # four or five screens down. Swiping past it is harmless; it only has to be bound once.
    [hashtable]$CaseSwipes = @{ 'pinterest://pin/1477812369124424/' = 7 },
    # Which case reaches each hook family: 'home' for the home feed, 'inbox' for the Inbox opened
    # from it, or a link above. Page, Switch
    # and Key name the switch that turns it on: its settings page and English title, and its key as
    # the diagnostics report prints it. A family left out is reported as not exercised.
    [object[]]$HookFamilies = @(
        @{ Family = 'Hide ads'; Case = 'home'; Page = 'Feed'; Switch = 'Hide ads'; Key = 'hushpinterest_hide_ads' }
        @{ Family = 'Hide AI-labeled pins'; Case = 'home'; Page = 'Feed'; Switch = 'Hide AI-labeled pins'; Key = 'hushpinterest_hide_ai_pins' }
        @{ Family = 'Hide shopping and product pins'; Case = 'home'; Page = 'Feed'; Switch = 'Hide shopping and product pins'; Key = 'hushpinterest_hide_shopping' }
        @{ Family = 'Filter feed'; Case = 'home'; Page = 'Feed'; Switch = 'Hide Home suggestions'; Key = 'hushpinterest_hide_home_suggestions' }
        @{ Family = 'Disable analytics'; Case = 'home'; Page = 'Privacy'; Switch = 'Disable analytics'; Key = 'hushpinterest_disable_analytics' }
        @{ Family = 'Hide advertising ID'; Case = 'home'; Page = 'Privacy'; Switch = 'Hide advertising ID'; Key = 'hushpinterest_hide_ad_id' }
        @{ Family = 'Download board'; Case = 'home'; Page = 'Pin actions'; Switch = 'Download board'; Key = 'hushpinterest_download_board' }
        @{ Family = 'No screenshot share menu'; Case = 'home'; Page = 'Interface'; Switch = 'No screenshot share menu'; Key = 'hushpinterest_hide_screenshot_share' }
        @{ Family = 'Hide Assistant'; Case = 'home'; Page = 'Interface'; Switch = 'Hide Assistant'; Key = 'hushpinterest_hide_assistant' }
        @{ Family = 'Hide navigation buttons'; Case = 'home'; Page = 'Interface'; Switch = 'Hide Create button'; Key = 'hushpinterest_hide_nav_create' }
        @{ Family = 'AMOLED black'; Case = 'home'; Page = 'Interface'; Switch = 'Black bottom bar'; Key = 'hushpinterest_black_bottom_bar' }
        @{ Family = 'Original-quality images'; Case = 'home'; Page = 'Interface'; Switch = 'Original-quality images'; Key = 'hushpinterest_original_images' }
        @{ Family = 'Hide closeup buttons'; Case = 'pinterest://pin/1477812369124424/'; Page = 'Interface'; Switch = 'Hide lens button on pins'; Key = 'hushpinterest_hide_closeup_lens' }
        @{ Family = 'Hide comments'; Case = 'pinterest://pin/1477812369124424/'; Page = 'Interface'; Switch = 'Hide comments'; Key = 'hushpinterest_hide_comments' }
        @{ Family = 'Hide topic suggestions'; Case = 'pinterest://pin/1477812369124424/'; Page = 'Interface'; Switch = 'Hide topic suggestions'; Key = 'hushpinterest_hide_topic_suggestions' }
        @{ Family = 'Show posted date'; Case = 'pinterest://pin/1477812369124424/'; Page = 'Interface'; Switch = 'Show posted date'; Key = 'hushpinterest_show_posted_date' }
        @{ Family = 'Hide Inbox updates'; Case = 'inbox'; Page = 'Notifications'; Switch = 'Hide Inbox updates'; Key = 'hushpinterest_hide_inbox_updates' }
    ),
    # The Hush settings pages, by the name each row shows in English. The walk opens every one.
    [string[]]$SettingsPages = @('Feed', 'Privacy', 'Pin actions', 'Interface', 'Links', 'Notifications',
        "Pinterest's own settings", 'Updates', 'Pause, backup and diagnostics', 'About'),
    # An unbound installed build can be surveyed, but only matching build evidence permits a
    # release walk. The sidecar comes from patch-for-device.ps1 after its checks pass.
    [switch]$SkipPatch,
    [string]$BuildEvidence,
    # Leave the Hush settings pages out of the walk. Makes the walk PARTIAL, not a release walk.
    [switch]$SkipSettings,
    [ValidateRange(1, 30)][int]$SettleSeconds = 4,
    [string]$OutDir = (Join-Path $env:TEMP 'hushpinterest-walk'),
    # Patches left out of the walked build, passed to patch-for-device.ps1, which records them in
    # the build evidence: GmsCore support and Spoof signature for Google sign-in on a phone without
    # microG-RE, where Pinterest would lose Play services. Commas split a single value.
    [string[]]$Exclude = @(),
    # Passed to patch-for-device.ps1, so the build is signed with the key the installed app uses.
    [string]$Keystore,
    [string]$KeyAlias,
    [string]$LeaseDirectory = $env:HUSHPINTEREST_DEVICE_LEASE_DIR,
    [string]$ChatIdentity = $env:HUSHPINTEREST_CHAT_ID,
    [string]$Adb,
    [string]$Aapt2,
    # The checkout whose release bundle is walked, the one holding this script unless given.
    [string]$Root
)

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'release-receipt.ps1')
. (Join-Path $PSScriptRoot 'device-install.ps1')
. (Join-Path $PSScriptRoot 'walk-receipt.ps1')

$WalkMainActivity = 'com.pinterest.activity.PinterestActivity'
# The settings list's search box, read by its hint. It sits above the list, so scrolling never hides
# it, and a page hides it, so it going away is how a page is told from the list. (The list's own
# headings scroll off the top once a lower row has been reached.)
$WalkSettingsHome = 'Search settings'
# Where Save full report leaves morphe-diagnostics-<time>-<id>.txt.
$WalkReportFolder = '/sdcard/Download/Morphe'

function Resolve-WalkOutcome {
    <#
    .SYNOPSIS
        The result a walk earns from what it saw. A crash or an unopened screen fails it, a skipped
        part makes it partial, and only a whole clean walk passes.
    #>
    param(
        [int]$Screens,
        [int]$Crashes,
        [string[]]$Problems = @(),
        [Parameter(Mandatory = $true)][bool]$Full
    )
    if ($Crashes -gt 0 -or @($Problems).Count -gt 0) { return 'FAIL' }
    if (-not $Full -or $Screens -lt 1) { return 'PARTIAL' }
    return 'PASS'
}

function Get-WalkCrashLines {
    <#
    .SYNOPSIS
        The crash buffer lines that name a Pinterest crash at or after -Since (device epoch
        seconds): a Java crash's "Process: com.pinterest" line or a native crash's
        ">>> com.pinterest <<<" line, Pinterest's own :name processes included. Another app's
        crash, or one from before the walk, is left out.
    #>
    param([string[]]$Lines, [long]$Since, [string]$Package = 'com.pinterest')
    $current = $false
    $name = [regex]::Escape($Package) + '(?::[\w.]+)?(?![\w.:])'
    foreach ($line in @($Lines)) {
        if ($line -match '^\s*(\d+)\.\d+\s') { $current = [long]$Matches[1] -ge $Since }
        if (-not $current) { continue }
        if ($line -match "Process:\s*$name" -or $line -match ">>>\s*$name\s*<<<") { $line }
    }
}

function ConvertFrom-WalkDump {
    <#
    .SYNOPSIS
        The text nodes of a uiautomator dump: text, package and the centre of their bounds.
    #>
    param([string]$Xml)
    if ([string]::IsNullOrWhiteSpace($Xml) -or $Xml -notmatch '<hierarchy') { return @() }
    $document = [xml]$Xml
    foreach ($node in $document.SelectNodes('//node')) {
        $text = [string]$node.GetAttribute('text')
        if (-not $text) { continue }
        if ($node.GetAttribute('bounds') -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { continue }
        [pscustomobject]@{
            Text = $text; Package = [string]$node.GetAttribute('package')
            X = [int](([int]$Matches[1] + [int]$Matches[3]) / 2); Y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
            Top = [int]$Matches[2]; Bottom = [int]$Matches[4]
        }
    }
}

function Find-WalkRow {
    <# The one Pinterest node whose text is exactly -Text, or $null when there isn't exactly one. #>
    param($Nodes, [string]$Text, [string]$Package = 'com.pinterest')
    $found = @($Nodes | Where-Object { $_.Package -ceq $Package -and $_.Text -ceq $Text })
    if ($found.Count -eq 1) { return $found[0] }
    return $null
}

function Find-WalkSwitch {
    <#
    .SYNOPSIS
        The switch row whose title is exactly -Title in a uiautomator dump: the title's nearest
        checkable ancestor, with its checked state and the centre of its bounds. $null when no
        title, or more than one, says -Title, or the title isn't in a switch row.
    #>
    param([string]$Xml, [string]$Title, [string]$Package = 'com.pinterest')
    if ([string]::IsNullOrWhiteSpace($Xml) -or $Xml -notmatch '<hierarchy') { return $null }
    $document = [xml]$Xml
    $titles = @($document.SelectNodes('//node') |
        Where-Object { $_.GetAttribute('package') -ceq $Package -and $_.GetAttribute('text') -ceq $Title })
    if ($titles.Count -ne 1) { return $null }
    $row = $titles[0].SelectSingleNode("ancestor::node[@checkable='true'][1]")
    if (-not $row -or $row.GetAttribute('bounds') -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { return $null }
    [pscustomobject]@{
        Checked = $row.GetAttribute('checked') -ceq 'true'
        X = [int](([int]$Matches[1] + [int]$Matches[3]) / 2); Y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    }
}

function Find-WalkButton {
    <#
    .SYNOPSIS
        The centre of the one node whose content description is exactly -Description in a
        uiautomator dump, for a button that carries no text. $null when there isn't exactly one.
    #>
    param([string]$Xml, [string]$Description, [string]$Package = 'com.pinterest')
    if ([string]::IsNullOrWhiteSpace($Xml) -or $Xml -notmatch '<hierarchy') { return $null }
    $document = [xml]$Xml
    $buttons = @($document.SelectNodes('//node') |
        Where-Object { $_.GetAttribute('package') -ceq $Package -and $_.GetAttribute('content-desc') -ceq $Description })
    if ($buttons.Count -ne 1 -or $buttons[0].GetAttribute('bounds') -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { return $null }
    [pscustomobject]@{ X = [int](([int]$Matches[1] + [int]$Matches[3]) / 2); Y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2) }
}

function Read-WalkHookReport {
    <#
    .SYNOPSIS
        The [HOOK STATUS] and [PATCHES] sections of an English diagnostics report. Status holds
        each family's counts by name (a family whose hook never ran has no line there), Patches
        each family's switches as saved, as key = 'on', 'off' or a count.
    #>
    param([string]$Text)
    $status = @{}; $patches = @{}; $notInBuild = [Collections.Generic.List[string]]::new()
    $sections = [Collections.Generic.HashSet[string]]::new()
    $section = $null
    foreach ($line in @($Text -split "`r?`n")) {
        if ($line -match '^\[([A-Z][A-Z -]*)\]\s*$') { $section = $Matches[1]; [void]$sections.Add($section); continue }
        if ($section -ceq 'HOOK STATUS' -and $line -match ('^(?<family>[^:]+): invoked (?<invoked>\d+), (?<found>\d+) found, ' +
                '(?:(?<ambiguous>\d+) ambiguous, )?(?<missing>\d+) missing(?<rest>.*)$')) {
            $parts = $Matches
            $first = if ($parts.rest -match '\. First (?:missing|ambiguous): (.+?)(?:\. Counted: .*)?$') { $Matches[1] } else { '' }
            $status[$parts.family] = [pscustomobject]@{
                Invoked = [int]$parts.invoked; Found = [int]$parts.found
                Ambiguous = if ($parts.ambiguous) { [int]$parts.ambiguous } else { 0 }
                Missing = [int]$parts.missing; First = $first
            }
        } elseif ($section -ceq 'PATCHES') {
            if ($line -match '^not in this build: (.+)$') {
                foreach ($name in $Matches[1] -split ', ') { $notInBuild.Add($name.Trim()) }
            } elseif ($line -match '^(?<family>.+?): (?:on|disabled by its switch|disabled while paused) \((?:saved )?(?<keys>[^)]*)\)') {
                $keys = @{}
                foreach ($pair in [regex]::Matches($Matches.keys, '(\w+)=(\w+)')) { $keys[$pair.Groups[1].Value] = $pair.Groups[2].Value }
                $patches[$Matches.family] = $keys
            } elseif ($line -match '^(?<family>.+?): no switch, ') {
                $patches[$Matches.family] = @{}
            }
        }
    }
    [pscustomobject]@{
        HasStatus = $sections.Contains('HOOK STATUS'); HasPatches = $sections.Contains('PATCHES')
        Status = $status; Patches = $patches; NotInBuild = @($notInBuild)
    }
}

function Resolve-WalkHooks {
    <#
    .SYNOPSIS
        A verdict for every hook family: pass or fail for each one in -Families whose case opened
        and whose switch the walk turned on, and not exercised for the rest, those the map leaves
        out included. Without a -Report every family is not exercised.
    #>
    param([object[]]$Families = @(), $Report, [string[]]$Opened = @(), [string[]]$SwitchedOn = @())
    $mapped = [Collections.Generic.HashSet[string]]::new()
    foreach ($entry in @($Families)) {
        [void]$mapped.Add($entry.Family)
        $counts = if ($Report) { $Report.Status[$entry.Family] } else { $null }
        $verdict = [ordered]@{
            family = $entry.Family; case = $entry.Case; verdict = 'not exercised'
            invoked = if ($counts) { $counts.Invoked } else { 0 }
            missing = if ($counts) { $counts.Missing } else { 0 }
            ambiguous = if ($counts) { $counts.Ambiguous } else { 0 }
            detail = ''
        }
        $where = if ($entry.Case -ceq 'home') { 'the home feed' } elseif ($entry.Case -ceq 'inbox') { 'the Inbox' } else { $entry.Case }
        if (-not $Report) { $verdict.detail = 'no diagnostics report to read' }
        elseif (@($Opened) -cnotcontains $entry.Case) { $verdict.detail = "$where didn't open" }
        elseif (@($SwitchedOn) -cnotcontains $entry.Family) { $verdict.detail = "its switch $($entry.Switch) couldn't be turned on" }
        elseif (-not $Report.Patches.ContainsKey($entry.Family)) { $verdict.detail = 'not in this build' }
        elseif ($Report.Patches[$entry.Family][$entry.Key] -cne 'on') {
            $verdict.verdict = 'fail'; $verdict.detail = "read its switch $($entry.Key) as off in the report, after the walk turned it on"
        } elseif ($verdict.invoked -eq 0) {
            $verdict.verdict = 'fail'; $verdict.detail = "never ran, with its switch on and $where open"
        } elseif ($verdict.missing -gt 0) {
            $verdict.verdict = 'fail'; $verdict.detail = "missed $($verdict.missing) of its targets (first: $($counts.First))"
        } elseif ($verdict.ambiguous -gt 0) {
            $verdict.verdict = 'fail'; $verdict.detail = "found $($verdict.ambiguous) of its targets more than once (first: $($counts.First))"
        } else {
            $verdict.verdict = 'pass'; $verdict.detail = "ran $($verdict.invoked) times on $where"
        }
        $verdict
    }
    if (-not $Report) { return }
    $others = @($Report.Patches.Keys) + @($Report.NotInBuild) | Where-Object { -not $mapped.Contains($_) } | Sort-Object -Unique
    foreach ($family in $others) {
        $counts = $Report.Status[$family]
        [ordered]@{
            family = $family; case = $null; verdict = 'not exercised'
            invoked = if ($counts) { $counts.Invoked } else { 0 }
            missing = if ($counts) { $counts.Missing } else { 0 }
            ambiguous = if ($counts) { $counts.Ambiguous } else { 0 }
            detail = if (@($Report.NotInBuild) -ccontains $family) { 'not in this build' } else { 'no case of the walk is mapped to it' }
        }
    }
}

function Invoke-WalkApp {
    param(
        [string]$Serial, [string]$Package, [string[]]$Visit, [string[]]$SettingsPages,
        [hashtable]$CaseSwipes = @{}, [object[]]$HookFamilies = @(),
        [bool]$SkipPatch, [bool]$SkipSettings, [int]$SettleSeconds, [string]$OutDir,
        [string]$BuildEvidence, [string[]]$Exclude = @(),
        [string]$Keystore, [string]$KeyAlias, [string]$LeaseDirectory, [string]$ChatIdentity,
        [string]$Adb, [string]$Aapt2, [string]$Root
    )
    if (-not $Serial) { throw 'Name the device to walk with -Serial.' }
    if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
    $Root = [IO.Path]::GetFullPath($Root)
    $OutDir = [IO.Path]::GetFullPath($OutDir)
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

    # The bundle publish will hash, named in the receipt so a walk of any other build can't stand in.
    $version = Get-BundleVersion -Root $Root
    $bundle = Get-ReleaseBundlePath -Root $Root -Version $version
    if (-not (Test-Path -LiteralPath $bundle -PathType Leaf)) {
        throw "No release bundle at $bundle. Build it first (:patches:generatePatchesList then :patches:buildAndroid, through the governor)."
    }
    $bundleSha256 = (Get-FileHash -LiteralPath $bundle -Algorithm SHA256).Hash.ToLowerInvariant()
    # Publish holds the receipt to the staged bundle, so a walk of any other build can't back it.
    # Saying so up front saves a walk that was meant for the release; a walk between releases,
    # with the last release's assets still staged, goes ahead.
    $staged = Join-Path $Root "build/release-assets/$version/patches-$version.mpp"
    if (Test-Path -LiteralPath $staged -PathType Leaf) {
        $stagedSha256 = (Get-FileHash -LiteralPath $staged -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($stagedSha256 -cne $bundleSha256) {
            Write-Host ("[walk] the staged $version release bundle isn't this build's bundle, so this walk won't back " +
                'that release. Run the release build stage again before a release walk.')
        } else {
            Write-Host "[walk] the staged release bundle is this build's bundle ($bundleSha256)"
        }
    }

    if (-not $Adb) {
        $Adb = (Get-Command adb -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1).Source
    }
    if (-not $Adb) { throw 'No adb found. Put it on the PATH or pass -Adb.' }
    $Aapt2 = Resolve-Aapt2 -Explicit $Aapt2 -Root $Root

    if (-not $SkipPatch) {
        if ($BuildEvidence) { throw '-BuildEvidence is only for a -SkipPatch walk.' }
        $patchArguments = @{ OutDir = (Join-Path $OutDir 'patch'); Root = $Root }
        if ($Keystore) { $patchArguments.Keystore = $Keystore }
        if ($KeyAlias) { $patchArguments.KeyAlias = $KeyAlias }
        $leaveOut = @($Exclude | ForEach-Object { "$_" -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
        if ($leaveOut.Count -gt 0) { $patchArguments.Exclude = [string[]]$leaveOut }
        Write-Host "[walk] patching Pinterest from $(Split-Path -Leaf $bundle)"
        $patchOut = @(& (Join-Path $PSScriptRoot 'patch-for-device.ps1') @patchArguments)
        $installApk = @($patchOut | ForEach-Object { "$_" } | Where-Object { $_ -match '\.apk$' -and (Test-Path -LiteralPath $_ -PathType Leaf) })
        if ($installApk.Count -eq 0) { throw 'patch-for-device.ps1 handed back no signed APK, so there is nothing to walk.' }
        $installApk = $installApk[-1]
        $BuildEvidence = "$installApk.build.json"
    }
    $build = $null
    if ($BuildEvidence) {
        $build = Get-Content -LiteralPath $BuildEvidence -Raw | ConvertFrom-Json
        if (-not $SkipPatch -and $build.signedApkSha256 -cne
            (Get-FileHash -LiteralPath $installApk -Algorithm SHA256).Hash.ToLowerInvariant()) {
            throw 'The signed APK differs from its build evidence.'
        }
    }

    $lease = $null
    $problems = [Collections.Generic.List[string]]::new()
    $screens = 0
    $dumpPath = '/sdcard/hushpinterest-walk.xml'
    # The cases that opened, the hook families whose switch is on, and the map entries whose
    # switch the walk tapped, which it turns back.
    $opened = [Collections.Generic.List[string]]::new()
    $switchedOn = [Collections.Generic.HashSet[string]]::new()
    $flipped = [Collections.Generic.List[object]]::new()
    $hooks = @()
    $restore = $null
    try {
        $lease = Enter-HushDeviceLease -Adb $Adb -Serial $Serial -LeaseDirectory $LeaseDirectory -ChatIdentity $ChatIdentity
        # Ownership is checked on every call. The device identity was bound when the lease was
        # taken and is checked again by the install and at the end.
        $walkAdb = {
            param([string[]]$Arguments)
            Renew-HushDeviceLease $lease
            Invoke-HushPinterestAdbCommand -Adb $Adb -Arguments (@('-s', $Serial) + $Arguments)
        }
        # A screen that's still moving (a playing video pin) can fail a dump, so a failed one is
        # taken again, twice at most. The old file goes first, so a failure never reads stale nodes.
        $readXml = {
            $xml = ''
            for ($try = 0; $try -lt 3 -and $xml -notmatch '<hierarchy'; $try++) {
                if ($try -gt 0) { Start-Sleep -Seconds 1 }
                [void](& $walkAdb @('shell', 'rm', '-f', $dumpPath))
                [void](& $walkAdb @('shell', 'uiautomator', 'dump', $dumpPath))
                $xml = (& $walkAdb @('shell', 'cat', $dumpPath)).Output -join "`n"
            }
            [void](& $walkAdb @('shell', 'rm', '-f', $dumpPath))
            $xml
        }
        $readScreen = { @(ConvertFrom-WalkDump (& $readXml)) }
        $inFront = {
            # The resumed-activity line is named differently by Android version: mResumedActivity=
            # on older ones, topResumedActivity= and "ResumedActivity:" on API 36.
            $resumed = @((& $walkAdb @('shell', 'dumpsys', 'activity', 'activities')).Output |
                Where-Object { $_ -match 'ResumedActivity' }) -join ' '
            $resumed -match "\b$([regex]::Escape($Package))/"
        }

        [void](& $walkAdb @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP'))
        $window = (& $walkAdb @('shell', 'dumpsys', 'window')).Output -join "`n"
        if ($window -match 'isKeyguardShowing=true|mDreamingLockscreen=true') {
            throw "$Serial is locked. Unlock it by hand and walk again; the walk never unlocks a device."
        }
        $size = ((& $walkAdb @('shell', 'wm', 'size')).Output -join ' ')
        $width = 720; $height = 1600
        if ($size -match '(\d+)x(\d+)\s*$') { $width = [int]$Matches[1]; $height = [int]$Matches[2] }

        # One 400 ms swipe down the middle of the screen, from -From to -To as fractions of its height.
        $drag = {
            param([double]$From, [double]$To)
            [void](& $walkAdb @('shell', 'input', 'swipe', [string][int]($width / 2), [string][int]($height * $From),
                [string][int]($width / 2), [string][int]($height * $To), '400'))
            Start-Sleep -Milliseconds 900
        }
        # The settings row whose text is exactly -Row, or the switch titled -Switch, brought in from
        # the screen's edges: on down the list first, then back up it, with a fresh dump after every
        # swipe. Only the Hush settings are on screen while this runs.
        $seek = {
            param([string]$Row, [string]$Switch, [int]$Swipes = 6)
            foreach ($leg in @(@{ From = 0.75; To = 0.35; Tries = $Swipes }, @{ From = 0.35; To = 0.75; Tries = $Swipes * 2 })) {
                $low = $null
                for ($try = 0; ; $try++) {
                    $xml = & $readXml
                    $hit = if ($Switch) { Find-WalkSwitch $xml $Switch $Package } else { Find-WalkRow (ConvertFrom-WalkDump $xml) $Row $Package }
                    if ($hit -and $hit.Y -ge $height * 0.12 -and $hit.Y -le $height * 0.85) { return $hit }
                    # A low row the last drag left where it was is the end of the list: the last
                    # row of a long page can't come up past the line, and the dump has it on screen.
                    if ($hit -and $low -and $hit.Y -gt $height * 0.85 -and $hit.Y -eq $low.Y) { return $hit }
                    $low = if ($hit -and $hit.Y -gt $height * 0.85) { $hit } else { $null }
                    if ($try -ge $leg.Tries) { break }
                    if ($hit -and $hit.Y -gt $height * 0.85) { & $drag 0.6 0.45 }
                    elseif ($hit) { & $drag 0.45 0.6 }
                    else { & $drag $leg.From $leg.To }
                }
            }
            return $null
        }
        # The Hush settings list, opened over the home feed through App info's intent.
        $openSettings = {
            [void](& $walkAdb @('shell', 'am', 'start', '-W', '-n', "$Package/$WalkMainActivity"))
            Start-Sleep -Seconds $SettleSeconds
            [void](& $walkAdb @('shell', 'am', 'start', '-W', '-a', 'android.intent.action.APPLICATION_PREFERENCES', '-p', $Package))
            Start-Sleep -Seconds $SettleSeconds
            [bool](Find-WalkRow (& $readScreen) $WalkSettingsHome $Package)
        }
        # A settings page, from the list: its row tapped, then its title showing and the list gone.
        $openPage = {
            param([string]$Page)
            $row = & $seek -Row $Page
            if (-not $row) { return $false }
            [void](& $walkAdb @('shell', 'input', 'tap', [string]$row.X, [string]$row.Y))
            Start-Sleep -Seconds 2
            $nodes = & $readScreen
            [bool]((Find-WalkRow $nodes $Page $Package) -and -not (Find-WalkRow $nodes $WalkSettingsHome $Package) -and (& $inFront))
        }
        $backToList = {
            [void](& $walkAdb @('shell', 'input', 'keyevent', 'KEYCODE_BACK'))
            Start-Sleep -Seconds 2
            [bool](Find-WalkRow (& $readScreen) $WalkSettingsHome $Package)
        }
        # Sets the switch titled -Title on the open page -Page to -On. Tapped says the walk touched
        # it, so it gets turned back; Lost, that the page wasn't in front after the tap.
        $setSwitch = {
            param([string]$Page, [string]$Title, [bool]$On)
            $found = & $seek -Switch $Title -Swipes 8
            if (-not $found) { return [pscustomobject]@{ Found = $false; Tapped = $false; Lost = $false; On = $false } }
            if ($found.Checked -eq $On) { return [pscustomobject]@{ Found = $true; Tapped = $false; Lost = $false; On = $found.Checked } }
            [void](& $walkAdb @('shell', 'input', 'tap', [string]$found.X, [string]$found.Y))
            Start-Sleep -Milliseconds 1500
            if (-not (Find-WalkRow (& $readScreen) $Page $Package)) {
                return [pscustomobject]@{ Found = $true; Tapped = $true; Lost = $true; On = -not $On }
            }
            $after = & $seek -Switch $Title -Swipes 3
            [pscustomobject]@{ Found = $true; Tapped = $true; Lost = $false; On = [bool]$after -and $after.Checked }
        }
        # Every switch in -Entries set to -On, a page at a time. Answers the entries whose switch
        # this tapped; a switch that won't take is a problem named after its family.
        $setSwitches = {
            param([object[]]$Entries, [bool]$On)
            $tapped = [Collections.Generic.List[object]]::new()
            $word = if ($On) { 'on' } else { 'back off' }
            foreach ($page in @($Entries | ForEach-Object { $_.Page } | Select-Object -Unique)) {
                if (-not (& $openPage $page)) {
                    $problems.Add("the settings page $page would not open to turn its switches $word")
                    if (& $backToList) { continue } else { break }
                }
                $lost = $false
                foreach ($entry in @($Entries | Where-Object { $_.Page -ceq $page })) {
                    $state = & $setSwitch $page $entry.Switch $On
                    if ($state.Tapped) { $tapped.Add($entry) }
                    if (-not $state.Found) { $problems.Add("$($entry.Family): the switch $($entry.Switch) isn't on the $page page") }
                    elseif ($state.Lost) { $problems.Add("$($entry.Family): after its switch $($entry.Switch) was tapped, the $page page wasn't in front"); $lost = $true; break }
                    elseif ($state.On -ne $On) { $problems.Add("$($entry.Family): the switch $($entry.Switch) on the $page page wouldn't turn $word") }
                    elseif ($On) { [void]$switchedOn.Add($entry.Family) }
                }
                if (-not (& $backToList) -or $lost) {
                    $problems.Add("Back from $page didn't return to the settings list while its switches were turned $word")
                    break
                }
            }
            , $tapped
        }
        # Save full report from Pause, backup and diagnostics, copied to the output folder, and the
        # phone's copy deleted. Answers the local path or the problem that stopped it.
        $exportReport = {
            if (-not (& $openSettings)) { return [pscustomobject]@{ Path = $null; Problem = 'the Hush settings would not open to export the diagnostics report' } }
            if (-not (& $openPage 'Pause, backup and diagnostics')) {
                return [pscustomobject]@{ Path = $null; Problem = 'Pause, backup and diagnostics would not open to export the diagnostics report' }
            }
            $row = & $seek -Row 'Export diagnostic report'
            if (-not $row) { return [pscustomobject]@{ Path = $null; Problem = 'Pause, backup and diagnostics has no Export diagnostic report row' } }
            $listing = {
                @((& $walkAdb @('shell', 'ls', '-1', $WalkReportFolder)).Output | ForEach-Object { $_.Trim() } |
                    Where-Object { $_ -match '^morphe-diagnostics-[\w.-]+\.txt$' })
            }
            $before = @(& $listing)
            [void](& $walkAdb @('shell', 'input', 'tap', [string]$row.X, [string]$row.Y))
            Start-Sleep -Seconds 2
            $save = Find-WalkRow (& $readScreen) 'Save full report' $Package
            if (-not $save) {
                [void](& $walkAdb @('shell', 'input', 'keyevent', 'KEYCODE_BACK'))
                return [pscustomobject]@{ Path = $null; Problem = 'Export diagnostic report offered no Save full report' }
            }
            [void](& $walkAdb @('shell', 'input', 'tap', [string]$save.X, [string]$save.Y))
            $new = @()
            for ($wait = 0; $wait -lt 10 -and $new.Count -eq 0; $wait++) {
                Start-Sleep -Seconds 2
                $new = @(& $listing | Where-Object { $before -cnotcontains $_ })
            }
            if ($new.Count -ne 1) { return [pscustomobject]@{ Path = $null; Problem = "Save full report left $($new.Count) new reports in $WalkReportFolder" } }
            $local = Join-Path $OutDir 'diagnostics.txt'
            if (Test-Path -LiteralPath $local) { Remove-Item -LiteralPath $local -Force }
            $pulled = & $walkAdb @('pull', "$WalkReportFolder/$($new[0])", $local)
            # The media store's row goes with the file, so the report doesn't linger in Downloads.
            [void](& $walkAdb @('shell', "content delete --uri content://media/external/file --where _display_name=\'$($new[0])\'"))
            [void](& $walkAdb @('shell', 'rm', '-f', "$WalkReportFolder/$($new[0])"))
            [void](& $backToList)
            if ($pulled.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $local -PathType Leaf)) {
                return [pscustomobject]@{ Path = $null; Problem = "the diagnostics report wouldn't copy off the phone" }
            }
            Write-Host "[walk] saved the diagnostics report to $local and deleted the phone's copy"
            [pscustomobject]@{ Path = $local; Problem = $null }
        }
        # The switches the walk turned on, turned back off. Runs once, from the walk's end or from
        # the finally when something stopped it.
        $restore = {
            if ($flipped.Count -eq 0) { return }
            Write-Host "[walk] turning the $($flipped.Count) switches it turned on back off"
            $known = $problems.Count
            try {
                if (& $openSettings) { [void](& $setSwitches ([object[]]$flipped) $false) }
                else { $problems.Add('the Hush settings would not open to turn the walk''s switches back off') }
                [void](& $walkAdb @('shell', 'input', 'keyevent', 'KEYCODE_BACK'))
                Start-Sleep -Seconds 1
            } catch {
                $problems.Add("turning the walk's switches back off stopped: $($_.Exception.Message)")
            }
            for ($i = $known; $i -lt $problems.Count; $i++) { Write-Host "[walk] $($problems[$i])" }
            $flipped.Clear()
        }

        if (-not $SkipPatch) {
            Write-Host "[walk] installing $(Split-Path -Leaf $installApk) on $Serial"
            try {
                Install-HushAndroidApk -Adb $Adb -Serial $Serial -PackageName $Package -Apk $installApk -Aapt2 $Aapt2 -Lease $lease
            } catch {
                throw ("The build would not install on ${Serial}: $($_.Exception.Message) If the signer differs, patch with the key " +
                    'the installed app uses (-Keystore, -KeyAlias). The installed app and its data were left alone.')
            }
        }
        $started = [DateTimeOffset]::UtcNow
        $startedEpoch = [long]((& $walkAdb @('shell', 'date', '+%s')).Output -join '').Trim()
        $checkHooks = -not $SkipSettings -and @($HookFamilies).Count -gt 0
        if ($checkHooks) {
            Write-Host "[walk] turning on the switches of $(@($HookFamilies).Count) hook families"
            if (& $openSettings) { $flipped.AddRange([object[]](& $setSwitches $HookFamilies $true)) }
            else { $problems.Add('the Hush settings would not open to turn the hook families'' switches on') }
            Write-Host "[walk] $($switchedOn.Count) switches on, $($flipped.Count) of them turned on by the walk"
        }
        # The restart also clears the hook counts, so the report counts only what the cases reach.
        [void](& $walkAdb @('shell', 'am', 'force-stop', $Package))
        $launch = & $walkAdb @('shell', 'am', 'start', '-W', '-n', "$Package/$WalkMainActivity")
        Start-Sleep -Seconds ($SettleSeconds * 2)
        if (($launch.Output -join "`n") -match '(?m)^Status:\s*ok\s*$' -and (& $inFront)) {
            $screens++
            $opened.Add('home')
            Write-Host '[walk] opened the home feed'
        } else {
            $problems.Add('Pinterest would not start on its home feed')
        }

        foreach ($link in $Visit) {
            $start = & $walkAdb @('shell', 'am', 'start', '-W', '-a', 'android.intent.action.VIEW', '-d', "'$link'", '-p', $Package)
            Start-Sleep -Seconds $SettleSeconds
            $ok = ($start.Output -join "`n") -match '(?m)^Status:\s*ok\s*$'
            $front = & $inFront
            if ($ok -and $front) {
                $screens++
                $opened.Add($link)
                Write-Host "[walk] opened $link"
                $swipes = if ($CaseSwipes -and $CaseSwipes.ContainsKey($link)) { [int]$CaseSwipes[$link] } else { 0 }
                # What comes into view loads from the network, so each swipe gets time to bind it.
                for ($done = 0; $done -lt $swipes -and (& $inFront); $done++) { & $drag 0.75 0.35; Start-Sleep -Milliseconds 600 }
                if ($swipes -gt 0) { Write-Host "[walk] swiped $link up $done times" }
            } else {
                $problems.Add("$link would not open (status ok: $ok, Pinterest in front: $front)")
                Write-Host "[walk] $link would not open"
            }
        }

        # The Inbox, from the home feed. Each tap is on a button read from a dump taken after the
        # last change of screen, and the Inbox itself is only read: it lists suggested people.
        $inboxOpen = $false
        $inboxStop = 'Pinterest wasn''t in front'
        $homeTab = Find-WalkButton (& $readXml) 'Home' $Package
        if (-not $homeTab) { $inboxStop = 'the bottom bar''s Home button wasn''t on screen' }
        elseif (& $inFront) {
            [void](& $walkAdb @('shell', 'input', 'tap', [string]$homeTab.X, [string]$homeTab.Y))
            Start-Sleep -Seconds $SettleSeconds
            $inboxButton = Find-WalkButton (& $readXml) 'Inbox' $Package
            if (-not $inboxButton) { $inboxStop = 'the home feed showed no Inbox button' }
            elseif (& $inFront) {
                [void](& $walkAdb @('shell', 'input', 'tap', [string]$inboxButton.X, [string]$inboxButton.Y))
                Start-Sleep -Seconds $SettleSeconds
                $inboxOpen = [bool](Find-WalkRow (& $readScreen) 'Inbox' $Package) -and (& $inFront)
                if (-not $inboxOpen) { $inboxStop = 'the Inbox button didn''t open a page titled Inbox' }
            }
        }
        if ($inboxOpen) {
            $screens++
            $opened.Add('inbox')
            Write-Host '[walk] opened the Inbox from the home feed'
            [void](& $walkAdb @('shell', 'input', 'keyevent', 'KEYCODE_BACK'))
            Start-Sleep -Seconds 1
        } else {
            $problems.Add("the Inbox would not open from the home feed's Inbox button: $inboxStop")
            Write-Host "[walk] the Inbox would not open: $inboxStop"
        }

        $full = -not $SkipSettings -and $null -ne $build
        if (-not $SkipSettings) {
            if (-not (& $openSettings)) {
                $problems.Add('the Hush settings would not open from App info''s intent')
                Write-Host '[walk] the Hush settings would not open'
            } else {
                $screens++
                Write-Host '[walk] opened the Hush settings'
                $nodes = & $readScreen
                foreach ($page in $SettingsPages) {
                    $row = Find-WalkRow $nodes $page $Package
                    # Pages lower in the list come into view one swipe at a time, each followed by a
                    # fresh dump. The list is the Hush settings; nothing else is on screen to touch.
                    for ($swipe = 0; -not $row -and $swipe -lt 6; $swipe++) {
                        [void](& $walkAdb @('shell', 'input', 'swipe', [string]($width / 2), [string][int]($height * 0.75),
                            [string]($width / 2), [string][int]($height * 0.35), '400'))
                        Start-Sleep -Milliseconds 900
                        $nodes = & $readScreen
                        $row = Find-WalkRow $nodes $page $Package
                    }
                    if (-not $row) {
                        $problems.Add("the settings page $page isn't in the list")
                        Write-Host "[walk] no $page row in the settings list"
                        continue
                    }
                    [void](& $walkAdb @('shell', 'input', 'tap', [string]$row.X, [string]$row.Y))
                    Start-Sleep -Seconds 2
                    $nodes = & $readScreen
                    $onPage = (Find-WalkRow $nodes $page $Package) -and -not (Find-WalkRow $nodes $WalkSettingsHome $Package)
                    if ($onPage -and (& $inFront)) {
                        $screens++
                        Write-Host "[walk] opened the settings page $page"
                    } else {
                        $problems.Add("the settings page $page would not open")
                        Write-Host "[walk] the settings page $page would not open"
                    }
                    [void](& $walkAdb @('shell', 'input', 'keyevent', 'KEYCODE_BACK'))
                    Start-Sleep -Seconds 2
                    $nodes = & $readScreen
                    if (-not (Find-WalkRow $nodes $WalkSettingsHome $Package)) {
                        $problems.Add("Back from $page didn't return to the settings list")
                        Write-Host "[walk] Back from $page didn't return to the settings list"
                        break
                    }
                }
            }
            [void](& $walkAdb @('shell', 'input', 'keyevent', 'KEYCODE_BACK'))
            Start-Sleep -Seconds 1
        }

        if ($checkHooks) {
            $export = & $exportReport
            $report = $null
            if ($export.Problem) {
                $problems.Add("$($export.Problem), so the hook families weren't checked")
            } else {
                $report = Read-WalkHookReport ([IO.File]::ReadAllText($export.Path))
                if (-not $report.HasStatus -or -not $report.HasPatches) {
                    $problems.Add('the diagnostics report has no [HOOK STATUS] or no [PATCHES] section, so the hook families weren''t checked')
                    $report = $null
                }
            }
            $hooks = @(Resolve-WalkHooks -Families $HookFamilies -Report $report -Opened ([string[]]$opened) `
                -SwitchedOn ([string[]]@($switchedOn)))
            $failed = @($hooks | Where-Object { $_.verdict -ceq 'fail' })
            foreach ($hook in $failed) { $problems.Add("$($hook.family) $($hook.detail)") }
            Write-Host ("[walk] hook families: $(@($hooks | Where-Object { $_.verdict -ceq 'pass' }).Count) passed, $($failed.Count) failed, " +
                "$(@($hooks | Where-Object { $_.verdict -ceq 'not exercised' }).Count) not exercised")
            foreach ($hook in $hooks) { Write-Host "[walk]   $($hook.verdict): $($hook.family), $($hook.detail)" }
        }
        & $restore

        $crashLines =@((& $walkAdb @('logcat', '-b', 'crash', '-d', '-v', 'epoch')).Output)
        $fatal = @(Get-WalkCrashLines -Lines $crashLines -Since $startedEpoch -Package $Package)
        if ($fatal.Count -gt 0) {
            [IO.File]::WriteAllLines((Join-Path $OutDir 'crash-buffer.txt'), [string[]]$crashLines)
            $problems.Add("Pinterest crashed during the walk: $($fatal[0].Trim())")
        }
        $alive = ((& $walkAdb @('shell', 'pidof', $Package)).Output -join '').Trim()
        if (-not $alive) { $problems.Add('Pinterest was no longer running when the walk ended') }

        $identity = Confirm-HushLeasedDevice -Adb $Adb -Lease $lease
        $packageLines = @((& $walkAdb @('shell', 'dumpsys', 'package', $Package)).Output)
        $appVersion = @($packageLines |
            Where-Object { $_ -match 'versionName=(.+)$' } | ForEach-Object { $Matches[1].Trim() } | Select-Object -First 1) -join ''
        $appVersionCode = @($packageLines | Where-Object { $_ -match '\bversionCode=(\d+)' } |
            ForEach-Object { $Matches[1] } | Select-Object -First 1) -join ''
        $apkPath = @((& $walkAdb @('shell', 'pm', 'path', $Package)).Output | Where-Object { $_ -like 'package:*base.apk' }) | Select-Object -First 1
        $installedSha = ''
        if ($apkPath) {
            $installedSha = (((& $walkAdb @('shell', 'sha256sum', $apkPath.Substring(8).Trim())).Output -join '') -split '\s+')[0]
        }

        $full = $full -and $problems.Count -eq 0
        $result = Resolve-WalkOutcome -Screens $screens -Crashes $fatal.Count -Problems ([string[]]$problems) -Full $full
        $receipt = New-WalkReceipt -Result $result -Full $full -Serial $Serial -Device $identity.Model `
            -AppVersion $appVersion -AppVersionCode $appVersionCode -Build $build `
            -BundleSha256 $bundleSha256 -InstalledApkSha256 $installedSha `
            -Started $started -Finished ([DateTimeOffset]::UtcNow) -Screens $screens -Crashes $fatal.Count `
            -Problems ([string[]]$problems) -Hooks $hooks
        $receiptPath =Join-Path $OutDir 'walk-receipt.json'
        [IO.File]::WriteAllText($receiptPath, (ConvertTo-Json -InputObject $receipt -Depth 8) + "`n")
        if ($result -eq 'PASS') {
            $validated = Test-WalkReceipt -Path $receiptPath -BundlePath $bundle -PatchList (Join-Path $Root 'patches-list.json')
            if (-not $validated.Valid) {
                $problems.Add($validated.Reason)
                $result = 'FAIL'
                $receipt.result = $result
                $receipt.full = $false
                $receipt.problems = [string[]]$problems
                [IO.File]::WriteAllText($receiptPath, (ConvertTo-Json -InputObject $receipt -Depth 8) + "`n")
            }
        }

        Write-Host "[walk] receipt $receiptPath"
        foreach ($problem in $problems) { Write-Host "[walk]   $problem" }
        if ($result -ne 'PASS') { Write-Host '[walk] this walk does not back a release.' }
        Write-Host "[walk] $result on $($identity.Model) ($Serial): $screens screens, $($fatal.Count) crashes, $($problems.Count) problems"
        return $receiptPath
    } finally {
        if ($restore -and $flipped.Count -gt 0) { & $restore }
        if ($lease) { Exit-HushDeviceLease $lease }
    }
}

if ($MyInvocation.InvocationName -ne '.') {
    $receiptPath = Invoke-WalkApp -Serial $Serial -Package $Package -Visit $Visit -SettingsPages $SettingsPages `
        -CaseSwipes $CaseSwipes -HookFamilies $HookFamilies -SkipPatch:$SkipPatch -SkipSettings:$SkipSettings `
        -BuildEvidence $BuildEvidence -Exclude $Exclude -SettleSeconds $SettleSeconds -OutDir $OutDir `
        -Keystore $Keystore -KeyAlias $KeyAlias -LeaseDirectory $LeaseDirectory -ChatIdentity $ChatIdentity `
        -Adb $Adb -Aapt2 $Aapt2 -Root $Root
    $walked = Get-Content -LiteralPath $receiptPath -Raw | ConvertFrom-Json
    if ($walked.result -cne 'PASS') { exit 1 }
}
