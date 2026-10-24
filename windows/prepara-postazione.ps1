# prepara-postazione.ps1 - AutoFillSuite
#
# Prepares a workstation in seconds, without administrator rights:
#   1. the portal address: from the clipboard (copied from Chrome), or typed
#   2. Chrome's own icons (desktop, taskbar, start menu) open the portal;
#      the original icons are kept in a backup folder, ANNULLA puts them back
#   3. a "Portale Lifecycle" icon on the desktop
#   4. AutoFillSuite starts with Windows (and has an icon on the desktop)
#   5. AutoFillSuite's settings: the portal address, the browser and the print
#      program, found by themselves, so its first start of the day opens the
#      portal and starts the printer program
#   6. optionally starts AutoFillSuite at once, to see it work
#
#   PREPARA-POSTAZIONE.bat            what an operator double-clicks
#   ANNULLA-POSTAZIONE.bat            puts Chrome's icons back, removes the new ones
#   -Url <address> -Quiet             no questions, no launch (used by the CI test)
param(
    [string]$Url = '',
    [switch]$Undo,
    [switch]$Quiet
)

$ErrorActionPreference = 'Continue'
$DefaultUrl = 'https://lifecycle-alpitronic.via.catonetworks.com/supplier/tca'
$ProxyName  = 'Alpitronic.ZebraPrinter.Proxy.App.exe'

$Here      = Split-Path -Parent $MyInvocation.MyCommand.Path
$Backup    = Join-Path $env:LOCALAPPDATA 'AutoFillSuite-icone-originali'
$Desktop   = [Environment]::GetFolderPath('Desktop')
$Startup   = [Environment]::GetFolderPath('Startup')
$StartMenu = [Environment]::GetFolderPath('Programs')
$Taskbar   = Join-Path $env:APPDATA 'Microsoft\Internet Explorer\Quick Launch\User Pinned\TaskBar'
$Props     = Join-Path $env:USERPROFILE '.autofill_suite.properties'
$PortalLnk = Join-Path $Desktop 'Portale Lifecycle.lnk'
$AfsDesk   = Join-Path $Desktop 'AutoFillSuite.lnk'
$AfsStart  = Join-Path $Startup 'AutoFillSuite.lnk'
$Latin1    = [Text.Encoding]::GetEncoding(28591)   # what Java reads a .properties file as
$Shell     = New-Object -ComObject WScript.Shell
$script:ok = 0
$script:ko = 0

function Say($m, $c = 'Gray') { Write-Host $m -ForegroundColor $c }
function Ok($m) { $script:ok++; Say ('  [OK] ' + $m) Green }
function Ko($m) { $script:ko++; Say ('  [KO] ' + $m) Red }
function Ask($m) { if ($Quiet) { return '' } return (Read-Host $m) }

# the Chrome icons an operator clicks
function ChromeLinks {
    $out = @()
    foreach ($dir in @($Desktop, $Taskbar, $StartMenu)) {
        if (-not $dir -or -not (Test-Path $dir)) { continue }
        $depth = 0
        if ($dir -eq $StartMenu) { $depth = 2 }
        foreach ($f in Get-ChildItem -Path $dir -Filter *.lnk -Recurse -Depth $depth -ErrorAction SilentlyContinue) {
            # our own Portale icon points at chrome.exe too: it is not one of Chrome's
            if ($f.FullName -eq $PortalLnk) { continue }
            try {
                $l = $Shell.CreateShortcut($f.FullName)
                if ($l.TargetPath -like '*\chrome.exe') { $out += $f.FullName }
            } catch { }
        }
    }
    return $out
}

# Chrome's .exe: from its icons, from where Windows registered it, from where it installs
function FindChrome($links) {
    foreach ($p in $links) {
        try { $t = $Shell.CreateShortcut($p).TargetPath; if ($t -and (Test-Path $t)) { return $t } } catch { }
    }
    foreach ($k in @('HKCU:\Software\Microsoft\Windows\CurrentVersion\App Paths\chrome.exe',
                     'HKLM:\Software\Microsoft\Windows\CurrentVersion\App Paths\chrome.exe',
                     'HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\App Paths\chrome.exe')) {
        try { $t = (Get-ItemProperty -Path $k -ErrorAction Stop).'(default)'; if ($t -and (Test-Path $t)) { return $t } } catch { }
    }
    foreach ($c in @((Join-Path $env:ProgramFiles 'Google\Chrome\Application\chrome.exe'),
                     (Join-Path ${env:ProgramFiles(x86)} 'Google\Chrome\Application\chrome.exe'),
                     (Join-Path $env:LOCALAPPDATA 'Google\Chrome\Application\chrome.exe'))) {
        if ($c -and (Test-Path $c)) { return $c }
    }
    return $null
}

# a file by name: first in the likely folders, then anywhere under the user's folder
function FindFile($name, $first) {
    foreach ($dir in $first) {
        if (-not $dir -or -not (Test-Path $dir)) { continue }
        $f = Get-ChildItem -Path $dir -Filter $name -Recurse -Depth 3 -ErrorAction SilentlyContinue |
             Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($f) { return $f }
    }
    return (Get-ChildItem -Path $env:USERPROFILE -Filter $name -Recurse -Depth 5 -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -notlike '*\AppData\*' } |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1)
}

