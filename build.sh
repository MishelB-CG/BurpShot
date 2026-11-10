#!/usr/bin/env bash
# Builds burpshot.jar: the extension plus the bundled SSH library (JSch, used for SFTP/SCP in "Share").
# Needs a JDK 17+ and the Montoya API jar (default ../montoya-api.jar, or set MONTOYA=/path/to/montoya-api.jar).
# Usage:  ./build.sh        (works in Git Bash on Windows, Linux and macOS)
set -euo pipefail
cd "$(dirname "$0")"

MONTOYA="${MONTOYA:-../montoya-api.jar}"
JSCH="$(ls libs/jsch-*.jar | head -1)"
[ -f "$MONTOYA" ] || { echo "Montoya API jar not found at $MONTOYA (set MONTOYA=...)"; exit 1; }

# the bundled third-party library must be exactly the one that was reviewed
( cd libs && sha256sum -c "$(basename "$JSCH").sha256" )

SEP=":"
case "$(uname -s)" in MINGW* | MSYS* | CYGWIN*) SEP=";" ;; esac

rm -rf build
mkdir -p build/classes
javac -encoding UTF-8 --release 17 -cp "$MONTOYA$SEP$JSCH" -d build/classes src/main/java/ptshot/*.java
cp -r src/main/resources/META-INF build/classes/

# fat jar: JSch's classes (incl. its multi-release variants) without its signatures / maven metadata
( cd build/classes && jar xf "../../$JSCH" com META-INF/versions )
printf 'Multi-Release: true\n' > build/manifest.txt
jar cfm burpshot.jar build/manifest.txt -C build/classes .

echo "built burpshot.jar ($(wc -c < burpshot.jar) bytes)"
