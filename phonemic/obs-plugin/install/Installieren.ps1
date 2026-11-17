$ErrorActionPreference = 'Stop'
$plugin = Join-Path $PSScriptRoot 'phone-mic-obs'
$dll = Join-Path $plugin 'phone-mic-obs.dll'
if (-not (Test-Path -LiteralPath $dll)) {
    Write-Error "Die DLL fehlt: $dll. ZIP zuerst vollstaendig entpacken."
    Read-Host "Enter zum Schliessen"
    exit 1
}
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]$identity
$isAdmin = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    $argsList = '-NoLogo -NoProfile -ExecutionPolicy Bypass -File "' + $PSCommandPath + '"'
    Start-Process -FilePath "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -ArgumentList $argsList -Verb RunAs
    exit
}
if (Get-Process -Name 'obs64' -ErrorAction SilentlyContinue) {
    Write-Host "Bitte OBS Studio erst vollstaendig beenden und Installer erneut starten." -ForegroundColor Yellow
    Read-Host "Enter zum Schliessen"
    exit 2
}
$obsExe = Join-Path $env:ProgramFiles 'obs-studio\bin\64bit\obs64.exe'
$major = 33
if (Test-Path -LiteralPath $obsExe) {
    $version = (Get-Item -LiteralPath $obsExe).VersionInfo.ProductVersion
    if ($version -match '^(\d+)') {
        $major = [int]$matches[1]
    }
}
$target = Join-Path $env:ProgramData 'obs-studio\plugins\phone-mic-obs'
New-Item -Path $target -ItemType Directory -Force | Out-Null
if ($major -ge 33) {
    $targetDll = Join-Path $target 'phone-mic-obs.dll'
    $legacyDll = Join-Path $target 'bin\64bit\phone-mic-obs.dll'
    if (Test-Path -LiteralPath $legacyDll) {
        Remove-Item -LiteralPath $legacyDll -Force
    }
} else {
    $bin = Join-Path $target 'bin\64bit'
    New-Item -Path $bin -ItemType Directory -Force | Out-Null
    $targetDll = Join-Path $bin 'phone-mic-obs.dll'
    $newDll = Join-Path $target 'phone-mic-obs.dll'
    if (Test-Path -LiteralPath $newDll) {
        Remove-Item -LiteralPath $newDll -Force
    }
}
Copy-Item -LiteralPath $dll -Destination $targetDll -Force
$localeTarget = Join-Path $target 'data\locale'
New-Item -Path $localeTarget -ItemType Directory -Force | Out-Null
Copy-Item -Path (Join-Path $plugin 'data\locale\*.ini') -Destination $localeTarget -Force

# Bei USB-Tethering stuft Windows den Adapter oft als "Oeffentlich" ein.
# Zugelassen ist ausschliesslich das lokale Subnetz auf UDP 49888.
try {
    $ruleName = 'PhoneMic-OBS-UDP-49888-LocalSubnet'
    $rule = Get-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue
    if (-not $rule) {
        New-NetFirewallRule -Name $ruleName -DisplayName 'PhoneMic OBS (USB/WLAN lokal)' `
            -Description 'Nur lokale Netzwerkgeraete duerfen PhoneMic an UDP 49888 erreichen.' `
            -Direction Inbound -Action Allow -Protocol UDP -LocalPort 49888 `
            -RemoteAddress LocalSubnet -Profile Any -Enabled True | Out-Null
    }
    else {
        Set-NetFirewallRule -Name $ruleName -Enabled True | Out-Null
    }
    Write-Host "Firewall: UDP 49888 nur aus dem lokalen Subnetz freigegeben."
}
catch {
    Write-Warning "Firewallregel konnte nicht gesetzt werden. Bitte UDP 49888 fuer das lokale Subnetz manuell freigeben."
}

Write-Host ""
Write-Host "PhoneMic erfolgreich installiert." -ForegroundColor Green
Write-Host "DLL: $targetDll"
Write-Host "OBS neu starten und unter Quellen -> + -> PhoneMic die Quelle hinzufuegen."
Write-Host "Handy und PC: UDP 49888, Verbindungscode 24681357."
Write-Host "USB: Kabel anschliessen, USB-Tethering am Handy aktivieren, in PhoneMic USB waehlen."
Write-Host ""
Read-Host "Enter zum Schliessen"