function Link($path, $target, $arguments, $dir, $icon, $what) {
    try {
        $l = $Shell.CreateShortcut($path)
        $l.TargetPath = $target
        if ($arguments) { $l.Arguments = $arguments }
        if ($dir) { $l.WorkingDirectory = $dir }
        if ($icon) { $l.IconLocation = $icon + ',0' }
        $l.Description = $what
        $l.Save()
        Ok ($what + ': ' + $path)
    } catch { Ko ($what + ' non creato: ' + $_.Exception.Message) }
}

# a .properties value: a backslash is an escape there, so a Windows path is doubled
function Esc($v) { return ($v -replace '\\', '\\') }

function WriteSettings($set, $drop) {
    $lines = New-Object System.Collections.ArrayList
    if (Test-Path $Props) {
        Copy-Item $Props ($Props + '.prima-della-preparazione') -Force
        foreach ($x in [IO.File]::ReadAllLines($Props, $Latin1)) { [void]$lines.Add($x) }
    }
    foreach ($k in $drop) {
        for ($j = $lines.Count - 1; $j -ge 0; $j--) { if ($lines[$j] -like ($k + '=*')) { $lines.RemoveAt($j) } }
    }
    foreach ($k in $set.Keys) {
        $line = $k + '=' + (Esc $set[$k])
        $i = -1
        for ($j = 0; $j -lt $lines.Count; $j++) { if ($lines[$j] -like ($k + '=*')) { $i = $j } }
        if ($i -ge 0) { $lines[$i] = $line } else { [void]$lines.Add($line) }
    }
    [IO.File]::WriteAllLines($Props, [string[]]$lines.ToArray(), $Latin1)
}

function WaitAutoFillClosed {
    if (-not (Get-Process -Name 'AutoFillSuite' -ErrorAction SilentlyContinue)) { return $true }
    if ($Quiet) { return $false }
    Say '  AutoFillSuite e'' aperto: chiudilo (salva le sue impostazioni quando si chiude), poi premi INVIO' Yellow
    while (Get-Process -Name 'AutoFillSuite' -ErrorAction SilentlyContinue) { [void](Read-Host) }
    return $true
}

# ================================================================ undo
if ($Undo) {
    Say '=========== Annulla la preparazione ===========' Cyan
    if (Test-Path $Backup) {
        foreach ($f in Get-ChildItem -Path $Backup -Filter *.lnk) {
            $dest = Get-Content -Path ($f.FullName + '.dove') -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($dest) {
                try { Copy-Item -Path $f.FullName -Destination $dest -Force; Ok ('icona di Chrome rimessa: ' + $dest) }
                catch { Ko ('non rimessa: ' + $dest) }
            }
        }
        Remove-Item -Path $Backup -Recurse -Force -ErrorAction SilentlyContinue
    } else { Say '  nessuna icona di Chrome da rimettere' Yellow }
    foreach ($p in @($PortalLnk, $AfsStart)) {
        if (Test-Path $p) { Remove-Item $p -Force; Ok ('tolta: ' + $p) }
    }
    if (WaitAutoFillClosed) {
        if (Test-Path $Props) { WriteSettings @{ 'station.autoStart' = 'false' } @(); Ok 'AutoFillSuite non apre piu'' portale e stampa all''avvio' }
    } else { Ko 'AutoFillSuite e'' aperto: impostazioni non cambiate' }
    Say ''
    Say 'Fatto.' Cyan
    exit 0
}

# ================================================================ 1. address
if (-not $Quiet) { Clear-Host }
Say '=========== Prepara la postazione ===========' Cyan
Say ''
if (-not $Url) {
    $clip = ''
    try { $clip = ((Get-Clipboard -Format Text -ErrorAction Stop) | Out-String).Trim() } catch { }
    if ($clip -match '^https?://\S+$') {
        Say 'Negli appunti c''e'' questo indirizzo:' Yellow
        Say ('  ' + $clip) White
        $a = Ask 'INVIO = usa questo   (oppure incollane un altro)'
        if ($a) { $Url = $a } else { $Url = $clip }
    } else {
        Say 'Indirizzo del portale. INVIO = quello di sempre:' Yellow
        Say ('  ' + $DefaultUrl) White
        $a = Ask 'Indirizzo'
        if ($a) { $Url = $a } else { $Url = $DefaultUrl }
    }
}
$Url = $Url.Trim().Trim('"')
if ($Url -notmatch '^[a-zA-Z][a-zA-Z0-9+.-]*://') { $Url = 'https://' + $Url }
Say ('Portale: ' + $Url) Green
Say ''

