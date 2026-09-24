[CmdletBinding(DefaultParameterSetName = 'Outbox')]
param(
  [Parameter(Mandatory = $true, ParameterSetName = 'Outbox')][string]$Outbox,
  [Parameter(ParameterSetName = 'Outbox')][string[]]$Secret = @(
    'tok-outbox-secret-42',
    'cookie-outbox-secret-42',
    'password-outbox-secret-42'
  ),
  [Parameter(Mandatory = $true, ParameterSetName = 'Legacy')][string]$Writer,
  [Parameter(Mandatory = $true, ParameterSetName = 'Legacy')][string]$Consumer,
  [Parameter(Mandatory = $true, ParameterSetName = 'Legacy')][string]$Root
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($PSCmdlet.ParameterSetName -eq 'Legacy') {
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
  exit 0
}

$dir = [IO.Path]::GetFullPath($Outbox)
if (-not (Test-Path -LiteralPath $dir -PathType Container)) {
  throw "outbox directory not found: $dir"
}
$entries = @(Get-ChildItem -LiteralPath $dir -Force)
$foreign = @($entries | Where-Object { -not $_.PSIsContainer -and $_.Name -notmatch '^sc-[0-9a-f]{12}\.jsonl$' })
$folders = @($entries | Where-Object { $_.PSIsContainer })
if ($foreign.Count -gt 0 -or $folders.Count -gt 0) {
  throw "outbox contains non-JSONL entries: $(@($foreign + $folders).Name -join ', ')"
}
$files = @($entries | Where-Object { -not $_.PSIsContainer })
if ($files.Count -eq 0) { throw "outbox has no scope JSONL: $dir" }

$facts = [Collections.Generic.List[object]]::new()
$raw = [Text.StringBuilder]::new()
foreach ($file in $files) {
  $text = [IO.File]::ReadAllText($file.FullName, [Text.Encoding]::UTF8)
  if ($text.Contains("`r")) { throw "$($file.Name) contains CR bytes" }
  if (-not $text.EndsWith("`n")) { throw "$($file.Name) does not end with LF" }
  [void]$raw.Append($text)
  $line = 0
  foreach ($json in $text.Split("`n", [StringSplitOptions]::RemoveEmptyEntries)) {
    $line++
    try {
      $fact = $json | ConvertFrom-Json
    } catch {
      throw "$($file.Name):$line is not valid JSON: $($_.Exception.Message)"
    }
    $fact | Add-Member -NotePropertyName acceptance_file -NotePropertyValue $file.Name
    $fact | Add-Member -NotePropertyName acceptance_line -NotePropertyValue $line
    $facts.Add($fact)
  }
}

# failure-first changes physical line order. Reconstruct only within each producer/run/channel.
$ordered = @($facts | Sort-Object producer_id, run_id, channel, @{ Expression = { [long]$_.seq } })

function Get-Payloads([object]$Parent) {
  $id = $Parent.context.incident_id
  if ([string]::IsNullOrWhiteSpace($id)) { throw "$($Parent.data.code) has no incident_id" }
  $refs = @($Parent.data.payload_refs)
  if ($refs.Count -gt 16) { throw "$id has $($refs.Count) payload refs; maximum is 16" }
  $result = @{}
  $saved = 0L
  foreach ($kind in $refs) {
    $chunks = @($ordered | Where-Object {
      $_.name -eq 'diagnostic.payload' -and $_.context.incident_id -eq $id -and $_.data.payload_kind -eq $kind
    } | Sort-Object { [int]$_.data.chunk_index })
    if ($chunks.Count -eq 0) { throw "$id/$kind has no chunks" }
    $count = [int]$chunks[0].data.chunk_count
    if ($chunks.Count -ne $count) { throw "$id/$kind has $($chunks.Count)/$count chunks" }
    for ($index = 0; $index -lt $count; $index++) {
      if ([int]$chunks[$index].data.chunk_index -ne $index) { throw "$id/$kind has a chunk index gap" }
    }
    foreach ($field in @('chunk_count','encoding','original_bytes','sha256','truncated')) {
      $values = @($chunks | ForEach-Object { $_.data.$field } | Select-Object -Unique)
      if ($values.Count -ne 1) { throw "$id/$kind has inconsistent $field metadata" }
    }
    $content = ($chunks | ForEach-Object { [string]$_.data.content }) -join ''
    $bytes = if ($chunks[0].data.encoding -eq 'base64') {
      [Convert]::FromBase64String($content)
    } else {
      [Text.Encoding]::UTF8.GetBytes($content)
    }
    $saved += $bytes.Length
    if (-not [bool]$chunks[0].data.truncated) {
      if ($bytes.Length -ne [long]$chunks[0].data.original_bytes) { throw "$id/$kind byte count mismatch" }
      $sha = [Security.Cryptography.SHA256]::Create()
      try {
        $actual = ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
      } finally {
        $sha.Dispose()
      }
      if ($actual -ne $chunks[0].data.sha256) { throw "$id/$kind sha256 mismatch" }
    }
    $result[$kind] = [Text.Encoding]::UTF8.GetString($bytes)
  }
  if ($saved -gt 1MB) { throw "$id retained more than 1 MiB across payload kinds" }
  return $result
}

function Assert-Incident(
  [string]$Code,
  [string]$Component = '',
  [string]$Route = '',
  [string]$Operation = '',
  [Nullable[int]]$Status = $null,
  [string]$Payload = ''
) {
  $matches = @($ordered | Where-Object {
    $_.name -eq 'diagnostic.reported' -and $_.data.code -eq $Code -and
      (-not $Component -or $_.data.component -eq $Component) -and
      (-not $Route -or $_.data.route -eq $Route)
  })
  if ($matches.Count -eq 0) { throw "no $Code incident for component=$Component route=$Route" }
  $incident = $matches[0]
  if ($null -ne $Status -and [int]$incident.data.http_status -ne [int]$Status) {
    throw "$Code status is $($incident.data.http_status), expected $Status"
  }
  $payloads = Get-Payloads $incident
  if ($Payload -and -not $payloads.ContainsKey($Payload)) { throw "$Code has no $Payload payload" }
  if ($Operation) {
    $id = $incident.context.operation_id
    $linked = @($ordered | Where-Object {
      $_.kind -eq 'operation' -and $_.context.operation_id -eq $id -and
        ($_.name -eq $Operation -or $_.data.operation -eq $Operation)
    })
    if ([string]::IsNullOrWhiteSpace($id) -or $linked.Count -eq 0) {
      throw "$Code has no real associated $Operation operation"
    }
  }
}

Assert-Incident -Code 'decode_failed' -Component 'session.recent' -Operation 'rpc' -Payload 'response'
$decode = @($ordered | Where-Object {
  $_.name -eq 'diagnostic.reported' -and $_.data.code -eq 'decode_failed' -and $_.data.component -eq 'session.recent'
})[0]
if ($decode.data.json_path -ne '$[0].time' -or $decode.data.expected_type -ne 'object' -or
    $decode.data.actual_type -ne 'string') {
  throw 'recent-session decode incident has incomplete structural metadata'
}
Assert-Incident -Code 'not_found' -Component 'telemetry.capture' -Route '/telemetry/capture' -Status 404 -Payload 'response'

$ends = @($ordered | Where-Object {
  $_.name -eq 'ide.operation' -and $_.data.phase -eq 'end' -and
    $_.data.error_code -eq 'ide_capability_bind_failed'
})
if ($ends.Count -eq 0) { throw 'no real failed mcp_register operation' }
$end = $ends[-1]
$operationId = $end.context.operation_id
$starts = @($ordered | Where-Object {
  $_.name -eq 'ide.operation' -and $_.context.operation_id -eq $operationId -and
    $_.data.operation -eq 'mcp_register'
})
if ($starts.Count -eq 0) { throw 'MCP failure has no associated mcp_register start' }
$mcp = @($ordered | Where-Object {
  $_.name -eq 'diagnostic.reported' -and $_.context.operation_id -eq $operationId -and
    $_.data.route -like '*/capabilities/ide'
})
if ($mcp.Count -eq 0) { throw 'mcp_register failure has no HTTP diagnostic incident' }
if ([int]$mcp[0].data.http_status -ne 502) { throw "MCP bind status is $($mcp[0].data.http_status), expected 502" }
$mcpPayloads = Get-Payloads $mcp[0]
if (-not $mcpPayloads.ContainsKey('response')) { throw 'MCP bind incident has no response payload' }

$stall = @($ordered | Where-Object { $_.name -eq 'edt.stall' -and [long]$_.data.duration_ms -ge 2000 }) |
  Where-Object {
    $id = $_.context.incident_id
    $parent = @($ordered | Where-Object { $_.name -eq 'diagnostic.reported' -and $_.context.incident_id -eq $id })
    $parent.Count -eq 1 -and (Get-Payloads $parent[0]).ContainsKey('edt_stack')
  } | Select-Object -First 1
if ($null -eq $stall) { throw 'no EDT stall >= 2000ms with captured stack' }

$unclean = @($ordered | Where-Object {
  $_.name -eq 'plugin.unclean' -and [long]$_.data.open_operation_count -gt 0 -and @($_.data.open_operations).Count -gt 0
})
if ($unclean.Count -eq 0) { throw 'no unclean restart with open operations' }

$load = @($ordered | Where-Object { $_.name -eq 'diagnostic.reported' -and $_.data.code -like 'load_failure_*' })
$expected = @(0..99 | ForEach-Object { 'load_failure_{0:D3}' -f $_ })
$actual = @($load | ForEach-Object { [string]$_.data.code } | Sort-Object -Unique)
$missing = @($expected | Where-Object { $_ -notin $actual })
$extra = @($actual | Where-Object { $_ -notin $expected })
if ($missing.Count -gt 0 -or $extra.Count -gt 0) {
  throw "load failure set mismatch; missing=$($missing -join ',') extra=$($extra -join ',')"
}
foreach ($code in $expected) {
  $parents = @($load | Where-Object { $_.data.code -eq $code })
  if ($parents.Count -ne 1) { throw "$code must have exactly one parent, found $($parents.Count)" }
  if ('response' -notin @($parents[0].data.payload_refs)) { throw "$code does not explicitly reference response" }
  $payloads = Get-Payloads $parents[0]
  $index = [int]$code.Substring($code.Length - 3)
  if ($payloads['response'] -ne "response-$index") { throw "$code response did not reassemble exactly" }
}

$good = @($ordered | Where-Object {
  $_.name -eq 'telemetry.health' -and $_.data.PSObject.Properties.Name -contains 'quality' -and
    $_.data.quality -eq 'good' -and [long]$_.data.drop_evicted -gt 0
})
if ($good.Count -eq 0) { throw 'no good-quality health fact reports sample eviction' }
$degraded = @($ordered | Where-Object {
  $_.name -eq 'telemetry.health' -and $_.data.PSObject.Properties.Name -contains 'quality' -and
    $_.data.quality -eq 'degraded' -and [long]$_.data.drop_failure -gt 0
})
if ($degraded.Count -eq 0) { throw 'no degraded health fact reports failure loss' }

foreach ($value in $Secret) {
  if ($value -and $raw.ToString().Contains($value)) { throw 'credential leaked into copied outbox' }
}

Write-Host "outbox-only acceptance passed: $($facts.Count) facts, 100 complete load incidents"
