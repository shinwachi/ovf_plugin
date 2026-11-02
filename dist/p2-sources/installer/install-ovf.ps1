# Install, check or remove the OVF Server Explorer plugin for KNIME on Windows.
#
#   install-ovf.cmd                          ask for everything
#   install-ovf.cmd -Knime DIR -Check        report only, change nothing
#   install-ovf.cmd -Knime DIR -Mode admin|user [-Uninstall] [-Yes]
#   install-ovf.cmd -Knime DIR -Mode admin -ApplyPermissions
#
# admin: into the KNIME folder, for everyone. Run as administrator.
# user:  into your user profile, for you only. Needs no special rights.
param(
    [string]$Knime = "",
    [ValidateSet("", "admin", "user")][string]$Mode = "",
    [switch]$Check,
    [switch]$Uninstall,
    [switch]$ApplyPermissions,
    [switch]$Yes
)
$ExpectMajor = "@KNIME_MAJOR@"
$MinFreeMb = 100
$IU = "org.ovf.serverconnector.feature.feature.group"
$OnWindows = ($env:OS -eq "Windows_NT")
$Exe = if ($OnWindows) { "knime.exe" } else { "knime" }
$UserHome = if ($OnWindows) { $env:USERPROFILE } else { $env:HOME }

function Die($msg) { Write-Host "ERROR: $msg" -ForegroundColor Red; exit 1 }
function Confirm-Step($msg) {
    if ($Yes) { return $true }
    return (Read-Host "$msg [y/N]") -match '^[yY]$'
}
function Test-Admin {
    if (-not $OnWindows) { return (& id -u) -eq "0" }
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    return (New-Object Security.Principal.WindowsPrincipal($id)).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)
}

