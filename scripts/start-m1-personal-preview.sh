#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -n "${CF_PYTHON:-}" ]; then
  CF_PYTHON_CMD="$CF_PYTHON"
elif python3 -c 'import sys; assert sys.version_info >= (3, 11)' >/dev/null 2>&1; then
  CF_PYTHON_CMD=python3
elif python -c 'import sys; assert sys.version_info >= (3, 11)' >/dev/null 2>&1; then
  CF_PYTHON_CMD=python
else
  echo '需要 Python 3.11 或更高版本；可通过 CF_PYTHON 指定解释器。' >&2
  exit 1
fi
CF_RUNTIME_DIR="$(pwd)/tmp/runtime"
CF_RUNTIME_JAVA="$CF_RUNTIME_DIR"
if command -v cygpath >/dev/null 2>&1; then CF_RUNTIME_JAVA="$(cygpath -m "$CF_RUNTIME_DIR")"; fi
mkdir -p "$CF_RUNTIME_DIR/temp" "$CF_RUNTIME_DIR/home" "$CF_RUNTIME_DIR/maven-home" \
  "$CF_RUNTIME_DIR/maven-repository" "$CF_RUNTIME_DIR/npm-cache" "$CF_RUNTIME_DIR/embedded-pg"
export TEMP="$CF_RUNTIME_JAVA/temp" TMP="$CF_RUNTIME_JAVA/temp"
export MAVEN_USER_HOME="$CF_RUNTIME_DIR/maven-home"
export MAVEN_OPTS="${MAVEN_OPTS:-} -Dmaven.repo.local=$CF_RUNTIME_JAVA/maven-repository"
export npm_config_cache="$CF_RUNTIME_JAVA/npm-cache"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djava.io.tmpdir=$CF_RUNTIME_JAVA/temp -Duser.home=$CF_RUNTIME_JAVA/home -Dot.epg.cache-dir=$CF_RUNTIME_JAVA/embedded-pg -Dot.epg.working-dir=$CF_RUNTIME_JAVA/temp"
if [ -x "$CF_RUNTIME_DIR/apache-maven-3.9.11/bin/mvn" ]; then
  CF_MAVEN_EXECUTABLE="$CF_RUNTIME_DIR/apache-maven-3.9.11/bin/mvn"
else
  CF_MAVEN_EXECUTABLE=./services/platform/mvnw
fi
export CF_MAVEN_EXECUTABLE
if ! "$CF_PYTHON_CMD" scripts/check_m1_personal.py --preview --preflight "$@"; then
  echo '预览端口被占用。请回到旧预览终端按 Ctrl+C，确认其关闭后再启动。' >&2
  exit 1
fi
if [ ! -d apps/web/node_modules ]; then (cd apps/web && npm ci); fi
CF_MAVEN_ARGS=()
if [ "${CF_MAVEN_OFFLINE:-0}" = 1 ]; then CF_MAVEN_ARGS=(-o); fi
if ! "$CF_MAVEN_EXECUTABLE" -B "${CF_MAVEN_ARGS[@]}" -f services/seats/pom.xml -DskipTests package; then
  echo '打包失败；若日志包含 Unable to rename，请先关闭仍在运行的旧 M1 预览进程。' >&2
  exit 1
fi
bash scripts/prepare-m1-http.sh "${CF_MAVEN_ARGS[@]}"
exec "$CF_PYTHON_CMD" scripts/check_m1_personal.py --preview "$@"
