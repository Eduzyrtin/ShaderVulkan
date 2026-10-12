<#
.SYNOPSIS
    Genera app/src/main/res/values/crt_palettes.xml: los atributos de color del tema CRT y las 73 paletas (72 tonos + neutro).

.DESCRIPTION
    Cada rol de color (texto, resaltado, tenue, línea, fondo, glow...) se define una sola vez, en OKLCH, a partir de la paleta
    clásica de T09 (la verde-amarilla sacada de la captura de referencia de Eduardo). Una paleta nueva conserva la luminosidad (L) y
    el croma (C) de cada rol y solo cambia el tono (h): todos los tonos tienen el mismo brillo de fósforo y el mismo contraste. Si
    un tono queda fuera de gama (azul puro muy claro) se baja el croma, nunca la luminosidad.

    Los colores de estado (alerta en rojo, aviso en ámbar) se quedan fijos; si el tono elegido cae a menos de 40 grados de uno de
    ellos, ese color se aparta hasta los 40 grados para no confundirse con el color principal.

    El índice "clásico" reproduce la paleta de T09 al valor (el script lo comprueba y aborta si no).

    No editar el XML generado a mano: cambiar este script y volver a ejecutarlo:
        pwsh tools/gen-crt-palettes.ps1
    Ver PROYECTO.md, T60.
#>
param([string]$Out = (Join-Path $PSScriptRoot '..\app\src\main\res\values\crt_palettes.xml'))

$ErrorActionPreference = 'Stop'
$inv = [System.Globalization.CultureInfo]::InvariantCulture

# ---------------------------------------------------------------- Conversión de color (OKLab / OKLCH <-> sRGB)

function ToLinear([double]$c) { if ($c -le 0.04045) { $c / 12.92 } else { [math]::Pow(($c + 0.055) / 1.055, 2.4) } }
function ToSrgb([double]$c)   { if ($c -le 0.0031308) { 12.92 * $c } else { 1.055 * [math]::Pow($c, 1 / 2.4) - 0.055 } }

# "RRGGBB" -> @(L, C, h)  (h en grados, 0..360)
function HexToOklch([string]$hex) {
    $r = ToLinear ([Convert]::ToInt32($hex.Substring(0, 2), 16) / 255.0)
    $g = ToLinear ([Convert]::ToInt32($hex.Substring(2, 2), 16) / 255.0)
    $b = ToLinear ([Convert]::ToInt32($hex.Substring(4, 2), 16) / 255.0)
    $lc = [math]::Cbrt(0.4122214708 * $r + 0.5363325363 * $g + 0.0514459929 * $b)
    $mc = [math]::Cbrt(0.2119034982 * $r + 0.6806995451 * $g + 0.1073969566 * $b)
    $sc = [math]::Cbrt(0.0883024619 * $r + 0.2817188376 * $g + 0.6299787005 * $b)
    $lum = 0.2104542553 * $lc + 0.7936177850 * $mc - 0.0040720468 * $sc
    $oa = 1.9779984951 * $lc - 2.4285922050 * $mc + 0.4505937099 * $sc
    $ob = 0.0259040371 * $lc + 0.7827717662 * $mc - 0.8086757660 * $sc
    $chroma = [math]::Sqrt($oa * $oa + $ob * $ob)
    $hue = [math]::Atan2($ob, $oa) * 180 / [math]::PI
    if ($hue -lt 0) { $hue += 360 }
    , @($lum, $chroma, $hue)
}

# (L, C, h) -> canales lineales sin recortar
function OklchToLinear([double]$lum, [double]$chroma, [double]$hue) {
    $oa = $chroma * [math]::Cos($hue * [math]::PI / 180)
    $ob = $chroma * [math]::Sin($hue * [math]::PI / 180)
    $lc = [math]::Pow($lum + 0.3963377774 * $oa + 0.2158037573 * $ob, 3)
    $mc = [math]::Pow($lum - 0.1055613458 * $oa - 0.0638541728 * $ob, 3)
    $sc = [math]::Pow($lum - 0.0894841775 * $oa - 1.2914855480 * $ob, 3)
    , @((4.0767416621 * $lc - 3.3077115913 * $mc + 0.2309699292 * $sc),
        (-1.2684380046 * $lc + 2.6097574011 * $mc - 0.3413193965 * $sc),
        (-0.0041960863 * $lc - 0.7034186147 * $mc + 1.7076147010 * $sc))
}

