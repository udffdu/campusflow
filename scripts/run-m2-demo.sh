#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -z "${BOOTSTRAP_ADMIN_PASSWORD:-}" ]; then
  read -r -s -p '为临时演示账号设置统一密码（至少 12 字符）: ' BOOTSTRAP_ADMIN_PASSWORD
  printf '\n'
  export BOOTSTRAP_ADMIN_PASSWORD
fi
(cd services/platform && ./mvnw -q -DskipTests package)
cd services/rooms
./mvnw -q test-compile org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath -Dmdep.outputFile=target/dev-classpath.txt
CF_JAVA=java
if [ -n "${JAVA_HOME:-}" ]; then CF_JAVA="$JAVA_HOME/bin/java"; fi
exec "$CF_JAVA" -cp "target/test-classes:target/classes:$(cat target/dev-classpath.txt)" io.campusflow.rooms.LocalDemo