# ================================================================ 2. chrome
Say '[1/5] Le icone di Chrome aprono il portale' Cyan
$links = @(ChromeLinks)
$chrome = FindChrome $links
if ($links.Count -eq 0) { Say '  nessuna icona di Chrome trovata: salto (l''icona Portale la creo comunque)' Yellow }
New-Item -ItemType Directory -Path $Backup -Force | Out-Null
$n = 0
foreach ($p in $links) {
    $n++
    try {
        $l = $Shell.CreateShortcut($p)
        $args0 = $l.Arguments
        # a previous preparation, maybe with another address: take it out first
        $args0 = ($args0 -replace '\s*"https?://[^"]*"', '').Trim()
        $new = ($args0 + ' "' + $Url + '"').Trim()
        if ($l.Arguments -eq $new) { Ok ('gia'' pronta: ' + $p); continue }
        $keep = Join-Path $Backup ('{0:D2}_{1}' -f $n, [IO.Path]::GetFileName($p))
        $already = Get-ChildItem -Path $Backup -Filter '*.dove' -ErrorAction SilentlyContinue |
                   Where-Object { (Get-Content $_.FullName | Select-Object -First 1) -eq $p }
        if (-not $already) {
            Copy-Item -Path $p -Destination $keep -Force
            Set-Content -Path ($keep + '.dove') -Value $p
        }
        $l.Arguments = $new
        $l.Save()
        Ok ('icona aggiornata: ' + $p)
    } catch { Ko ('icona non modificabile: ' + $p + ' (' + $_.Exception.Message + ')') }
}

# ================================================================ 3. portal icon
Say '[2/5] Icona "Portale Lifecycle" sul desktop' Cyan
if ($chrome) { Link $PortalLnk $chrome ('"' + $Url + '"') $null $chrome 'icona Portale' }
else {
    Say '  Chrome non trovato: l''icona apre il browser predefinito' Yellow
    Link $PortalLnk $Url $null $null $null 'icona Portale'
}

# ================================================================ 4. autofill at start
Say '[3/5] AutoFillSuite parte con Windows' Cyan
$afs = $null
$beside = Join-Path $Here 'AutoFillSuite.exe'
if (Test-Path $beside) { $afs = Get-Item $beside }
else { $afs = FindFile 'AutoFillSuite.exe' @($Desktop, (Join-Path $env:USERPROFILE 'Downloads')) }
if ($afs) {
    Link $AfsStart $afs.FullName $null $afs.DirectoryName $afs.FullName 'avvio automatico'
    Link $AfsDesk  $afs.FullName $null $afs.DirectoryName $afs.FullName 'icona AutoFillSuite'
    if ($afs.FullName -like '*\Downloads\*' -or $afs.FullName -like '*\AppData\Local\Temp\*') {
        Say '  ATTENZIONE: AutoFillSuite sta in Download/Temp. Spostalo (es. sul Desktop) e rilancia questo script.' Yellow
    }
} else {
    Ko 'AutoFillSuite.exe non trovato: estrai lo zip della release e rilancia questo script da li'''
}

# ================================================================ 5. print program
Say '[4/5] Programma della stampante' Cyan
$proxy = FindFile $ProxyName @($Desktop)
if ($proxy) { Ok ('trovato: ' + $proxy.FullName) }
else { Ko ($ProxyName + ' non trovato: lo scegli dopo in AutoFillSuite > Impostazioni > Giornata') }

# ================================================================ 6. autofill settings
Say '[5/5] Impostazioni di AutoFillSuite' Cyan
if (WaitAutoFillClosed) {
    $set = [ordered]@{ 'station.autoStart' = 'true'; 'station.portalUrl' = $Url }
    if ($chrome) { $set['station.browserPath'] = $chrome }
    if ($proxy)  { $set['station.proxyPath']   = $proxy.FullName }
    try {
        # without the day it last prepared, the next start prepares again: today too
        WriteSettings $set @('station.lastDay')
        Ok 'indirizzo del portale salvato'
        if ($chrome) { Ok ('browser: ' + $chrome) } else { Say '  browser: quello predefinito di Windows' Yellow }
        if ($proxy) { Ok 'programma della stampante salvato' }
        Ok 'al primo avvio di ogni giorno: apre portale e stampante'
    } catch { Ko ('impostazioni non scritte: ' + $_.Exception.Message) }
} else { Ko 'AutoFillSuite e'' aperto: impostazioni non scritte (chiudilo e rilancia)' }

# ================================================================ the end
Say ''
if ($script:ko -eq 0) { Say ('TUTTO OK (' + $script:ok + ' passi)') Green }
else { Say ('FATTO, ma ' + $script:ko + ' cose da guardare: le righe [KO] qui sopra') Yellow }
Say ''
Say 'Da adesso:' Cyan
Say '  - accendi il PC: parte AutoFillSuite, che apre il portale e il programma della stampante'
Say '  - le icone di Chrome e "Portale Lifecycle" aprono il portale'
Say '  - per tornare indietro: ANNULLA-POSTAZIONE.bat'
Say ''
if ($afs -and -not $Quiet) {
    $a = Read-Host 'INVIO = apri AutoFillSuite adesso e prova   (N = no)'
    if ($a -notmatch '^[nN]') {
        Start-Process -FilePath $afs.FullName -WorkingDirectory $afs.DirectoryName
        Say 'AutoFillSuite si sta aprendo: tra pochi secondi portale e stampante.' Green
    }
}
if ($script:ko -gt 0) { exit 1 }
exit 0
