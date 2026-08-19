#Requires -Version 5.1
<#
.SYNOPSIS
    Build SealFSM, run it against the bundled examples, render every
    resulting .dot file to .png, and save a text log of the run.

.DESCRIPTION
    Each example under examples/<name>/ is run as its own Spoon model, into
    its own out/<name>/ directory. This is deliberate, not just tidy: two
    examples are free to declare a type with the same simple name (e.g. both
    DHCP fixtures produce a machine called "DhcpState"), and a single combined
    --src examples --out out run would either crash outright (two top-level
    types sharing one Java package) or silently overwrite one example's
    DhcpState.dot with the other's (same machine name, flat output dir, no
    warning). Per-example invocation sidesteps both failure modes.

    Everything printed to the console during the run (build step, each
    example's own summary table, the render pass, the final status table) is
    also collected and written to -ResultsFile once the run finishes. stderr
    chatter (SLF4J's "no providers found" warning) is deliberately excluded
    from that file — it is library noise, not a result — but still prints to
    the console exactly as before.

.PARAMETER Example
    Run only examples/<Example> instead of every directory under examples/.

.PARAMETER Format
    Passed through to the CLI's --format (dot | scxml | both). Default: both.

.PARAMETER OutDir
    Output root, mirrored per example as <OutDir>/<name>/. Default: out.

.PARAMETER ResultsFile
    Where to write the captured text log. Default: <OutDir>/results.txt.

.PARAMETER IncludeDiagnostics
    Also capture each example's [INFO]/[WARN] diagnostics (omits --quiet from
    the CLI call). Off by default, matching the console output today.

.PARAMETER SkipBuild
    Skip "mvn clean package" and reuse the existing target/sealfsm.jar.

.PARAMETER SkipTests
    When building, pass -DskipTests to Maven (faster, less safe).

.PARAMETER SkipRender
    Skip the dot -> png rendering pass.

.EXAMPLE
    scripts\build-run-render.ps1
    Full pipeline: build, run all examples, render all diagrams, write out\results.txt.

.EXAMPLE
    scripts\build-run-render.ps1 -Example traffic -SkipBuild
    Re-run just the traffic example and re-render its diagram, reusing the jar.

.EXAMPLE
    scripts\build-run-render.ps1 -IncludeDiagnostics -ResultsFile out\full-log.txt
    Full run with per-example [INFO]/[WARN] diagnostics captured too.
#>
[CmdletBinding()]
param(
    [string]$Example,
    [ValidateSet('dot', 'scxml', 'both')]
    [string]$Format = 'both',
    [string]$OutDir = 'out',
    [string]$ResultsFile,
    [switch]$IncludeDiagnostics,
    [switch]$SkipBuild,
    [switch]$SkipTests,
    [switch]$SkipRender
)

# Deliberately NOT setting $ErrorActionPreference = 'Stop': java/mvn/dot all
# write benign chatter to stderr (SLF4J's "no providers found" warning, in
# particular), and under 'Stop' a captured/redirected stderr line gets wrapped
# into a terminating NativeCommandError even though the process exit code is
# 0. Every failure path below is instead an explicit $LASTEXITCODE check.

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# Every line written via Write-Log goes to the console exactly as before AND
# accumulates here, so the .txt file at the end is a verbatim copy of what
# scrolled past on screen - never a re-derived or reformatted summary of it.
$log = New-Object System.Collections.Generic.List[string]

function Write-Log {
    param([string]$Text = '', [string]$Color)
    if ($Color) { Write-Host $Text -ForegroundColor $Color } else { Write-Host $Text }
    $log.Add($Text)
}

function Write-LogLines {
    param([string[]]$Lines)
    foreach ($l in $Lines) {
        Write-Host $l
        $log.Add($l)
    }
}

function Write-WarnLog {
    param([string]$Text)
    Write-Warning $Text
    $log.Add("WARNING: $Text")
}

function ConvertTo-QuotedArgument {
    # Standard Win32/CRT argv quoting (same rules CommandLineToArgvW parses
    # by): backslashes are only special immediately before a literal `"`.
    # ProcessStartInfo.ArgumentList (which would avoid needing this) isn't
    # present on the .NET Framework build this host has, so Arguments - a
    # single pre-quoted string - is the portable path.
    param([string]$Arg)
    if ($Arg -eq '') { return '""' }
    if ($Arg -notmatch '[\s"]') { return $Arg }
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append('"')
    $backslashes = 0
    foreach ($ch in $Arg.ToCharArray()) {
        if ($ch -eq '\') {
            $backslashes++
        } elseif ($ch -eq '"') {
            [void]$sb.Append('\' * ($backslashes * 2 + 1))
            [void]$sb.Append('"')
            $backslashes = 0
        } else {
            if ($backslashes -gt 0) { [void]$sb.Append('\' * $backslashes); $backslashes = 0 }
            [void]$sb.Append($ch)
        }
    }
    if ($backslashes -gt 0) { [void]$sb.Append('\' * ($backslashes * 2)) }
    [void]$sb.Append('"')
    return $sb.ToString()
}

# PowerShell 5.1's own native-command capture (`$x = & java ...`) decodes the
# child's stdout using [Console]::OutputEncoding, which on a plain Windows
# console is a legacy codepage, not UTF-8. The CLI's diagnostics contain
# multi-byte characters (em dash, "->", "<=") that mis-decode under that path
# and come out as the Unicode replacement character - a silent, irreversible
# loss of exactly the content this file exists to preserve. Driving the
# process directly with an explicit UTF-8 StandardOutputEncoding sidesteps
# PowerShell's decode entirely. stderr is left unredirected (inherited by the
# child), so it still goes straight to the console exactly as before and is
# never captured.
function Invoke-CapturedProcess {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [Parameter(Mandatory)][string[]]$ArgumentList
    )
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $FilePath
    $psi.Arguments = (($ArgumentList | ForEach-Object { ConvertTo-QuotedArgument $_ })) -join ' '
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $false
    $psi.UseShellExecute = $false
    $psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8

    $proc = [System.Diagnostics.Process]::Start($psi)
    $stdout = $proc.StandardOutput.ReadToEnd()
    $proc.WaitForExit()

    [PSCustomObject]@{
        ExitCode = $proc.ExitCode
        Lines    = @($stdout.TrimEnd("`r", "`n") -split "`r?`n")
    }
}

$startedAt = Get-Date
Write-Log "SealFSM run started $startedAt" -Color Cyan
Write-Log ""

if (-not $SkipBuild) {
    Write-Log "==> Building (mvn clean package)" -Color Cyan
    if ($SkipTests) {
        mvn -q clean package -DskipTests
    } else {
        mvn -q clean package
    }
    if ($LASTEXITCODE -ne 0) {
        throw "Maven build failed (exit $LASTEXITCODE). Fix the build before running examples."
    }
}

$jar = Join-Path $root 'target/sealfsm.jar'
if (-not (Test-Path $jar)) {
    throw "Jar not found at $jar. Run without -SkipBuild first."
}

$examplesRoot = Join-Path $root 'examples'
if ($Example) {
    $exampleDir = Join-Path $examplesRoot $Example
    if (-not (Test-Path $exampleDir -PathType Container)) {
        throw "No such example: $exampleDir"
    }
    $dirs = @(Get-Item $exampleDir)
} else {
    $dirs = Get-ChildItem $examplesRoot -Directory | Sort-Object Name
}

$outRoot = Join-Path $root $OutDir
New-Item -ItemType Directory -Force -Path $outRoot | Out-Null

if (-not $ResultsFile) {
    $ResultsFile = Join-Path $outRoot 'results.txt'
}

$cliArgs = @('--format', $Format)
if (-not $IncludeDiagnostics) { $cliArgs += '--quiet' }

Write-Log "==> Running $($dirs.Count) example(s)" -Color Cyan
$results = foreach ($dir in $dirs) {
    $name = $dir.Name
    $exampleOut = Join-Path $outRoot $name
    New-Item -ItemType Directory -Force -Path $exampleOut | Out-Null

    Write-Log "  -- $name" -Color DarkCyan
    # Invoke-CapturedProcess, not `& java ...` assigned to a variable:
    # (1) it decodes stdout as UTF-8 explicitly (see the function's own
    #     comment) instead of losing non-ASCII diagnostic text, and
    # (2) a plain PowerShell pipeline result here would still work, but
    #     going through .NET Process sidesteps PowerShell's native-command
    #     output handling entirely, which is what (1) requires.
    # This statement lives inside a `foreach` whose output is itself being
    # captured into $results below, so nothing here may be left as
    # unsuppressed pipeline output - it would leak into $results alongside
    # the [PSCustomObject] and corrupt the final table (this bit us with
    # Tee-Object during development). The result is instead an explicit
    # object, and the lines are explicitly replayed through Write-LogLines so
    # they reach the console and the log.
    # -Dstdout.encoding=UTF-8: on this host the JVM's default stdout charset
    # is the Windows-1252 console codepage, not UTF-8, so diagnostics text
    # (em dash, etc.) comes out as raw cp1252 bytes - which Invoke-
    # CapturedProcess's UTF-8 decode (correctly) rejects as malformed and
    # replaces with U+FFFD. Forcing the JVM's own encoder to UTF-8 is what
    # actually fixes it; the decode side alone cannot recover bytes that were
    # already wrong when Java wrote them.
    $javaArgs = @('-Dstdout.encoding=UTF-8', '-jar', $jar, '--src', $dir.FullName, '--out', $exampleOut) + $cliArgs
    $proc = Invoke-CapturedProcess -FilePath 'java' -ArgumentList $javaArgs
    $code = $proc.ExitCode
    Write-LogLines $proc.Lines

    # Main.java exits 1 when no state machine was found. For the negative-
    # control fixtures (shape, foreignfold, treebuilder, ...) that is the
    # CORRECT outcome, not a failure, so it is reported distinctly rather
    # than aborting the batch.
    $status = if ($code -eq 0) { 'ok' }
              elseif ($code -eq 1) { 'no machine found (expected for negative controls)' }
              else { "ERROR (exit $code)" }

    [PSCustomObject]@{ Example = $name; ExitCode = $code; Status = $status }
}

if (-not $SkipRender) {
    Write-Log "==> Rendering diagrams (dot -> png)" -Color Cyan
    $dotCmd = Get-Command dot -ErrorAction SilentlyContinue
    if (-not $dotCmd) {
        Write-WarnLog "Graphviz 'dot' not found on PATH - skipping PNG rendering. Install Graphviz (https://graphviz.org/download/) and ensure 'dot' is on PATH."
    } else {
        $dotFiles = Get-ChildItem $outRoot -Recurse -Filter *.dot
        $rendered = 0
        foreach ($f in $dotFiles) {
            $png = [System.IO.Path]::ChangeExtension($f.FullName, 'png')
            & dot -Tpng $f.FullName -o $png
            if ($LASTEXITCODE -eq 0) {
                $rendered++
            } else {
                Write-WarnLog "dot failed to render $($f.FullName)"
            }
        }
        Write-Log "Rendered $rendered/$($dotFiles.Count) diagram(s) to PNG." -Color Green
    }
}

Write-Log ""
Write-Log "==> Summary" -Color Cyan
$tableText = ($results | Format-Table -AutoSize | Out-String).TrimEnd("`r", "`n")
Write-LogLines ($tableText -split "`r?`n")

Set-Content -Path $ResultsFile -Value $log -Encoding utf8
Write-Host ""
Write-Host "Full results written to: $ResultsFile" -ForegroundColor Green

$errored = $results | Where-Object { $_.ExitCode -gt 1 }
if ($errored) {
    Write-Warning "$($errored.Count) example(s) errored (exit code > 1) - see table above."
    exit 1
}
