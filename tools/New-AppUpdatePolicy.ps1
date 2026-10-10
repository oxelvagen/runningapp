param(
    [Parameter(Mandatory)][string]$ApkPath,
    [Parameter(Mandatory)][ValidateRange(1,2147483647)][int]$VersionCode,
    [Parameter(Mandatory)][ValidateRange(1,2147483647)][int]$MinimumVersionCode,
    [Parameter(Mandatory)][string]$VersionName,
    [Parameter(Mandatory)][string]$ReleaseTag,
    [string]$ReleaseNotes = '',
    [Parameter(Mandatory)][string]$OutputPath
)
$ErrorActionPreference = 'Stop'
if ($MinimumVersionCode -gt $VersionCode) { throw 'MinimumVersionCode cannot exceed VersionCode.' }
if ([string]::IsNullOrWhiteSpace($VersionName) -or $VersionName.Length -gt 100) { throw 'Invalid version name.' }
if ([string]::IsNullOrWhiteSpace($ReleaseTag) -or $ReleaseTag.Length -gt 200) { throw 'Invalid release tag.' }
if ($ReleaseNotes.Length -gt 20000) { throw 'Release notes too long.' }
$file = Get-Item -LiteralPath $ApkPath
if ($file.PSIsContainer -or $file.Name -cnotmatch '^[A-Za-z0-9][A-Za-z0-9._-]*[.]apk$' -or $file.Name.Length -gt 200) { throw 'Invalid APK filename.' }
$hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
function ConvertTo-SqlLiteral([string]$Value) { "'" + $Value.Replace("'", "''") + "'" }
$nameSql = ConvertTo-SqlLiteral $VersionName
$tagSql = ConvertTo-SqlLiteral $ReleaseTag
$assetSql = ConvertTo-SqlLiteral $file.Name
$notesSql = ConvertTo-SqlLiteral $ReleaseNotes
$sql = @"
-- Generated locally. Does NOT publish or execute anything.
-- Upload the exact signed APK to its GitHub release first.
-- Confirm APK package, version and compatible certificate before running.
insert into public.app_update_config
  (id, latest_version_code, minimum_version_code, latest_version_name,
   github_release_tag, apk_asset_name, apk_sha256, release_notes)
values (true, $VersionCode, $MinimumVersionCode, $nameSql,
  $tagSql, $assetSql, '$hash', $notesSql)
on conflict (id) do update set
  latest_version_code = excluded.latest_version_code,
  minimum_version_code = excluded.minimum_version_code,
  latest_version_name = excluded.latest_version_name,
  github_release_tag = excluded.github_release_tag,
  apk_asset_name = excluded.apk_asset_name,
  apk_sha256 = excluded.apk_sha256,
  release_notes = excluded.release_notes;
"@
[IO.File]::WriteAllText($ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputPath),$sql)
Write-Output "Prepared SQL: $OutputPath"
Write-Output "SHA-256: $hash"
Write-Output 'Nothing was uploaded or applied to Supabase.'