function InGamut($rgb) {
    foreach ($v in $rgb) { if ($v -lt -0.0005 -or $v -gt 1.0005) { return $false } }
    $true
}

# (L, C, h) -> "RRGGBB"; si el croma no cabe en sRGB, se baja el croma (la luminosidad y el tono no se tocan)
function OklchToHex([double]$lum, [double]$chroma, [double]$hue) {
    $rgb = OklchToLinear $lum $chroma $hue
    if (-not (InGamut $rgb)) {
        $lo = 0.0; $hi = $chroma
        for ($i = 0; $i -lt 24; $i++) {
            $mid = ($lo + $hi) / 2
            if (InGamut (OklchToLinear $lum $mid $hue)) { $lo = $mid } else { $hi = $mid }
        }
        $rgb = OklchToLinear $lum $lo $hue
    }
    $out = ""
    foreach ($v in $rgb) {
        $enc = [math]::Min(1.0, [math]::Max(0.0, (ToSrgb ([math]::Min(1.0, [math]::Max(0.0, $v))))))
        $out += ([int][math]::Round($enc * 255)).ToString("X2")
    }
    $out
}

# Croma máximo que cabe en sRGB con esa luminosidad y ese tono
function MaxChroma([double]$lum, [double]$hue) {
    $lo = 0.0; $hi = 0.4
    for ($i = 0; $i -lt 22; $i++) {
        $mid = ($lo + $hi) / 2
        if (InGamut (OklchToLinear $lum $mid $hue)) { $lo = $mid } else { $hi = $mid }
    }
    $lo
}

function AngDiff([double]$a, [double]$b) {   # a - b, en (-180, 180]
    $d = ($a - $b) % 360
    if ($d -gt 180) { $d -= 360 } elseif ($d -le -180) { $d += 360 }
    $d
}

function Norm360([double]$h) { $x = $h % 360; if ($x -lt 0) { $x += 360 }; $x }

# ---------------------------------------------------------------- Roles (paleta clásica de T09)

# Roles con tono: siguen al tono elegido. Roles de estado: tono fijo (con la regla de separación de arriba).
$tinted = [ordered]@{
    bg = '1B2413'; bgEdge = '0B1008'; panel = '101709'; panelSolid = '141C0E'; vig = '050803'
    phosphor = 'C6F28E'; hot = 'EEFC9C'; dim = '8DB063'; faint = '4E6936'; ink = '16200E'
}
$status = [ordered]@{ alert = 'FF846A'; warn = 'FFCB6B'; alertSoft = 'FFB8A6'; warnSoft = 'FFE2A8' }

$classicLch = HexToOklch $tinted.phosphor
$classicHue = $classicLch[2]
$hueBase = $classicHue % 5                           # los tonos son hueBase + 5*i: el clásico cae exactamente en un paso
$classicIndex = [int][math]::Floor($classicHue / 5)
$hueSteps = 72
$neutralIndex = $hueSteps

$role = @{}                                           # nombre -> @(L, C, desfase de tono respecto al fósforo)
foreach ($k in $tinted.Keys) {
    $lch = HexToOklch $tinted[$k]
    $role[$k] = @($lch[0], $lch[1], (AngDiff $lch[2] $classicHue))
}
$statusLch = @{}
foreach ($k in $status.Keys) { $statusLch[$k] = HexToOklch $status[$k] }

# Atributo -> (rol, alfa en hex). Todo lo que el tema ofrece a layouts, drawables y código.
$attrs = [ordered]@{
    crtBg = @('bg', 'FF'); crtBgEdge = @('bgEdge', 'FF')
    crtPanel = @('panel', '99'); crtPanelSolid = @('panelSolid', 'F2')
    crtVig99 = @('vig', '99'); crtVig80 = @('vig', '80')
    crtPhosphor = @('phosphor', 'FF'); crtHot = @('hot', 'FF'); crtDim = @('dim', 'FF'); crtFaint = @('faint', 'FF')
    crtInk = @('ink', 'FF'); crtInk40 = @('ink', '40')
    crtAlert = @('alert', 'FF'); crtWarn = @('warn', 'FF'); crtAlertSoft = @('alertSoft', 'FF'); crtWarnSoft = @('warnSoft', 'FF')
    crtGlow = @('phosphor', '8C'); crtGlowDim = @('dim', '59'); crtGlowHot = @('hot', 'A6')
    crtGlowAlert = @('alert', '8C'); crtGlowWarn = @('warn', '80')
    crtGhost = @('phosphor', '1F'); crtGhostStrong = @('phosphor', '38')
    crtPhosphor00 = @('phosphor', '00'); crtPhosphor0A = @('phosphor', '0A'); crtPhosphor0F = @('phosphor', '0F')
    crtPhosphor1A = @('phosphor', '1A'); crtPhosphor1C = @('phosphor', '1C'); crtPhosphor26 = @('phosphor', '26')
    crtHot1A = @('hot', '1A'); crtHot1F = @('hot', '1F'); crtHot26 = @('hot', '26'); crtHot33 = @('hot', '33')
    crtHot40 = @('hot', '40'); crtHot59 = @('hot', '59')
    crtFaint73 = @('faint', '73')
    crtAlert14 = @('alert', '14'); crtAlert26 = @('alert', '26'); crtAlert33 = @('alert', '33')
    crtAlert38 = @('alert', '38'); crtAlert66 = @('alert', '66')
    crtWarn14 = @('warn', '14'); crtWarn26 = @('warn', '26'); crtWarn33 = @('warn', '33')
    crtWarn38 = @('warn', '38'); crtWarn66 = @('warn', '66')
}

