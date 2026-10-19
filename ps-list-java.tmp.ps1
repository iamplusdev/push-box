Get-CimInstance Win32_Process -Filter "Name='java.exe'" | ForEach-Object {
  $cl = $_.CommandLine
  if ($null -ne $cl -and $cl.Length -gt 220) { $cl = $cl.Substring(0, 220) }
  "{0} :: {1}" -f $_.ProcessId, $cl
}
