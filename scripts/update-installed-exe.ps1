# Replace the installed Lyrics Float (Windows) with the freshly built app
# image from `gradlew createDistributable`, without running the installer.
#
# Only the three items jpackage installs are touched: lyrics-float.exe, app\
# and runtime\ inside the folder the MSI registered as InstallLocation.
# Settings (%APPDATA%\LyricsFloat) and caches (%LOCALAPPDATA%\LyricsFloat)
# live elsewhere and are never touched. Runs on Windows PowerShell 5.1, which
# is what `just` uses on Windows.
#
#   scripts\update-installed-exe.ps1 [-DryRun]

param([switch]$DryRun)

$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot
$source = Join-Path $repo 'build\compose\binaries\main\app\lyrics-float'
$items = @('lyrics-float.exe', 'app', 'runtime')

function Test-AppLayout([string]$dir) {
    (Test-Path -LiteralPath (Join-Path $dir 'lyrics-float.exe') -PathType Leaf) -and
    (Test-Path -LiteralPath (Join-Path $dir 'app') -PathType Container) -and
    (Test-Path -LiteralPath (Join-Path $dir 'runtime') -PathType Container)
}

if (-not (Test-AppLayout $source)) {
    throw "No built app image at $source. Run: ./gradlew createDistributable"
}

# The MSI registers itself under the per-user (or machine) Uninstall key.
$entries = @(
    Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*',
                     'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*' -ErrorAction SilentlyContinue |
        Where-Object { $_.DisplayName -eq 'lyrics-float' -and $_.InstallLocation }
)
if ($entries.Count -eq 0) { throw 'Lyrics Float is not installed. Run the installer from `just build-exe` once first.' }
if ($entries.Count -gt 1) { throw "Found $($entries.Count) Lyrics Float installs; refusing to guess which one to replace." }

$target = [IO.Path]::GetFullPath($entries[0].InstallLocation.TrimEnd('\'))
# The folder is user-chosen (dirChooser), so only trust it if it is not a
# drive root and already holds exactly the layout jpackage installs.
if ([IO.Path]::GetPathRoot($target).TrimEnd('\') -eq $target) { throw "Install location is a drive root: $target" }
if (-not (Test-AppLayout $target)) { throw "Install location does not look like Lyrics Float: $target" }

Write-Host "Installed version : $($entries[0].DisplayVersion) (registry; not changed by this update)"
Write-Host "Install location  : $target"
Write-Host "Source            : $source"
Write-Host "Replacing         : $($items -join ', ')"
if ($DryRun) { Write-Host 'Dry run: nothing changed.'; return }

# Stop only Lyrics Float processes running from the install folder.
$prefix = $target + '\'
$running = @(Get-Process -Name 'lyrics-float' -ErrorAction SilentlyContinue |
    Where-Object { $_.Path -and $_.Path.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase) })
if ($running.Count -gt 0) {
    Write-Host "Stopping the running app ($($running.Count) process(es))..."
    $running | Stop-Process -Force
    $running | Wait-Process -Timeout 15 -ErrorAction SilentlyContinue
}

# Move the old items aside first so a failed copy can be rolled back.
$stamp = Get-Date -Format 'yyyyMMddHHmmss'
$backups = @{}
try {
    foreach ($item in $items) {
        $path = Join-Path $target $item
        $backup = "$path.old-$stamp"
        Move-Item -LiteralPath $path -Destination $backup
        $backups[$item] = $backup
    }
    foreach ($item in $items) {
        Copy-Item -LiteralPath (Join-Path $source $item) -Destination (Join-Path $target $item) -Recurse
    }
} catch {
    Write-Host "Update failed, restoring the previous files: $_"
    foreach ($item in $backups.Keys) {
        $path = Join-Path $target $item
        if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Recurse -Force }
        Move-Item -LiteralPath $backups[$item] -Destination $path
    }
    throw
}
foreach ($backup in $backups.Values) { Remove-Item -LiteralPath $backup -Recurse -Force }

Write-Host 'Updated.'
if ($running.Count -gt 0) {
    Start-Process -FilePath (Join-Path $target 'lyrics-float.exe')
    Write-Host 'Restarted the app.'
}
