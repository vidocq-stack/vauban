#!/usr/bin/env bash
# The CDI Lite and AtInject TCKs. They FAIL when a suite does: the runner used to carry
# surefire's testFailureIgnore, so a red TCK still printed BUILD SUCCESS and the
# only way to know was to read target/surefire-reports/TestSuite.txt by hand
# (Vidocq/vauban#85). The suite is 774/774; a failure here is a regression.
set -euo pipefail
sdk use java 25.ea.4-open 2>/dev/null || true
sdk use maven 3.9.16 2>/dev/null || true
# Both runners: this repository certifies against two suites, and the script used to
# run only the first, so a red AtInject TCK was invisible from here.
mvn verify -pl vauban-tck-runner -Ptck "$@"
mvn verify -pl vauban-atinject-tck-runner -Ptck "$@"
