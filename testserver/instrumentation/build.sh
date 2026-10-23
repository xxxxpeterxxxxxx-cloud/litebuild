#!/usr/bin/env bash
# Builds litebuild-faults.jar with plain javac (no Gradle).
# Classpath: the official 26.2 server jar (shipped with Mojang names),
# the game's bundled libraries, Fabric Loader, the needed Fabric API
# modules and sponge-mixin annotations.
set -euo pipefail
cd "$(dirname "$0")"

JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/javac}"
JAVAC="${JAVAC:-javac}"
JAR="${JAVA_HOME:+$JAVA_HOME/bin/jar}"
JAR="${JAR:-jar}"

CP="lib/server-26.2.jar"
for j in lib/game-libs/*.jar lib/fabric-modules/*.jar; do
    CP="$CP:$j"
done
CP="$CP:lib/fabric-loader-0.19.5.jar:lib/sponge-mixin-0.17.4+mixin.0.8.7.jar"

rm -rf build/classes
mkdir -p build/classes

find src -name "*.java" > build/sources.txt
"$JAVAC" --release 21 -encoding UTF-8 -cp "$CP" -d build/classes @build/sources.txt

OUT="build/litebuild-faults.jar"
rm -f "$OUT"
"$JAR" --create --file "$OUT" \
    -C build/classes . \
    -C src fabric.mod.json \
    -C src litebuild-faults.mixins.json

mkdir -p ../mods
cp "$OUT" ../mods/
echo "built $OUT -> ../mods/"
