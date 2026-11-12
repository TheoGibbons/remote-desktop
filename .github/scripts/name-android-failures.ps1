# Says what failed in an Android build, as annotations on the run. Reading a
# job's log needs a GitHub login; reading its annotations does not.

# The tests that failed.
Get-ChildItem android/app/build/test-results -Recurse -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue |
    ForEach-Object {
        # A results file that cannot be read must not cost Gradle's account below.
        try {
            [xml]$suite = Get-Content -Raw -LiteralPath $_.FullName
            foreach ($case in $suite.testsuite.testcase) {
                $problem = if ($case.failure) { $case.failure } else { $case.error }
                if (-not $problem) { continue }
                $file = ($case.classname -split '\.')[-1] + '.kt'
                # Every line of the test file in the trace: a helper's and its caller's.
                $where = ([regex]::Matches($problem.InnerText, "\($([regex]::Escape($file)):\d+\)") |
                    ForEach-Object Value) -join ' '
                $text = "$($problem.message) $where" -replace '\s+', ' '
                if ($text.Length -gt 400) { $text = $text.Substring(0, 400) }
                $text = $text -replace '%', '%25'
                # A test's name is a sentence, and a comma in it would cut the title short.
                $title = "$($case.classname).$($case.name)" -replace '%', '%25' -replace ':', '%3A' -replace ',', '%2C'
                "::error title=$title::$text"
            }
        } catch {
            "::warning title=Test results not read::$($_.Exception.Message -replace '%', '%25' -replace '\s+', ' ')"
        }
    }

# Gradle's own account of what went wrong. A build that fails before any test
# has run leaves nothing for the part above to name.
$log = Join-Path $env:RUNNER_TEMP 'gradle.log'
if (-not (Test-Path -LiteralPath $log)) { return }
$lines = @(Get-Content -LiteralPath $log)
if ($lines.Count -eq 0) { '::error title=Gradle::Gradle printed nothing'; return }
# A later step failed, not the build.
if (@($lines | Where-Object { $_ -match '^BUILD SUCCESSFUL' }).Count -gt 0) { return }
$say = {
    param($title, $text)
    $text = $text -join "`n"
    if ($text.Length -gt 3000) { $text = $text.Substring(0, 3000) }
    $text = $text -replace '%', '%25' -replace "`r", '' -replace "`n", '%0A'
    "::error title=$title::$text"
}
$failed = @($lines | Where-Object { $_ -match '^> Task .* FAILED$' })
if ($failed.Count -gt 0) { & $say 'Gradle tasks that failed' $failed }
$at = 0..($lines.Count - 1) | Where-Object { $lines[$_] -match '^\* What went wrong:' } | Select-Object -First 1
if ($null -ne $at) {
    $end = [Math]::Min($at + 25, $lines.Count - 1)
    $try = $at..$end | Where-Object { $lines[$_] -match '^\* Try:' } | Select-Object -First 1
    if ($null -ne $try) { $end = $try - 1 }
    & $say 'Gradle: what went wrong' $lines[$at..$end]
} else {
    & $say 'Gradle: its last lines' @($lines | Select-Object -Last 25)
}
$errors = @($lines | Where-Object { $_ -match '^e: |: error: |: Error: ' } | Select-Object -First 15)
if ($errors.Count -gt 0) { & $say 'Compiler and lint errors' $errors }