# ---------------------------------------------------------------- Paletas

# Tono de un color de estado para un tono base. Si queda a menos de 40 grados del base se aparta hasta 40, y elige el lado que lo deja
# más lejos del OTRO color de estado ($otherHue): así, en un tema rojo, STOP se va al rosa en vez de al amarillo del aviso.
function StatusHue([double]$statusHue, [double]$base, [double]$otherHue) {
    if ([math]::Abs((AngDiff $statusHue $base)) -ge 40) { return $statusHue }
    $up = Norm360 ($base + 40)
    $down = Norm360 ($base - 40)
    if ([math]::Abs((AngDiff $up $otherHue)) -ge [math]::Abs((AngDiff $down $otherHue))) { $up } else { $down }
}

# A luminosidad fija, un rojo o un azul puros no tienen croma suficiente y el texto saldría rosa o celeste casi blanco. En esos
# tonos el texto (fósforo y resaltado) baja la luminosidad lo justo para conservar $chromaKeep del croma del clásico; lo tenue baja
# la mitad. En el tono clásico no baja nada.
$chromaKeep = 0.85
$maxDrop = 0.22

# Devuelve nombre-de-rol -> "RRGGBB" para la paleta i (0..71 = tono, 72 = neutro)
function BuildPalette([int]$i) {
    $neutral = ($i -eq $neutralIndex)
    $base = if ($neutral) { $classicHue } else { Norm360 ($hueBase + 5 * $i) }
    $p = @{}
    $drop = 0.0
    if (-not $neutral) {
        $ph = $role.phosphor
        while ($drop -lt $maxDrop -and (MaxChroma ($ph[0] - $drop) $base) -lt $chromaKeep * $ph[1]) { $drop += 0.005 }
    }
    foreach ($k in $tinted.Keys) {
        $r = $role[$k]
        $c = if ($neutral) { 0.0 } else { $r[1] }
        $lk = $r[0]
        if ($k -eq 'phosphor' -or $k -eq 'hot') { $lk -= $drop } elseif ($k -eq 'dim') { $lk -= $drop / 2 }
        $p[$k] = OklchToHex $lk $c (Norm360 ($base + $r[2]))
    }
    $alertHue = if ($neutral) { $statusLch.alert[2] } else { StatusHue $statusLch.alert[2] $base $statusLch.warn[2] }
    $warnHue  = if ($neutral) { $statusLch.warn[2] }  else { StatusHue $statusLch.warn[2] $base $alertHue }
    $p.alert = OklchToHex $statusLch.alert[0] $statusLch.alert[1] $alertHue
    $p.warn = OklchToHex $statusLch.warn[0] $statusLch.warn[1] $warnHue
    $p.alertSoft = OklchToHex $statusLch.alertSoft[0] $statusLch.alertSoft[1] (Norm360 ($alertHue + (AngDiff $statusLch.alertSoft[2] $statusLch.alert[2])))
    $p.warnSoft = OklchToHex $statusLch.warnSoft[0] $statusLch.warnSoft[1] (Norm360 ($warnHue + (AngDiff $statusLch.warnSoft[2] $statusLch.warn[2])))
    $p
}

