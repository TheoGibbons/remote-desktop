# Builds, lints and tests the Android app in a workflow. The release and the
# check on main both run this, so what main checks is what a release builds.
#
# Gradle's output is also kept in a file, so that name-android-failures.ps1 can
# say afterwards what went wrong.
#
# A build that failed because libraries could not be downloaded is tried again
# after a wait. v1.0.54 was lost to a few minutes in which Maven Central
# answered "not found" for ordinary libraries.
param(
    # Seconds to wait before each further try.
    [int[]]$WaitSeconds = @(60, 180, 420)
)

$tasks = ':app:assembleRelease :app:bundleRelease :app:lintRelease :app:testDebugUnitTest'
$log = Join-Path $env:RUNNER_TEMP 'gradle.log'

# What Gradle and its wrapper print when a download fails: of a library's
# description ("Could not find group:name:1.0.") or of its jar ("Could not find
# name-1.0.jar (group:name:1.0)."). A library or plugin that really does not
# exist reads the same, so that mistake costs the waits before it fails. A
# mistake in the build's own code does not.
$downloadFailed = @(
    '^\s*> Could not (find|resolve) (\S+ \()?\S+:\S+'
    "^\s*> Could not (GET|HEAD) '"
    '^\s*> Could not download '
    'was not found in any of the following sources'
    '^Exception in thread "main" .*(Downloading from|java\.net\.|javax\.net\.ssl\.)'
) -join '|'

for ($try = 0; ; $try++) {
    # Gradle remembers for a day that a library was missing, so a further try
    # has to make it ask again.
    $again = if ($try -gt 0) { ' --refresh-dependencies' } else { '' }
    # cmd joins Gradle's two streams, so PowerShell is handed plain lines.
    cmd /c ".\android\gradlew.bat -p android --no-daemon$again $tasks 2>&1" | Tee-Object -FilePath $log
    if ($LASTEXITCODE -eq 0) { return }

    $lines = @(Get-Content -LiteralPath $log)
    $missed = @($lines | Where-Object { $_ -cmatch $downloadFailed })
    # The same words stand above a library that was downloaded and cannot be
    # used. Waiting does not help that.
    if (@($lines | Where-Object { $_ -cmatch 'No matching variant of|Cannot find a version of' }).Count -gt 0) {
        $missed = @()
    }
    # Robolectric downloads the Android versions its tests run on by itself,
    # and Gradle sees that fail only as failed tests.
    if ($missed.Count -eq 0) {
        $missed = @(Get-ChildItem android/app/build/test-results -Recurse -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue |
            Select-String -Pattern 'Failed to fetch maven artifact [\w.:-]+' -List |
            Select-Object -First 1 | ForEach-Object { $_.Matches[0].Value })
    }
    if ($missed.Count -eq 0 -or $try -ge $WaitSeconds.Count) { throw 'Android build failed.' }
    $wait = $WaitSeconds[$try]
    $first = $missed[0].Trim() -replace '%', '%25'
    "::warning title=A download failed in the Android build::Try $($try + 1) failed with '$first'. Trying again in $wait seconds."
    Start-Sleep -Seconds $wait
}
