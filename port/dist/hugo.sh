#!/bin/sh
# Hugo's Horrific Adventure - Kotlin/Swing port launcher (Linux).
# Run from the folder that holds the original game files, or pass that folder:
#   ./hugo.sh /path/to/HWIN_1
set -e
dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

jar=
for f in "$dir"/hugo-port-*-all.jar; do
    if [ -f "$f" ]; then jar=$f; break; fi
done
if [ -z "$jar" ]; then
    echo "hugo-port-*-all.jar was not found next to this script." >&2
    exit 1
fi
if ! command -v java >/dev/null 2>&1; then
    echo "Java 17 or newer was not found. Install a JRE/JDK, e.g. from https://adoptium.net/" >&2
    exit 1
fi

# If the game files sit next to the launcher, point the port at them.
if [ "$#" -eq 0 ] && [ -f "$dir/HUGOWIN1.EXE" ]; then
    set -- "$dir"
fi

exec java -jar "$jar" "$@"
