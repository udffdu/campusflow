#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# The only new runtime libraries are the same Spring Boot starters already used by D.
for module in platform seats; do
  ./services/platform/mvnw -B -f "services/$module/pom.xml" "${@}" test-compile \
    org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath \
    -Dmdep.outputFile=target/http-classpath.txt
done
