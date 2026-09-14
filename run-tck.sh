#!/usr/bin/env bash
# The CDI Lite TCK. It FAILS when the suite does: the runner used to carry
# surefire's testFailureIgnore, so a red TCK still printed BUILD SUCCESS and the
# only way to know was to read target/surefire-reports/TestSuite.txt by hand
# (Vidocq/vauban#85). The suite is 774/774; a failure here is a regression.
set -euo pipefail
sdk use java 25.ea.4-open 2>/dev/null || true
sdk use maven 3.9.16 2>/dev/null || true
mvn verify -pl vauban-tck-runner -Ptck "$@"
