# Convert Kenney SVG files to Android Vector Drawables
param(
    [string]$SrcDir = "$env:TEMP\kenney-prompts\svg",
    [string]$DstDir = "c:\Users\emir\TinkerNorth\Dish\app\src\main\res\drawable"
)

$files = @{
    # Xbox
    "xbox_button_color_a"     = "ic_gp_xbox_a"
    "xbox_button_color_b"     = "ic_gp_xbox_b"
    "xbox_button_color_x"     = "ic_gp_xbox_x"
    "xbox_button_color_y"     = "ic_gp_xbox_y"
    "xbox_dpad_none"          = "ic_gp_xbox_dpad"
    "xbox_dpad_up"            = "ic_gp_xbox_dpad_up"
    "xbox_dpad_down"          = "ic_gp_xbox_dpad_down"
    "xbox_dpad_left"          = "ic_gp_xbox_dpad_left"
    "xbox_dpad_right"         = "ic_gp_xbox_dpad_right"
    "xbox_lb"                 = "ic_gp_xbox_lb"
    "xbox_rb"                 = "ic_gp_xbox_rb"
    "xbox_lt"                 = "ic_gp_xbox_lt"
    "xbox_rt"                 = "ic_gp_xbox_rt"
    "xbox_stick_l"            = "ic_gp_xbox_stick_l"
    "xbox_stick_r"            = "ic_gp_xbox_stick_r"
    "xbox_button_view"        = "ic_gp_xbox_view"
    "xbox_button_menu"        = "ic_gp_xbox_menu"
    "xbox_guide"              = "ic_gp_xbox_guide"
    # PlayStation
    "playstation_button_color_cross"    = "ic_gp_ps_cross"
    "playstation_button_color_circle"   = "ic_gp_ps_circle"
    "playstation_button_color_square"   = "ic_gp_ps_square"
    "playstation_button_color_triangle" = "ic_gp_ps_triangle"
    "playstation_dpad_none"             = "ic_gp_ps_dpad"
    "playstation_dpad_up"               = "ic_gp_ps_dpad_up"
    "playstation_dpad_down"             = "ic_gp_ps_dpad_down"
    "playstation_dpad_left"             = "ic_gp_ps_dpad_left"
    "playstation_dpad_right"            = "ic_gp_ps_dpad_right"
    "playstation_trigger_l1"            = "ic_gp_ps_l1"
    "playstation_trigger_r1"            = "ic_gp_ps_r1"
    "playstation_trigger_l2"            = "ic_gp_ps_l2"
    "playstation_trigger_r2"            = "ic_gp_ps_r2"
    "playstation_stick_l"               = "ic_gp_ps_stick_l"
    "playstation_stick_r"               = "ic_gp_ps_stick_r"
    "playstation_button_analog"         = "ic_gp_ps_analog"
}

foreach ($kv in $files.GetEnumerator()) {
    $svgPath = Join-Path $SrcDir "$($kv.Key).svg"
    $outPath = Join-Path $DstDir "$($kv.Value).xml"
    if (-not (Test-Path $svgPath)) { Write-Warning "Missing: $svgPath"; continue }

    $svg = Get-Content $svgPath -Raw
    # Extract all path elements
    $paths = [regex]::Matches($svg, '<path\s+stroke="[^"]*"\s+fill="([^"]*)"\s+d="([^"]*)"')
    if ($paths.Count -eq 0) { Write-Warning "No paths in $svgPath"; continue }

    $vdPaths = ""
    foreach ($m in $paths) {
        $fill = $m.Groups[1].Value
        $d = $m.Groups[2].Value -replace "`n"," " -replace "`r"," "
        # Convert fill color
        if ($fill -eq "#FFFFFF") { $fill = "#FFFFFFFF" }
        elseif ($fill -match "^#[0-9A-Fa-f]{6}$") { $fill = "#FF" + $fill.Substring(1) }
        $vdPaths += "    <path`n        android:fillColor=`"$fill`"`n        android:pathData=`"$d`" />`n"
    }

    $xml = @"
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="64dp"
    android:height="64dp"
    android:viewportWidth="64"
    android:viewportHeight="64">
$vdPaths</vector>
"@
    Set-Content -Path $outPath -Value $xml -Encoding UTF8
    Write-Output "Created: $($kv.Value).xml"
}
