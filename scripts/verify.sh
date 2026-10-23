#!/usr/bin/env bash
# verify.sh — reproduzierbares Release-Gate für GentleBuild.
#
# Ein einziges Kommando führt den vollständigen Verifikationslauf aus:
#
#     ./scripts/verify.sh
#
# Ablauf:
#   1. Preflight: java/javac im PATH, LIBGL_ALWAYS_SOFTWARE=1 (Standard,
#      überschreibbar), Display-Setup — vorhandenes DISPLAY wird respektiert;
#      ohne X-Display oder mit --isolated wird ein eigenes Xwayland auf einem
#      freien privaten Display gestartet (Standard ab :99, aufwärts suchend;
#      :0 und :2 werden nie angefasst und nur der selbst gestartete Xwayland
#      wird wieder beendet).
#   2. ./gradlew --offline clean test
#   3. ./gradlew --offline runClientGameTest   (alle registrierten Client-
#      GameTests laut src/gametest/resources/fabric.mod.json — das Manifest
#      steuert den Umfang, nicht dieses Skript)
#   4. ./gradlew --offline build
#   5. Archivierung aller Belege unter validation/release-gate-<zeitstempel>/
#
# Flags:
#   --skip-gametest    nur clean test + build (kein runClientGameTest)
#   --isolated         eigenes Xwayland auch bei vorhandenem Display
#   --display N        Start-Display für die freie Suche (Standard 99)
#   --timeout SEKUNDEN Limit je Gradle-Schritt (Standard 2400)
#
# Exit-Code: 0 = PASS, ungleich 0 = FAIL (Schrittfehler, Timeout oder
# fehlendes "BUILD SUCCESSFUL" im GameTest-Log).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

SKIP_GAMETEST=0
ISOLATED=0
DISPLAY_BASE=99
TIMEOUT=2400
# Displays, die niemals fuer ein eigenes Xwayland verwendet werden.
PROTECTED_DISPLAYS="0 2"

usage() {
	cat <<'EOF'
Usage: ./scripts/verify.sh [--skip-gametest] [--isolated] [--display N] [--timeout SEK]

  --skip-gametest    nur clean test + build (kein runClientGameTest)
  --isolated         eigenes Xwayland auch bei vorhandenem Display
  --display N        Start-Display für die freie Suche (Standard 99; :0/:2 nie)
  --timeout SEK      Limit je Gradle-Schritt (Standard 2400)
EOF
}

log() {
	printf '[verify] %s\n' "$*"
}

die() {
	printf '[verify] FEHLER: %s\n' "$*" >&2
	exit 2
}

is_uint() {
	case "$1" in
		''|*[!0-9]*) return 1 ;;
		*) return 0 ;;
	esac
}

# ---------------------------------------------------------------- args ----
while [ $# -gt 0 ]; do
	case "$1" in
		--skip-gametest) SKIP_GAMETEST=1 ;;
		--isolated)      ISOLATED=1 ;;
		--display)       DISPLAY_BASE="${2:?--display braucht eine Zahl}"; shift ;;
		--display=*)     DISPLAY_BASE="${1#*=}" ;;
		--timeout)       TIMEOUT="${2:?--timeout braucht Sekunden}"; shift ;;
		--timeout=*)     TIMEOUT="${1#*=}" ;;
		-h|--help)       usage; exit 0 ;;
		*) die "unbekanntes Flag: $1" ;;
	esac
	shift
done

is_uint "$DISPLAY_BASE" || die "--display muss eine positive Zahl sein (got: $DISPLAY_BASE)"
is_uint "$TIMEOUT"      || die "--timeout muss eine positive Zahl sein (got: $TIMEOUT)"
[ "$TIMEOUT" -gt 0 ]    || die "--timeout muss groesser als 0 sein"
case " $PROTECTED_DISPLAYS " in
	*" $DISPLAY_BASE "*) die "--display $DISPLAY_BASE ist geschuetzt (:0/:2 werden nie angefasst)" ;;
esac

# ------------------------------------------------------------- archive ----
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
ARCHIVE_DIR="validation/release-gate-$TIMESTAMP"
mkdir -p "$ARCHIVE_DIR/logs"

# Gesamte Konsolenausgabe zusaetzlich ins Archiv spiegeln.
exec > >(tee "$ARCHIVE_DIR/verify-console.log") 2>&1

log "Release-Gate $TIMESTAMP"
log "Archiv: $ARCHIVE_DIR"

# ----------------------------------------------------------- preflight ----
for tool in java javac git timeout sha256sum; do
	command -v "$tool" >/dev/null 2>&1 || die "Benötigtes Werkzeug fehlt im PATH: $tool"
done
[ -x ./gradlew ] || die "./gradlew ist nicht ausführbar"

export LIBGL_ALWAYS_SOFTWARE="${LIBGL_ALWAYS_SOFTWARE:-1}"
log "LIBGL_ALWAYS_SOFTWARE=$LIBGL_ALWAYS_SOFTWARE (Software-Rendering, überschreibbar)"

