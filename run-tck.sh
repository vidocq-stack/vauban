#!/usr/bin/env bash
# The CDI Lite and AtInject TCKs. They FAIL when a suite does: the runner used to carry
# surefire's testFailureIgnore, so a red TCK still printed BUILD SUCCESS and the
# only way to know was to read target/surefire-reports/TestSuite.txt by hand
# (Vidocq/vauban#85). The suite is 774/774; a failure here is a regression.
set -euo pipefail
cd "$(dirname "$0")"
# The Maven wrapper, so the run uses the Maven the repository pins (.mvn/wrapper), whatever
# `mvn` is on the PATH. Java comes from the environment: `sdk env` reads .sdkmanrc.
#
# Both runners: this repository certifies against two suites, and the script used to
# run only the first, so a red AtInject TCK was invisible from here. --fail-at-end still
# runs the second when the first is red.
#
# -am builds vauban-core and the other modules the runners depend on from this working
# tree: without it the runners resolve them from ~/.m2, so a run before `install` tested
# the last installed core, not the change at hand. Their unit tests run too; with
# -Dtest=…, failIfNoSpecifiedTests=false keeps the modules that have no such test green.
# CI installs the reactor before the TCK step, so `-am` is not needed there: the pull-request
# workflow's `-pl <runner> -Ptck test` resolves the modules it tests from that install.
./mvnw verify -Ptck -pl vauban-tck-runner,vauban-atinject-tck-runner -am --fail-at-end \
    -Dsurefire.failIfNoSpecifiedTests=false "$@"
