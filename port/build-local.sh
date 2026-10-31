#!/bin/sh
# Offline build without Gradle (used for development where Maven Central is unreachable).
# Needs: kotlinc (Kotlin 2.1.x) and the LWJGL 3.3.6 release zip unpacked at $LWJGL.
set -e
LWJGL=${LWJGL:-$HOME/deps/lwjgl}
KOTLINC=${KOTLINC:-$HOME/deps/kotlinc/bin/kotlinc}
OUT=${OUT:-build-local}
CP=""
for m in lwjgl lwjgl-openal; do
  CP="$CP:$LWJGL/$m/$m.jar"
done
mkdir -p "$OUT"
"$KOTLINC" -jvm-target 17 -nowarn -cp "${CP#:}" -d "$OUT/hugo.jar" $(find src/main/kotlin -name '*.kt')
[ -d src/main/resources ] && jar uf "$OUT/hugo.jar" -C src/main/resources .
RT="$OUT/hugo.jar$CP"
for m in lwjgl lwjgl-openal; do RT="$RT:$LWJGL/$m/$m-natives-linux.jar"; done
echo "$RT:$(dirname $KOTLINC)/../lib/kotlin-stdlib.jar" > "$OUT/classpath.txt"
echo "built $OUT/hugo.jar"

# FAT=1: also build one runnable jar (kotlin-stdlib + LWJGL + Linux/Windows natives)
if [ "$FAT" = "1" ]; then
  T="$OUT/fat"; rm -rf "$T"; mkdir -p "$T"
  ( cd "$T" && unzip -qo ../hugo.jar && unzip -qo "$(dirname $KOTLINC)/../lib/kotlin-stdlib.jar" -x 'META-INF/*' )
  for m in lwjgl lwjgl-openal; do
    for j in "$LWJGL/$m/$m.jar" "$LWJGL/$m/$m-natives-linux.jar" "$LWJGL/$m/$m-natives-linux-arm64.jar" \
             "$LWJGL/$m/$m-natives-windows.jar" "$LWJGL/$m/$m-natives-windows-arm64.jar"; do
      [ -f "$j" ] && ( cd "$T" && unzip -qo "$j" -x 'META-INF/MANIFEST.MF' 'META-INF/*.SF' 'META-INF/*.RSA' )
    done
  done
  printf 'Manifest-Version: 1.0\nMain-Class: hugo.MainKt\n' > "$OUT/manifest.txt"
  rm -f "$OUT/hugo-all.jar"
  jar cfm "$OUT/hugo-all.jar" "$OUT/manifest.txt" -C "$T" .
  echo "built $OUT/hugo-all.jar"
fi
