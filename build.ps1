# Builds the mod: ONE jar for every Minecraft version in versions.json
# (release\skykings-guild-bridge-<version>.jar).
#
# It's compiled against the oldest version (gradle.properties). Then, so it
# can't crash on a newer version, it's compiled against each version in
# versions.json and every Minecraft/Fabric method and field it uses is compared:
# if any of them differs between versions, the build fails.
#
# Minecraft 26.x needs Java 25: a portable JDK 25 is downloaded into .\tools\
# the first time (~200 MB).

$ErrorActionPreference = "Stop"
$tools = Join-Path $PSScriptRoot "tools"
$jdk = Join-Path $tools "jdk-25"

# One exact JDK build, checked against its published SHA-256 before it's unpacked
# (https://github.com/adoptium/temurin25-binaries/releases). To move to a newer one,
# change both lines from that release's .zip and .zip.sha256.txt.
$jdkUrl = "https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1/OpenJDK25U-jdk_x64_windows_hotspot_25.0.4.1_1.zip"
$jdkSha256 = "00c847d804f4a78e9f04f2683faf14fed898535b177b7fc704486cb0284e9283"

if (-not (Test-Path (Join-Path $jdk "bin\java.exe"))) {
    Write-Host "Downloading JDK 25 (Temurin 25.0.4.1+1)..." -ForegroundColor Cyan
    New-Item -ItemType Directory -Force -Path $tools | Out-Null
    $zip = Join-Path $tools "jdk25.zip"
    Invoke-WebRequest -Uri $jdkUrl -OutFile $zip -UseBasicParsing
    $actual = (Get-FileHash -Algorithm SHA256 $zip).Hash.ToLower()
    if ($actual -ne $jdkSha256) {
        Remove-Item $zip
        throw "The JDK download doesn't match its published checksum (got $actual). Not using it."
    }
    Expand-Archive -Path $zip -DestinationPath $tools -Force
    Remove-Item $zip
    $extracted = Get-ChildItem $tools -Directory | Where-Object { $_.Name -like "jdk-25*" -and $_.Name -ne "jdk-25" } | Select-Object -First 1
    Rename-Item $extracted.FullName $jdk
}
$env:JAVA_HOME = $jdk
$javap = Join-Path $jdk "bin\javap.exe"

function Invoke-Gradle([string[]]$arguments) {
    $ErrorActionPreference = "Continue"
    & .\gradlew.bat @arguments --no-daemon -q 2>&1 | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $arguments" }
}

# Everything Minecraft/Fabric/Mojang the compiled mod refers to, with exact
# signatures: methods, fields, and the callbacks it passes to Fabric (lambdas).
function Get-GameReferences {
    $classes = Get-ChildItem (Join-Path $PSScriptRoot "build\classes\java\main") -Recurse -Filter *.class | ForEach-Object FullName
    & $javap -v -p @classes | Select-String -Pattern '//\s+(.*(net/minecraft|net/fabricmc|com/mojang).*)$' |
        ForEach-Object { $_.Matches[0].Groups[1].Value.Trim() } | Sort-Object -Unique
}

Push-Location $PSScriptRoot
try {
    $versions = Get-Content "versions.json" -Raw | ConvertFrom-Json
    $baseline = $null
    foreach ($v in $versions) {
        Write-Host "Checking against Minecraft $($v.label)..." -ForegroundColor Cyan
        Invoke-Gradle @("clean", "compileJava", "-Pminecraft_version=$($v.minecraft)", "-Pfabric_version=$($v.fabric)")
        $refs = Get-GameReferences
        if ($null -eq $baseline) { $baseline = $refs; $baseLabel = $v.label; continue }
        $diff = Compare-Object $baseline $refs
        if ($diff) {
            $diff | ForEach-Object { Write-Host "  $($_.SideIndicator) $($_.InputObject)" -ForegroundColor Red }
            throw "The mod uses something that differs between Minecraft $baseLabel and $($v.label) (listed above). Handle it like ChatAccess.java does."
        }
    }

    Write-Host "Building the jar..." -ForegroundColor Cyan
    Invoke-Gradle @("clean", "build")
    $release = Join-Path $PSScriptRoot "release"  # not under build\, which "clean" deletes
    New-Item -ItemType Directory -Force -Path $release | Out-Null
    Copy-Item "build\libs\*.jar" $release -Force -Exclude "*-sources.jar"
} finally {
    Pop-Location
}
Get-ChildItem (Join-Path $PSScriptRoot "release\*.jar") | ForEach-Object { "Built: $($_.FullName)" }
