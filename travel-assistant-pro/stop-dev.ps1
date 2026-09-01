$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$runtimeDir = Join-Path $root "data\runtime"
$resolvedRoot = [System.IO.Path]::GetFullPath($root)
$resolvedRuntime = [System.IO.Path]::GetFullPath($runtimeDir)
if (!$resolvedRuntime.StartsWith($resolvedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Runtime path escaped the VoyageIQ workspace."
}

function Get-DescendantProcessIds([int]$RootProcessId, [object[]]$ProcessSnapshot) {
    $children = @($ProcessSnapshot | Where-Object { $_.ParentProcessId -eq $RootProcessId })
    $ids = @()
    foreach ($child in $children) {
        $ids += Get-DescendantProcessIds -RootProcessId $child.ProcessId -ProcessSnapshot $ProcessSnapshot
        $ids += [int]$child.ProcessId
    }
    return $ids
}

$processSnapshot = @(Get-CimInstance Win32_Process)
Get-ChildItem -LiteralPath $runtimeDir -Filter "*.pid" -ErrorAction SilentlyContinue | ForEach-Object {
    $rootProcessId = [int](Get-Content -LiteralPath $_.FullName -Raw)
    $targets = @(Get-DescendantProcessIds -RootProcessId $rootProcessId -ProcessSnapshot $processSnapshot)
    $targets += $rootProcessId
    foreach ($targetId in $targets | Select-Object -Unique) {
        Stop-Process -Id $targetId -Force -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $_.FullName -Force
    Write-Output ("Stopped process tree rooted at PID {0}" -f $rootProcessId)
}
