$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$runtimeDir = Join-Path $root "data\runtime"
New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null

function Start-VoyageProcess([string]$Name, [string]$FilePath, [string[]]$Arguments, [string]$WorkingDirectory) {
    $outLog = Join-Path $runtimeDir ($Name + ".out.log")
    $errLog = Join-Path $runtimeDir ($Name + ".err.log")
    $process = Start-Process -FilePath $FilePath -ArgumentList $Arguments -WorkingDirectory $WorkingDirectory `
        -WindowStyle Hidden -RedirectStandardOutput $outLog -RedirectStandardError $errLog -PassThru
    Set-Content -LiteralPath (Join-Path $runtimeDir ($Name + ".pid")) -Value $process.Id
    Write-Output ("Started {0} PID {1}" -f $Name, $process.Id)
}

$python = Join-Path $root "agent-service\.venv\Scripts\python.exe"
if (!(Test-Path -LiteralPath $python)) {
    throw "Agent virtual environment is missing. Run: py -3.11 -m venv agent-service\.venv"
}

$envFile = Join-Path $root "agent-service\.env"
if (Test-Path -LiteralPath $envFile) {
    Get-Content -LiteralPath $envFile | ForEach-Object {
        $line = $_.Trim()
        if (-not $line -or $line.StartsWith("#") -or $line -notmatch "=") { return }
        $name, $value = $line.Split("=", 2)
        if ($name -and $value) {
            Set-Item -Path ("Env:" + $name.Trim()) -Value $value.Trim().Trim('"').Trim("'")
        }
    }
}

Start-VoyageProcess "agent" $python @("-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", "8001") (Join-Path $root "agent-service")
Start-VoyageProcess "business" "mvn.cmd" @("-q", "spring-boot:run") (Join-Path $root "business-service")
Start-VoyageProcess "web" "cmd.exe" @("/c", "corepack pnpm dev --host 127.0.0.1") (Join-Path $root "web")

Write-Output "VoyageIQ is starting: http://127.0.0.1:5173/"

