$ErrorActionPreference = 'Continue'
Write-Host ""
Write-Host "========== PhoneMic 1.3 - USB Diagnose ==========" -ForegroundColor Cyan
Write-Host ""
Write-Host "IPv4 Netzwerkadapter:" -ForegroundColor Yellow
$all = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -ne '127.0.0.1' -and $_.IPAddress -notlike '169.254.*' } |
    Sort-Object InterfaceAlias,IPAddress
foreach ($entry in $all) {
    $adapter = Get-NetAdapter -InterfaceIndex $entry.InterfaceIndex -ErrorAction SilentlyContinue
    $description = if ($null -ne $adapter) { $adapter.InterfaceDescription } else { "Unbekannt" }
    $usb = ($description -match '(RNDIS|Remote NDIS|USB|NCM|Tether|Android)' -or
            $entry.InterfaceAlias -match '(USB|Ethernet \d+)')
    $prefix = if ($usb) { "MOEGLICHER USB-ADAPTER" } else { "Netzwerk" }
    Write-Host ("[{0}] {1} - {2}/{3} - {4}" -f
        $prefix, $entry.InterfaceAlias, $entry.IPAddress,
        $entry.PrefixLength, $description)
}
Write-Host ""
Write-Host "OBS-Empfaenger auf UDP 49888:" -ForegroundColor Yellow
$udp = Get-NetUDPEndpoint -LocalPort 49888 -ErrorAction SilentlyContinue
if ($udp) {
    foreach ($row in $udp) {
        Write-Host ("Aktiv: {0}:{1} (PID {2})" -f
            $row.LocalAddress, $row.LocalPort, $row.OwningProcess) -ForegroundColor Green
    }
} else {
    Write-Host "KEIN EMPFAENGER. OBS starten und PhoneMic-Quelle aktivieren!" -ForegroundColor Red
}
Write-Host ""
Write-Host "Firewall-Regel:" -ForegroundColor Yellow
$rule = Get-NetFirewallRule -Name 'PhoneMic-OBS-UDP-49888-LocalSubnet' -ErrorAction SilentlyContinue
if ($rule) {
    Write-Host ("Name: {0}, aktiv: {1}, Aktion: {2}" -f
        $rule.Name,$rule.Enabled,$rule.Action) -ForegroundColor Green
    $rule | Get-NetFirewallAddressFilter | Format-Table RemoteAddress
} else {
    Write-Host "PhoneMic-Firewallregel fehlt. Installer mit Adminrechten starten." -ForegroundColor Yellow
}
Write-Host ""
Write-Host "Wenn Android die IP nicht automatisch findet, die IPv4-Adresse des" -ForegroundColor Cyan
Write-Host "USB-Adapters (nicht die normale WLAN/LAN-IP) in PhoneMic eintragen."
Write-Host ""
Read-Host "Enter zum Schliessen" | Out-Null