# Comprobación: el índice clásico debe reproducir la paleta de T09 (tolerancia de 2 niveles por canal por el redondeo de OKLCH)
$check = BuildPalette $classicIndex
foreach ($k in $tinted.Keys + $status.Keys) {
    $want = if ($tinted.Contains($k)) { $tinted[$k] } else { $status[$k] }
    for ($c = 0; $c -lt 3; $c++) {
        $a = [Convert]::ToInt32($check[$k].Substring($c * 2, 2), 16)
        $b = [Convert]::ToInt32($want.Substring($c * 2, 2), 16)
        if ([math]::Abs($a - $b) -gt 2) { throw "El tono clásico no reproduce '$k': generado $($check[$k]), esperado $want" }
    }
}

# Atajos con nombre (grados OKLCH) -> índice de paleta
function PresetIndex([double]$deg) { [int]([math]::Round(((Norm360 $deg) - $hueBase) / 5) % $hueSteps) }
$presets = [ordered]@{ crt_preset_classic = $classicIndex; crt_preset_amber = (PresetIndex 80); crt_preset_red = (PresetIndex 28)
    crt_preset_magenta = (PresetIndex 335); crt_preset_ice = (PresetIndex 255); crt_preset_cyan = (PresetIndex 200) }

# ---------------------------------------------------------------- Salida

$sb = New-Object System.Text.StringBuilder
function Line([string]$s) { [void]$sb.Append($s).Append("`n") }

function StyleItems([hashtable]$p) {
    foreach ($name in $attrs.Keys) {
        $r = $attrs[$name][0]; $alpha = $attrs[$name][1]
        Line ('        <item name="{0}">#{1}{2}</item>' -f $name, $alpha, $p[$r])
    }
}

Line '<?xml version="1.0" encoding="utf-8"?>'
Line '<!--'
Line '    GENERADO por tools/gen-crt-palettes.ps1: no editar a mano (ver PROYECTO.md, T60).'
Line ('    {0} paletas: {1} tonos cada 5 grados (OKLCH, a partir de {2:N1} grados) + neutro. Indice del clasico: {3}.' -f ($hueSteps + 1), $hueSteps, $hueBase, $classicIndex)
Line '-->'
Line '<resources>'
Line ''
Line '    <!-- Atributos de color del tema CRT. Los layouts y drawables usan ?attr/crtX; el valor lo pone la paleta aplicada al tema. -->'
foreach ($name in $attrs.Keys) { Line ('    <attr name="{0}" format="color|reference" />' -f $name) }
Line ''
Line '    <!-- Valores por defecto (paleta clásica): una pantalla que no aplique ninguna paleta se ve como en T09 -->'
Line '    <style name="Theme.ShaderVulkan.Palette" parent="Theme.Material3.Dark.NoActionBar">'
StyleItems $check
Line '    </style>'
Line ''
$swatches = New-Object System.Collections.Generic.List[string]
for ($i = 0; $i -le $neutralIndex; $i++) {
    $p = BuildPalette $i
    $name = if ($i -eq $neutralIndex) { 'CrtPalette_Neutral' } else { "CrtPalette_H$i" }
    Line ('    <style name="{0}">' -f $name)
    StyleItems $p
    Line '    </style>'
    $swatches.Add($p.dim)
}
Line ''
Line '    <!-- Estilo de cada paleta, en orden de índice (CrtTheme.paletteStyle) -->'
Line '    <array name="crt_palette_styles">'
for ($i = 0; $i -lt $hueSteps; $i++) { Line ('        <item>@style/CrtPalette_H{0}</item>' -f $i) }
Line '        <item>@style/CrtPalette_Neutral</item>'
Line '    </array>'
Line ''
Line '    <!-- Color representativo de cada paleta (rol "tenue", el más vivo): barra de arcoíris y atajos -->'
Line '    <array name="crt_hue_swatches">'
foreach ($s in $swatches) { Line ('        <item>#{0}</item>' -f $s) }
Line '    </array>'
Line ''
Line ('    <integer name="crt_hue_steps">{0}</integer>' -f $hueSteps)
Line ('    <integer name="crt_hue_neutral">{0}</integer>' -f $neutralIndex)
foreach ($k in $presets.Keys) { Line ('    <integer name="{0}">{1}</integer>' -f $k, $presets[$k]) }
Line '</resources>'

$full = [System.IO.Path]::GetFullPath($Out)
[System.IO.File]::WriteAllText($full, $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))
Write-Host ("OK {0}: {1} atributos, {2} paletas, clásico = índice {3} ({4:N1} grados)" -f $full, $attrs.Count, ($hueSteps + 1), $classicIndex, $classicHue)
foreach ($k in $presets.Keys) { Write-Host ("  {0} = {1}" -f $k, $presets[$k]) }