if (-not $Knime) {
    $candidates = @()
    foreach ($root in @($env:ProgramFiles, "C:\Program Files", $UserHome, "/opt")) {
        if ($root -and (Test-Path $root)) {
            $candidates += Get-ChildItem -Path $root -Directory -Filter "knime*" -ErrorAction SilentlyContinue |
                ForEach-Object { $_.FullName }
        }
    }
    foreach ($c in $candidates) {
        if (Test-Path (Join-Path $c $Exe)) { $Knime = $c; break }
    }
    if (-not $Yes) {
        $a = Read-Host "KNIME folder (the one holding $Exe) [$Knime]"
        if ($a) { $Knime = $a }
    }
}
if (-not $Knime) { Die "KNIME folder not found; pass -Knime DIR" }
$Knime = $Knime.Trim('"').TrimEnd('\')
if (-not (Test-Path (Join-Path $Knime $Exe)) -or
    -not (Test-Path (Join-Path $Knime "plugins")) -or
    -not (Test-Path (Join-Path $Knime "configuration"))) {
    Die "$Knime is not a KNIME folder (expected $Exe, plugins, configuration)"
}
$Knime = (Resolve-Path $Knime).ProviderPath
$Plugins = Join-Path $Knime "plugins"

$core = Get-ChildItem $Plugins -Filter "org.knime.core_*" | Select-Object -First 1
if ($core.Name -notmatch '^org\.knime\.core_(\d+)') { Die "can't tell which KNIME version this is" }
$Major = $Matches[1]
if ($Major -ne $ExpectMajor) {
    Die "this installer is for KNIME $ExpectMajor.x but $Knime is KNIME $Major.x; get the matching zip"
}

# Can this account create new files, and change the ones KNIME needs to lock?
# Windows ACLs can allow the first and not the second, which breaks KNIME.
$Lock = Join-Path $Knime "configuration\org.eclipse.osgi\.manager\.fileTableLock"
if (-not $OnWindows) { $Lock = $Lock.Replace('\', '/') }
$Probe = Join-Path (Join-Path $Knime "configuration") (".ovf_probe." + $PID)
$State = "readonly"
try {
    [IO.File]::WriteAllText($Probe, "")
    [IO.File]::Delete($Probe)
    $State = "writable"
    foreach ($f in @($Lock, (Join-Path (Join-Path $Knime "configuration") "config.ini"))) {
        if (Test-Path -LiteralPath $f) {
            try { $s = [IO.File]::Open($f, "Open", "Write", "ReadWrite"); $s.Close() }
            catch { $State = "broken" }
        }
    }
} catch { }

$Private = Get-ChildItem (Join-Path $UserHome ".eclipse") -Directory -ErrorAction SilentlyContinue |
    Select-Object -First 1 -ExpandProperty FullName
if (-not $Private) { $Private = (Join-Path $UserHome ".eclipse") + "\<id>_win32_win32_x86_64" }

# knime.ini can move KNIME's configuration elsewhere. The director doesn't read
# knime.ini, so a just-for-me install has to be told, or KNIME never sees the plugin.
$Conf = ""
$iniLines = @(Get-Content -LiteralPath (Join-Path $Knime "knime.ini") -ErrorAction SilentlyContinue)
for ($i = 0; $i -lt $iniLines.Count -and -not $Conf; $i++) {
    if ($iniLines[$i] -eq "-configuration" -and $i + 1 -lt $iniLines.Count) { $Conf = $iniLines[$i + 1] }
    elseif ($iniLines[$i] -match '^-Dosgi\.configuration\.area=(.*)$') { $Conf = $Matches[1] }
}

function Show-Files {
    Write-Host @"

Where the files are
  KNIME folder       $Knime
    Program files, shared by everyone on this machine.
    An all-users install puts the plugin in its plugins folder.
  Your private area  $Private
    Created by KNIME. A just-for-me install puts the plugin in plugins here.
    To open it, paste the path into the File Explorer address bar.
  Your workspace     the folder KNIME asks for at startup, by default
                     $UserHome\knime-workspace
    Your layout and settings: .metadata. Each person needs their own.

Recommended permissions
  KNIME folder       Administrators and SYSTEM: Full control.
                     Users: Read & execute only, for all files in it.
                     In an administrator Command Prompt:
                       icacls "$Knime" /reset /T /C /Q
                       icacls "$Knime" /inheritance:r /grant:r *S-1-5-32-544:(OI)(CI)F *S-1-5-18:(OI)(CI)F *S-1-5-32-545:(OI)(CI)RX
                     Check it: right-click the folder > Properties > Security.
  Private area       Only you; Windows sets this up. Don't share it.
  Workspace          Only you. Two people must not open the same workspace.

  Avoid: Everyone or Users with Modify or Full control on the KNIME folder. One
  user can then remove the plugin for everyone, and if one user's files can't
  be changed by the next, the next user's KNIME may not start.
  Avoid too: "Create files" without "Modify". KNIME then can't lock its files:
  installs fail and nodes go missing.
"@
}

Write-Host ""
Write-Host "KNIME folder: $Knime (KNIME $Major.x)"
switch ($State) {
    "readonly" { Write-Host "This account:  can't write to it. This is the recommended setup." }
    "writable" { Write-Host "This account:  can write to it." }
    "broken"   {
        Write-Host "This account:  can create files in it but can't change KNIME's own files."
        Write-Host "               KNIME misbehaves like this (failed installs, missing nodes)."
        Write-Host "               An administrator needs to fix the permissions below."
    }
}
if ($Conf) { Write-Host "knime.ini:     keeps KNIME's configuration in $Conf" }
$IsAdmin = Test-Admin
if ($Check) { Show-Files; exit 0 }

if (-not $Mode) {
    $rec = if ($State -eq "readonly" -and -not $IsAdmin) { "2" } else { "1" }
    Write-Host ""
    Write-Host "Who should get the plugin?"
    Write-Host "  1) All users (admin): into the KNIME folder. Run as administrator."
    Write-Host "  2) Just me (user):    into your user profile. Others won't see it."
    $a = Read-Host "Choice [$rec]"
    if (-not $a) { $a = $rec }
    switch ($a) { "1" { $Mode = "admin" } "2" { $Mode = "user" } default { Die "enter 1 or 2" } }
}

$Dest = @()
$ConfArg = @()
if ($Mode -eq "admin") {
    if ($State -ne "writable") {
        Die "all-users install needs write access. Right-click install-ovf.cmd > Run as administrator."
    }
    # The director would rewrite that setting in knime.ini, and KNIME then won't start.
    if ($Conf) {
        Die "knime.ini sets KNIME's configuration to $Conf. An all-users install would damage that setting and KNIME would no longer start. Take the setting out of $Knime\knime.ini, run this again, then put it back."
    }
    $Dest = @("-destination", $Knime, "-profile", "KNIMEProfile")
} else {
    if ($State -eq "broken") {
        Die "KNIME can't manage its own files for this account. Ask an administrator to fix the permissions. See: install-ovf.cmd -Knime `"$Knime`" -Check"
    }
    if ($IsAdmin -and $OnWindows) {
        Write-Host "You're running as administrator: the plugin would go to this admin account or the KNIME folder."
        if (-not (Confirm-Step "Continue anyway?")) { exit 1 }
    }
    if ($Conf) { $ConfArg = @("-configuration", $Conf) }
    if ($State -eq "readonly" -and -not $Conf) {
        # KNIME keeps a read-only install's private files here, and says nothing if it can't.
        $PrivateRoot = Join-Path $UserHome ".eclipse"
        if (-not (Test-Path -LiteralPath $PrivateRoot)) { $PrivateRoot = $UserHome }
        $homeProbe = Join-Path $PrivateRoot (".ovf_probe." + $PID)
        try { [IO.File]::WriteAllText($homeProbe, ""); [IO.File]::Delete($homeProbe) }
        catch { Die "KNIME needs to write to $PrivateRoot and this account can't. Fix that folder's permissions, then run this again." }
    }
    if ($State -eq "writable" -and -not $Conf) {
        Write-Host ""
        Write-Host "You can write to this KNIME folder, so KNIME will install there, not in"
        Write-Host "your user profile. Anyone else using this KNIME will get the plugin too."
        if (-not (Confirm-Step "Continue?")) { exit 1 }
    }
}

$running = Get-Process -Name "knime" -ErrorAction SilentlyContinue
if ($running) {
    Write-Host "KNIME appears to be running. People need to restart it afterwards."
    if ($Mode -eq "admin" -and -not (Confirm-Step "Continue anyway?")) { exit 1 }
}

$jreName = if ($OnWindows) { "org.knime.binary.jre.win32.x86_64_*" } else { "org.knime.binary.jre.linux.x86_64_*" }
$javaExe = if ($OnWindows) { "java.exe" } else { "java" }
$Java = $null
foreach ($jre in Get-ChildItem $Plugins -Directory -Filter $jreName) {
    foreach ($sub in @("jre", ".")) {
        $p = Join-Path (Join-Path (Join-Path $jre.FullName $sub) "bin") $javaExe
        if (Test-Path $p) { $Java = $p }
    }
}
if (-not $Java) { Die "KNIME's bundled Java not found" }
$Launcher = (Get-ChildItem $Plugins -Filter "org.eclipse.equinox.launcher_*.jar" | Select-Object -First 1).FullName
$Site = Get-ChildItem $PSScriptRoot -Filter "ovf-serverconnector-*-updatesite.zip" | Select-Object -First 1
if (-not $Site) { Die "update-site zip not found next to this script" }
$Repo = "jar:" + (New-Object Uri($Site.FullName)).AbsoluteUri + "!/"

function Invoke-Director {
    & $Java -jar $Launcher -debug -nosplash @ConfArg -application org.eclipse.equinox.p2.director `
        -data "@noDefault" @Dest -repository $Repo @args 2>&1 | ForEach-Object { "$_" } | Set-Content -Path $Log -Encoding UTF8
    $script:rc = $LASTEXITCODE
    $script:text = Get-Content $Log -Encoding UTF8
    $script:text | Where-Object { $_ -match '^(Installing|Uninstalling|Operation completed)|^Cannot|read only|Permission denied|locked' } |
        Sort-Object -Unique | ForEach-Object { Write-Host $_ }
}

# What the director said went wrong: the lines after "Installation failed."
function Get-FailureReason {
    $lines = @($script:text)
    $start = -1
    for ($i = 0; $i -lt $lines.Count; $i++) { if ("$($lines[$i])" -match 'Installation failed\.\s*$') { $start = $i } }
    if ($start -lt 0) { return @() }
    $reason = @()
    for ($i = $start + 1; $i -lt $lines.Count; $i++) {
        $l = "$($lines[$i])".Trim()
        if ($l -match '^Application failed') { break }
        if ($l -and $l -notmatch 'session context was|^There were errors') { $reason += $l }
    }
    return $reason
}

function Show-Failure {
    $reason = @(Get-FailureReason)
    Write-Host ""
    if ($reason.Count -eq 0) {
        Write-Host "KNIME's installer stopped without giving a reason."
        Write-Host "Another install may be running, or this account can't write where it needs to."
        return
    }
    Write-Host "KNIME's installer says:"
    $reason | ForEach-Object { Write-Host "  $_" }
    Write-Host ""
    $all = $reason -join " "
    if ($all -match 'artifact file for osgi\.bundle,([^,]+),(\S+) was not found') {
        $jar = "$($Matches[1])_$($Matches[2]).jar"
        if ($jar -like "org.ovf.*") {
            Write-Host "The plugin was installed for everyone, but its jar is gone from the KNIME folder."
            Write-Host "An administrator needs to run: install-ovf.cmd -Knime `"$Knime`" -Mode admin"
        } else {
            Write-Host "$jar is missing, and it belongs to another extension, not this plugin."
            Write-Host "No just-for-me install can work until an administrator restores that jar or"
            Write-Host "removes that extension. An all-users install still works."
        }
    }
    if ($all -match 'required items could not be found') {
        Write-Host "If another install was running at the same time, wait for it and run this again."
        Write-Host "Otherwise this build may not match this KNIME."
    }
    if ($all -match 'Unable to load repositories') {
        if (Test-Jar $Site.FullName) {
            Write-Host "The update-site zip is fine, so another install was probably running at the"
            Write-Host "same time. Wait for it to finish, then run this again."
        } else {
            Write-Host "The update-site zip next to this script can't be read: $($Site.FullName)"
            Write-Host "It may be damaged. Download the installer zip again."
        }
    }
}

function Show-NotLoaded {
    Write-Host "Your install record no longer includes the plugin, because an administrator changed"
    Write-Host "this KNIME. KNIME may still offer it in a Migration wizard at startup: choose Cancel,"
    Write-Host "then Yes, and it stops asking."
}

function Test-Director {
    if ($script:rc -ne 0 -or -not ($script:text -match '^Operation completed')) {
        Show-Failure
        Write-Host "Full log: $Log"
        Die "the install failed"
    }
}

# The jar a configuration folder tells KNIME to load. Paths in bundles.info are
# relative to the KNIME folder and URL-encoded. Empty if it doesn't list the plugin.
function Get-ListedJar($area) {
    $info = Join-Path $area "org.eclipse.equinox.simpleconfigurator\bundles.info"
    if (-not (Test-Path -LiteralPath $info)) { return "" }
    $line = Get-Content -LiteralPath $info -Encoding UTF8 | Where-Object { $_ -like "org.ovf.serverconnector,*" } |
        Sort-Object { [version](($_ -split ',')[1] -replace '^(\d+\.\d+\.\d+).*', '$1') } | Select-Object -Last 1
    if (-not $line) { return "" }
    $rel = [Uri]::UnescapeDataString(($line -split ',')[2]) -replace '^file:/?', ''
    if ([IO.Path]::IsPathRooted($rel)) { return [IO.Path]::GetFullPath($rel) }
    return [IO.Path]::GetFullPath((Join-Path $Knime $rel))
}

function Test-Jar($path) {
    if (-not $path -or -not (Test-Path -LiteralPath $path)) { return $false }
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        [IO.Compression.ZipFile]::OpenRead($path).Dispose()
        return $true
    } catch { return $false }
}

Write-Host ""
Write-Host "Running the KNIME installer..."
try { $Log = [IO.Path]::GetTempFileName() }
catch { Die "can't create a temporary file in $env:TEMP. Free some space there, or set TEMP to a folder you can write to." }
# The installer writes to stderr. PowerShell must not treat that as failure, and converting
# each line to a string stops it wrapping them at the console width.
$ErrorActionPreference = "Continue"
if ($Uninstall) { Invoke-Director -uninstallIU $IU } else { Invoke-Director -installIU $IU }
if (-not $Uninstall -and ($text -match 'conflicting dependency')) {
    # An older version is installed. p2 won't hold two, so swap them in one step.
    Write-Host "Replacing the installed version..."
    Invoke-Director -uninstallIU $IU -installIU $IU
}

$SharedArea = Join-Path $Knime "configuration"
$Area = $SharedArea
if ($Mode -eq "user") {
    $i = [array]::IndexOf(@($text), "Configuration location:")
    if ($i -ge 0) { $Area = ([Uri]$text[$i + 1].Trim()).LocalPath.TrimEnd('\', '/') }
    if ($Area -ne $SharedArea) { $Private = Split-Path $Area }
}

# Nothing in the install record to remove. That's fine unless a copy is still listed.
$NotInProfile = $Uninstall -and $rc -ne 0 -and ((Get-FailureReason) -join " ") -match 'installable unit .* has not been found'
if (-not $NotInProfile) { Test-Director }

# The director's success only means its records agree. Check what KNIME will load.
Write-Host ""
if ($Uninstall) {
    $Jar = Get-ListedJar $SharedArea
    if ($Jar) {
        Remove-Item $Log
        Write-Host "The plugin is still installed: $Jar"
        Write-Host "That copy was installed for everyone, so KNIME will keep loading it."
        Write-Host "To remove it, an administrator runs:"
        Write-Host "  install-ovf.cmd -Knime `"$Knime`" -Mode admin -Uninstall"
        exit 1
    }
    $Jar = Get-ListedJar $Area
    if ($Jar -and $NotInProfile) {
        Show-NotLoaded
    } elseif ($Jar) {
        # Seen after an admin installed and removed the plugin over a just-for-me copy.
        Remove-Item $Log -ErrorAction SilentlyContinue
        Write-Host "The plugin is still listed for you, and KNIME will keep loading it: $Jar"
        Write-Host "To clear it, quit KNIME and delete your private area. That also removes any other"
        Write-Host "extensions you installed for yourself in this KNIME: $Private"
        exit 1
    } elseif ($NotInProfile) {
        Write-Host "The plugin isn't installed for this account, so there's nothing to remove."
    } else {
        Write-Host "Removed. If a Server Explorer tab now shows an error, close the tab."
    }
} else {
    $Jar = Get-ListedJar $Area
    if (-not (Test-Jar $Jar)) {
        # p2 still lists the plugin, so installing again changes nothing.
        Write-Host "KNIME lists the plugin but its jar is missing or damaged: $(if ($Jar) { $Jar } else { 'no jar listed' })"
        Write-Host "Reinstalling..."
        Invoke-Director -uninstallIU $IU
        Test-Director
        Invoke-Director -installIU $IU
        Test-Director
        $Jar = Get-ListedJar $Area
        if (-not (Test-Jar $Jar)) {
            Write-Host "Full log: $Log"
            Die "KNIME won't find the plugin: $(if ($Jar) { $Jar } else { "not listed in $Area" }). If it was installed for everyone, an administrator needs to run: install-ovf.cmd -Knime `"$Knime`" -Mode admin"
        }
    }
    if ($Mode -eq "admin") { Write-Host "Installed for everyone: $Jar" } else { Write-Host "Installed: $Jar" }
    if ($Mode -eq "user" -and $Area -ne $SharedArea -and -not (Get-ListedJar $SharedArea)) {
        Write-Host "This copy is yours only. If an administrator later changes this KNIME, the plugin"
        Write-Host "stops loading: the tab turns red and KNIME offers a Migration wizard. Finish the"
        Write-Host "wizard or run this installer again. An all-users install doesn't have this problem."
    }
    $free = $null
    try { $free = [math]::Floor((New-Object IO.DriveInfo((Split-Path -Qualifier $Area))).AvailableFreeSpace / 1MB) } catch { }
    if ($null -ne $free -and $free -lt $MinFreeMb) {
        Write-Host "WARNING: only $free MB free for $Area. The plugin is installed, but KNIME"
        Write-Host "may not start until you free some space ($MinFreeMb MB or more)."
    } else {
        Write-Host "Restart KNIME. The Server Explorer tab appears next to KNIME Explorer."
        if ($ExpectMajor -eq "5") { Write-Host "It's in the classic interface. In the Modern UI: Menu > Switch to classic user interface" }
        Write-Host "If you closed it before: View > Other... > KNIME Views > Server Explorer"
    }
}
Remove-Item $Log -ErrorAction SilentlyContinue
Show-Files

if ($Mode -eq "admin" -and $ApplyPermissions) {
    if (-not $OnWindows) { Die "-ApplyPermissions is Windows only here; use install-ovf.sh" }
    Write-Host ""
    Write-Host "Applying the recommended KNIME folder permissions..."
    & icacls $Knime /reset /T /C /Q
    & icacls $Knime /inheritance:r /grant:r "*S-1-5-32-544:(OI)(CI)F" "*S-1-5-18:(OI)(CI)F" "*S-1-5-32-545:(OI)(CI)RX"
    if ($LASTEXITCODE -ne 0) {
        Die "couldn't set permissions. If you don't own the folder, first run: takeown /F `"$Knime`" /R /A"
    }
    Write-Host "Done."
} elseif ($Mode -eq "admin") {
    Write-Host ""
    Write-Host "Permissions were not changed. Run the two icacls commands above, or re-run with -ApplyPermissions."
}
exit 0