# Die Java-Toolchain verlangt JDK 25 (build.gradle). Gradle erkennt das
# JAVA_HOME-JDK immer; in nichtstandard Installationsordnern wird gesucht.
java_major() {
	"$1/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n 1
}

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ] \
	&& [ "$(java_major "$JAVA_HOME")" = "25" ]; then
	log "JAVA_HOME=$JAVA_HOME (JDK 25, respektiert)"
else
	JDK25=""
	for cand in \
		"$HOME"/.jdks-local/*/ \
		"$HOME"/.jdks/*/ \
		/usr/lib/jvm/*/ \
		"$HOME"/.gradle/jdks/*/ \
		"$HOME"/.sdkman/candidates/java/*/; do
		[ -x "${cand%/}/bin/javac" ] || continue
		if [ "$(java_major "${cand%/}")" = "25" ]; then
			JDK25="${cand%/}"
			break
		fi
	done
	if [ -n "$JDK25" ]; then
		export JAVA_HOME="$JDK25"
		log "JAVA_HOME=$JAVA_HOME (JDK 25, automatisch gefunden)"
	else
		log "WARNUNG: kein JDK 25 gefunden — Toolchain-Auto-Erkennung entscheidet"
	fi
fi

# ------------------------------------------------------------ display -----
XWAYLAND_PID=""
DISPLAY_NOTE=""

cleanup() {
	# Nur den von diesem Skript gestarteten Xwayland beenden.
	if [ -n "$XWAYLAND_PID" ] && kill -0 "$XWAYLAND_PID" 2>/dev/null; then
		log "Beende eigenes Xwayland (pid $XWAYLAND_PID)"
		kill "$XWAYLAND_PID" 2>/dev/null || true
		wait "$XWAYLAND_PID" 2>/dev/null || true
	fi
}
trap cleanup EXIT INT TERM

protected_display() {
	case " $PROTECTED_DISPLAYS " in
		*" $1 "*) return 0 ;;
		*) return 1 ;;
	esac
}

display_in_use() {
	local n="$1"
	[ -e "/tmp/.X11-unix/X$n" ] || [ -e "/tmp/.X$n-lock" ]
}

start_private_xwayland() {
	command -v Xwayland >/dev/null 2>&1 || die "Xwayland nicht gefunden — kein Display verfügbar"
	local d="$DISPLAY_BASE" tries=0
	while [ "$tries" -lt 200 ]; do
		if ! protected_display "$d" && ! display_in_use "$d"; then
			break
		fi
		d=$((d + 1))
		tries=$((tries + 1))
	done
	[ "$tries" -lt 200 ] || die "Kein freies Display ab :$DISPLAY_BASE gefunden"

	log "Starte eigenes Xwayland auf :$d"
	Xwayland ":$d" -noreset >/dev/null 2>&1 &
	XWAYLAND_PID=$!

	local waited=0
	while [ "$waited" -lt 150 ]; do
		if [ -e "/tmp/.X11-unix/X$d" ]; then
			export DISPLAY=":$d"
			DISPLAY_NOTE="DISPLAY=$DISPLAY (eigenes Xwayland, pid $XWAYLAND_PID, Basis :$DISPLAY_BASE)"
			log "$DISPLAY_NOTE"
			return 0
		fi
		if ! kill -0 "$XWAYLAND_PID" 2>/dev/null; then
			XWAYLAND_PID=""
			die "Xwayland auf :$d ist sofort beendet worden"
		fi
		sleep 0.1
		waited=$((waited + 1))
	done
	die "Xwayland auf :$d wurde nicht innerhalb von 15s bereit"
}

if [ "$ISOLATED" -eq 1 ]; then
	log "--isolated: eigenes Xwayland wird erzwungen"
	start_private_xwayland
elif [ -n "${DISPLAY:-}" ]; then
	DISPLAY_NOTE="DISPLAY=$DISPLAY (vorhandenes Display, respektiert)"
	log "$DISPLAY_NOTE"
else
	log "Kein X-Display verfügbar — starte privates Xwayland"
	start_private_xwayland
fi
printf '%s\n' "$DISPLAY_NOTE" > "$ARCHIVE_DIR/display.txt"

# -------------------------------------------------------------- steps -----
RESULT_CLEAN="nicht gelaufen"
RESULT_GAMETEST="nicht gelaufen"
RESULT_BUILD="nicht gelaufen"
FAILURES=0

run_step() {
	local name="$1" logfile="$2"
	shift 2
	log "=== Schritt: $name"
	log "    Kommando: $*"
	log "    Log: $logfile"
	local rc=0
	timeout --kill-after=15 "$TIMEOUT" "$@" >"$logfile" 2>&1 || rc=$?
	if [ "$rc" -eq 0 ]; then
		log "    OK"
		return 0
	fi
	if [ "$rc" -eq 124 ] || [ "$rc" -eq 137 ]; then
		log "    TIMEOUT nach ${TIMEOUT}s — Log-Ende:"
	else
		log "    FEHLER (rc=$rc) — Log-Ende:"
	fi
	tail -n 40 "$logfile" 2>/dev/null | sed 's/^/    | /' || true
	return "$rc"
}

# 1) clean test
if run_step "clean test" "$ARCHIVE_DIR/logs/01-clean-test.log" \
	./gradlew --offline clean test; then
	RESULT_CLEAN="OK"
else
	RESULT_CLEAN="FAIL"
	FAILURES=$((FAILURES + 1))
fi

# 2) runClientGameTest — volles Manifest, keine Einzelaufzählung.
GT_LOG="$ARCHIVE_DIR/logs/02-runClientGameTest.log"
if [ "$SKIP_GAMETEST" -eq 1 ]; then
	RESULT_GAMETEST="SKIPPED (--skip-gametest)"
	log "=== Schritt: runClientGameTest — übersprungen (--skip-gametest)"
elif run_step "runClientGameTest" "$GT_LOG" \
	./gradlew --offline runClientGameTest; then
	if grep -q "BUILD SUCCESSFUL" "$GT_LOG"; then
		RESULT_GAMETEST="OK (BUILD SUCCESSFUL)"
	else
		RESULT_GAMETEST="FAIL ('BUILD SUCCESSFUL' fehlt im Log)"
		log "    'BUILD SUCCESSFUL' fehlt im GameTest-Log — Log-Ende:"
		tail -n 40 "$GT_LOG" 2>/dev/null | sed 's/^/    | /' || true
		FAILURES=$((FAILURES + 1))
	fi
else
	RESULT_GAMETEST="FAIL"
	FAILURES=$((FAILURES + 1))
fi

# 3) build
if run_step "build" "$ARCHIVE_DIR/logs/03-build.log" \
	./gradlew --offline build; then
	RESULT_BUILD="OK"
else
	RESULT_BUILD="FAIL"
	FAILURES=$((FAILURES + 1))
fi

# ------------------------------------------------------------ archive -----
log "=== Archiviere Belege nach $ARCHIVE_DIR"

mkdir -p "$ARCHIVE_DIR/unit-xml" "$ARCHIVE_DIR/screenshots" "$ARCHIVE_DIR/fabric-mod-json"

if [ -d build/test-results/test ]; then
	cp build/test-results/test/*.xml "$ARCHIVE_DIR/unit-xml/" 2>/dev/null || true
fi
if [ -d build/reports/tests/test ]; then
	cp -r build/reports/tests/test "$ARCHIVE_DIR/unit-reports" 2>/dev/null || true
fi
if [ -d build/run/clientGameTest/screenshots ]; then
	cp -r build/run/clientGameTest/screenshots/. "$ARCHIVE_DIR/screenshots/" 2>/dev/null || true
fi

cp src/main/resources/fabric.mod.json \
	"$ARCHIVE_DIR/fabric-mod-json/src-main.fabric.mod.json" 2>/dev/null || true
cp src/gametest/resources/fabric.mod.json \
	"$ARCHIVE_DIR/fabric-mod-json/src-gametest.fabric.mod.json" 2>/dev/null || true
[ -f build/resources/main/fabric.mod.json ] && \
	cp build/resources/main/fabric.mod.json \
	"$ARCHIVE_DIR/fabric-mod-json/processed-main.fabric.mod.json" 2>/dev/null || true
[ -f build/resources/gametest/fabric.mod.json ] && \
	cp build/resources/gametest/fabric.mod.json \
	"$ARCHIVE_DIR/fabric-mod-json/processed-gametest.fabric.mod.json" 2>/dev/null || true

{
	echo "## git status"
	git status 2>&1 || true
	echo
	echo "## git status --porcelain=v1 -b"
	git status --porcelain=v1 -b 2>&1 || true
} > "$ARCHIVE_DIR/git-status.txt" 2>&1 || true

{
	echo "## git diff --check"
	git diff --check 2>&1 || true
	echo
	echo "## git diff --cached --check"
	git diff --cached --check 2>&1 || true
} > "$ARCHIVE_DIR/git-diff-check.txt" 2>&1 || true

if [ -d build/libs ]; then
	( cd build/libs && sha256sum ./*.jar ) \
		> "$ARCHIVE_DIR/jar-sha256.txt" 2>/dev/null || true
fi

# ------------------------------------------------------------ verdict -----
VERDICT="PASS"
[ "$FAILURES" -eq 0 ] || VERDICT="FAIL"

{
	echo "============================================================"
	echo "RELEASE-GATE: $VERDICT"
	echo "  clean test        : $RESULT_CLEAN"
	echo "  runClientGameTest : $RESULT_GAMETEST"
	echo "  build             : $RESULT_BUILD"
	echo "  Archiv            : $ARCHIVE_DIR"
	echo "  Display           : ${DISPLAY_NOTE:-kein}"
	echo "============================================================"
} | tee "$ARCHIVE_DIR/RESULT.txt"

# tee-Subprozess kurz Zeit zum Schreiben geben.
sleep 0.3

if [ "$VERDICT" = "PASS" ]; then
	exit 0
fi
exit 1
