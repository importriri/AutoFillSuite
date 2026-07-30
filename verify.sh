#!/usr/bin/env bash
set -euo pipefail

for command_name in java javac ant xvfb-run timeout unzip jar; do
    command -v "${command_name}" >/dev/null || {
        printf 'missing command: %s\n' "${command_name}" >&2
        exit 2
    }
done

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
java -cp out app.ui.GlyphSafetyTest
java -cp out app.ui.IconRenderTest
java -cp out app.ui.RunTableModelTest
java -cp out app.ui.ManualRenderTest
java -cp out app.ui.SpinnerCommitTest
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
