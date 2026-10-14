#!/usr/bin/env bash
set -euo pipefail

for command_name in java javac ant xvfb-run timeout unzip jar sha256sum; do
    command -v "${command_name}" >/dev/null || {
        printf 'missing command: %s\n' "${command_name}" >&2
        exit 2
    }
done

# the CSV contract is shared with ScanLedger (Registro QR): the same samples and
# the same document must sit in both repositories, byte for byte
(
    cd test/fixtures
    sha256sum --check --strict --quiet CHECKSUMS
) || {
    printf 'contract files changed: test/fixtures/CHECKSUMS does not match\n' >&2
    exit 1
}

rm -rf out build dist
mkdir -p out

javac --release 8 -Xlint:-options -d out $(find src test -name '*.java')
(
    cd src
    find . -name '*.md' -exec cp --parents {} ../out/ ';'
)

xvfb-run -a bash -eu -o pipefail <<'TESTS'
java -cp out app.core.RegistrationVerifierTest
java -cp out app.core.DownloadWatcherTest
java -cp out app.core.VerificationTaskTest
java -cp out app.core.CoreExtrasTest
java -cp out app.core.ScanGuardTest
java -cp out app.core.PairBurstTest
java -cp out app.core.RobotGateTest
java -cp out app.core.RobotReleaseTest
java -cp out app.core.ReportAtomicTest
java -cp out app.core.ScanQueueJournalTest
java -cp out app.core.SoundsTest
java -cp out app.core.CrashTest
java -cp out app.core.CsvImportTest
java -cp out app.core.ImportLedgerTest
java -cp out app.core.PreflightTest
java -cp out app.ui.GlyphSafetyTest
java -cp out app.ui.IconRenderTest
java -cp out app.ui.RunTableModelTest
java -cp out app.ui.FileModePanelTest
java -cp out app.ui.ShutdownTest
java -cp out app.ui.RangeModeTest
java -cp out app.ui.RegisteredTodayTest
java -cp out app.ui.ScanConfirmKeysTest
java -cp out app.ui.ScanRaceTest
java -cp out app.ui.ResultsOwnershipTest
java -cp out app.ui.ScanSessionTest
java -cp out app.ui.MidnightReportTest
java -cp out app.ui.ManualRenderTest
java -cp out app.ui.SpinnerCommitTest
java -cp out app.ui.ScanTabSupportTest
java -cp out app.ui.TextFitTest
java -cp out app.ui.StartupSmokeTest
java -cp out app.ui.StartupSmokeTest --saved
TESTS

ant clean jar

test -s dist/AutoFillSuite.jar
unzip -p dist/AutoFillSuite.jar META-INF/MANIFEST.MF \
    | tr -d '\r' \
    | grep -Fx 'Main-Class: app.Main'
jar tf dist/AutoFillSuite.jar | grep -Fx 'app/Main.class'
jar tf dist/AutoFillSuite.jar | grep -Fx 'app/docs/MANUAL.it.md'
jar tf dist/AutoFillSuite.jar | grep -Fx 'app/docs/MANUAL.en.md'

xvfb-run -a bash -eu -c '
java -jar dist/AutoFillSuite.jar >/tmp/package-startup.log 2>&1 &
pid=$!
sleep 5
kill -0 "$pid"
kill -TERM "$pid"
for attempt in 1 2 3 4 5; do
    kill -0 "$pid" 2>/dev/null || exit 0
    sleep 1
done
kill -KILL "$pid"
wait "$pid" || true
'

printf 'AutoFillSuite verification: PASS\n'
sha256sum dist/AutoFillSuite.jar
