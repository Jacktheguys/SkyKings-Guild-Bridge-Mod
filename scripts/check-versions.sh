#!/usr/bin/env bash
# The CI version of build.ps1's safety check: the mod ships ONE jar for every
# Minecraft version in versions.json, so it's compiled against each of them and
# every Minecraft/Fabric/Mojang method and field it uses is compared. If any of
# them differs between versions, this fails (handle it like ChatAccess.java does).
#
# Needs JDK 25 (JAVA_HOME) and jq. Leaves the classes of the last version built.
set -euo pipefail
cd "$(dirname "$0")/.."

JAVAP="${JAVA_HOME:+$JAVA_HOME/bin/}javap"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# Everything Minecraft/Fabric/Mojang the compiled mod refers to, with exact signatures.
refs() {
    find build/classes/java/main -name '*.class' -print0 \
        | xargs -0 "$JAVAP" -v -p \
        | grep -oE '//[[:space:]]+.*(net/minecraft|net/fabricmc|com/mojang).*$' \
        | sed -E 's#^//[[:space:]]+##; s#[[:space:]]+$##' \
        | sort -u
}

count=$(jq length versions.json)
baseline=""
for ((i = 0; i < count; i++)); do
    label=$(jq -r ".[$i].label" versions.json)
    mc=$(jq -r ".[$i].minecraft" versions.json)
    fabric=$(jq -r ".[$i].fabric" versions.json)
    echo "Checking against Minecraft $label..."
    ./gradlew clean compileJava -Pminecraft_version="$mc" -Pfabric_version="$fabric" --no-daemon -q
    refs > "$work/$i.txt"
    if [[ -z "$baseline" ]]; then
        baseline="$work/$i.txt"
        base_label="$label"
    elif ! diff "$baseline" "$work/$i.txt" > "$work/diff.txt"; then
        cat "$work/diff.txt"
        echo "::error::The mod uses something that differs between Minecraft $base_label and $label (listed above). Handle it like ChatAccess.java does."
        exit 1
    fi
done
echo "Every Minecraft version in versions.json uses the same methods and fields."
