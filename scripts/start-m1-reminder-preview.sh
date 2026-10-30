#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/resolve-python.sh
CF_RUNTIME_DIR="$(pwd)/tmp/runtime";mkdir -p "$CF_RUNTIME_DIR/temp" "$CF_RUNTIME_DIR/home" "$CF_RUNTIME_DIR/maven-repository" "$CF_RUNTIME_DIR/npm-cache" "$CF_RUNTIME_DIR/embedded-pg"
CF_RUNTIME_JAVA="$CF_RUNTIME_DIR";if command -v cygpath >/dev/null 2>&1;then CF_RUNTIME_JAVA="$(cygpath -m "$CF_RUNTIME_DIR")";fi
export TEMP="$CF_RUNTIME_JAVA/temp" TMP="$CF_RUNTIME_JAVA/temp"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djava.io.tmpdir=$CF_RUNTIME_JAVA/temp -Duser.home=$CF_RUNTIME_JAVA/home -Dot.epg.cache-dir=$CF_RUNTIME_JAVA/embedded-pg -Dot.epg.working-dir=$CF_RUNTIME_JAVA/temp"
export MAVEN_OPTS="${MAVEN_OPTS:-} -Dmaven.repo.local=$CF_RUNTIME_JAVA/maven-repository"
export npm_config_cache="$CF_RUNTIME_JAVA/npm-cache"
MAVEN="${CF_MAVEN_EXECUTABLE:-./services/platform/mvnw}"
if [ ! -d apps/web/node_modules ];then (cd apps/web && npm ci);fi
bash scripts/prepare-m1-http.sh
"$MAVEN" -B -f services/seats/pom.xml -DskipTests package
exec "$PYTHON" scripts/check_m1_reminders.py --preview "$@"
