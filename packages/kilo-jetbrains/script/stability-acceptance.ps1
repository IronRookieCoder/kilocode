param(
  [Parameter(Mandatory=$true)][string]$Writer,
  [Parameter(Mandatory=$true)][string]$Consumer,
  [Parameter(Mandatory=$true)][string]$Root
)
$ErrorActionPreference = 'Stop'
$dir = [IO.Path]::GetFullPath($Root)
if (-not (Test-Path -LiteralPath $dir -PathType Container)) {
  New-Item -ItemType Directory -Path $dir | Out-Null
}
foreach ($scenario in @('lock-contention','dead-writer','pid-reuse','claim-race','sync-failure','replay-after-ack')) {
  & $Writer '--scenario' $scenario '--root' $dir '--peer' $Consumer
  if ($LASTEXITCODE -ne 0) { throw "writer failed: $scenario" }
  & $Consumer '--scenario' $scenario '--root' $dir '--peer' $Writer
  if ($LASTEXITCODE -ne 0) { throw "consumer failed: $scenario" }
}
