$ErrorActionPreference = "Stop"
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$jar = Join-Path (Split-Path -Parent $scriptRoot) "lib/mini-claude-code.jar"
if (-not (Test-Path -LiteralPath $jar)) {
    $jar = Join-Path (Split-Path -Parent $scriptRoot) "agent-cli/target/mini-claude-code.jar"
}
& java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED -jar $jar @args
exit $LASTEXITCODE
