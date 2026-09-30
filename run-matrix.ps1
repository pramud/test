# Builds the harness if needed and runs the experiment matrix on Windows; every argument is passed to the runner.
#   .\run-matrix.ps1 --nginx=C:\nginx-1.28.0\nginx.exe
#   .\run-matrix.ps1 "--exp=E4,E6" --runs=5 --nginx=C:\nginx-1.28.0\nginx.exe
#   .\run-matrix.ps1 --exp=custom --proxy=on --heartbeat=forced --ping-phase=P2 --nginx=C:\nginx-1.28.0\nginx.exe
# Packet capture is off by default on Windows. A tcpdump-compatible tool (e.g. WinDump with the Npcap loopback
# adapter) can be used with --tcpdump=<path> --capture-if=<interface>.
# Set $env:REBUILD=1 to force a Maven build.
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$RunnerArgs)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$jars = 'server\target\server.jar', 'client\target\client.jar', 'runner\target\runner.jar'
if ($env:REBUILD -or ($jars | Where-Object { -not (Test-Path $_) })) {
    & mvn -q -B package
    if ($LASTEXITCODE -ne 0) { throw 'Maven build failed' }
}

# PowerShell splits an unquoted "--exp=E1,E2" into "--exp=E1" and "E2"; glue such pieces back together.
$fixed = @()
foreach ($a in $RunnerArgs) {
    if ($fixed.Count -gt 0 -and -not $a.StartsWith('--')) { $fixed[-1] = $fixed[-1] + ',' + $a }
    else { $fixed += $a }
}

& java -jar runner\target\runner.jar @fixed
exit $LASTEXITCODE
