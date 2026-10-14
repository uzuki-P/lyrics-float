# Lyrics Float now-playing helper for Windows.
#
# Reads every System Media Transport Controls session (the media sessions the
# Windows volume flyout shows) through the WinRT
# GlobalSystemMediaTransportControlsSessionManager and prints one JSON line per
# tick on stdout. SmtcNowPlayingMonitor.kt parses the lines.
#
# Runs under Windows PowerShell 5.1 (powershell.exe). PowerShell 7 dropped the
# WinRT type projection this script relies on.

$ErrorActionPreference = 'Stop'
# Song titles are often non-ASCII; the default console code page would
# mangle them on the way to the JVM.
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false

Add-Type -AssemblyName System.Runtime.WindowsRuntime
$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType=WindowsRuntime]
$managerType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]
$propertiesType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties]

# PowerShell 5.1 cannot await WinRT IAsyncOperation<T> directly; convert it to
# a .NET Task with the generic AsTask extension and block with a timeout.
$asTaskGeneric = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
} | Select-Object -First 1

function Await($operation, [Type]$resultType) {
    $task = $asTaskGeneric.MakeGenericMethod($resultType).Invoke($null, @($operation))
    if (-not $task.Wait(2000)) { return $null }
    return $task.Result
}

$manager = Await ($managerType::RequestAsync()) $managerType
if ($null -eq $manager) {
    [Console]::Error.WriteLine('SMTC session manager unavailable')
    exit 2
}

$parentPid = 0
[void][int]::TryParse("$env:LYRICSFLOAT_PARENT_PID", [ref]$parentPid)
$tick = 0

while ($true) {
    # Exit with the app even if it dies without closing our stdout.
    if ($parentPid -gt 0 -and ($tick % 8) -eq 0) {
        try { $null = [System.Diagnostics.Process]::GetProcessById($parentPid) } catch { exit 0 }
    }
    $tick++

    $nowMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $current = $null
    try {
        $currentSession = $manager.GetCurrentSession()
        if ($null -ne $currentSession) { $current = $currentSession.SourceAppUserModelId }
    } catch {}

    $sessions = New-Object System.Collections.ArrayList
    $seen = @{}
    foreach ($session in $manager.GetSessions()) {
        try {
            $app = [string]$session.SourceAppUserModelId
            # Two tabs of one browser share an app id; number the repeats so
            # each session keeps its own key.
            $count = 0
            if ($seen.ContainsKey($app)) { $count = $seen[$app] }
            $seen[$app] = $count + 1
            $key = $app
            if ($count -gt 0) { $key = "$app#$count" }

            $info = $session.GetPlaybackInfo()
            $timeline = $session.GetTimelineProperties()
            $properties = Await ($session.TryGetMediaPropertiesAsync()) $propertiesType

            $status = [string]$info.PlaybackStatus
            $rate = 1.0
            if ($null -ne $info.PlaybackRate) { $rate = [double]$info.PlaybackRate }

            $start = $timeline.StartTime.TotalMilliseconds
            $position = $timeline.Position.TotalMilliseconds - $start
            $length = $timeline.EndTime.TotalMilliseconds - $start
            # Players report the position only when it changes discontinuously
            # (play, seek, track change) and stamp it with LastUpdatedTime, so
            # advance it to now while playing.
            $updated = $timeline.LastUpdatedTime
            if ($status -eq 'Playing' -and $updated.Year -gt 2000) {
                $position += ($nowMs - $updated.ToUnixTimeMilliseconds()) * $rate
            }
            if ($length -gt 0 -and $position -gt $length) { $position = $length }
            if ($position -lt 0) { $position = 0 }

            $title = ''
            $artist = ''
            $album = ''
            if ($null -ne $properties) {
                $title = [string]$properties.Title
                $artist = [string]$properties.Artist
                $album = [string]$properties.AlbumTitle
            }

            [void]$sessions.Add([ordered]@{
                key = $key
                app = $app
                status = $status
                rate = $rate
                positionMs = [long]$position
                lengthMs = [long]$length
                title = $title
                artist = $artist
                album = $album
            })
        } catch {
            [Console]::Error.WriteLine("session read failed: $_")
        }
    }

    $line = ConvertTo-Json -InputObject ([ordered]@{ current = $current; sessions = $sessions.ToArray() }) -Compress -Depth 4
    [Console]::Out.WriteLine($line)
    [Console]::Out.Flush()
    Start-Sleep -Milliseconds 250
}
