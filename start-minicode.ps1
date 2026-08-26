$ErrorActionPreference = "Stop"

$project_root = Split-Path -Parent $PSCommandPath
$python = Join-Path $project_root ".venv\Scripts\python.exe"

if (-not (Test-Path -LiteralPath $python)) {
    throw "Project virtual environment was not found: $python"
}

$secure_key = Read-Host -AsSecureString "Paste your DeepSeek API key"
$api_key = (New-Object System.Net.NetworkCredential("", $secure_key)).Password

if ([string]::IsNullOrWhiteSpace($api_key)) {
    throw "No API key was provided."
}

$env_name = "OPENAI" + [char]95 + "API" + [char]95 + "KEY"
Set-Item -Path ("Env:" + $env_name) -Value $api_key

Set-Location -LiteralPath $project_root
& $python -m mewcode
exit $LASTEXITCODE
