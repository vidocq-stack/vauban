#!/usr/bin/env bash
sdk use java 25.ea.4-open 2>/dev/null
sdk use maven 4.0.0-rc-5 2>/dev/null
mvn verify -pl vauban-tck-runner -Ptck "$@"
