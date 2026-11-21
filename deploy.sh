#!/usr/bin/env bash
# Copy the built jar to the proxy host without ever truncating the file the running
# JVM has open: scp to a side file, then rename it over the old one. A rename only
# swaps the directory entry, so the process keeps reading the inode it opened and a
# class that is loaded later still resolves - a plain scp over the live jar makes
# every class not yet loaded fail with NoClassDefFoundError.
#
# The host and the plugin directory are deployment details, not source: set ZP_HOST /
# ZP_PLUGINS_DIR in the environment, or put them in deploy.local.sh (git-ignored) next
# to this script:
#
#   ZP_HOST=my-proxy
#   ZP_PLUGINS_DIR=/path/to/proxy/plugins
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONF="${ZP_DEPLOY_CONF:-$HERE/deploy.local.sh}"
# shellcheck disable=SC1090
[ -f "$CONF" ] && . "$CONF"

HOST="${ZP_HOST:-}"
DIR="${ZP_PLUGINS_DIR:-plugins}"
# The jar name carries the plugin version, so it is read from the build instead of being written twice: a version bump
# used to leave this script looking for the previous jar and failing with "build the plugin first".
VERSION="$(sed -n 's/^plugin_version=//p' "$HERE/gradle.properties" | tr -d '\r')"
NAME="ZenithProxyAutoEnchant-${VERSION}.jar"
JAR="build/libs/$NAME"

if [ -z "$HOST" ]; then
    echo "no deploy target: set ZP_HOST or create $HERE/deploy.local.sh" >&2
    exit 2
fi
[ -f "$JAR" ] || { echo "build the plugin first: ./gradlew build" >&2; exit 2; }

LOCAL_MD5="$(md5sum "$JAR" | cut -d' ' -f1)"

scp -q "$JAR" "$HOST:$DIR/.$NAME.new"
REMOTE_MD5="$(ssh "$HOST" "md5sum '$DIR/.$NAME.new'" | cut -d' ' -f1)"
if [ "$LOCAL_MD5" != "$REMOTE_MD5" ]; then
    echo "upload mismatch: local $LOCAL_MD5 remote $REMOTE_MD5" >&2
    exit 1
fi
ssh "$HOST" "mv -f '$DIR/.$NAME.new' '$DIR/$NAME'"
echo "deployed $NAME $LOCAL_MD5 (restart to load it)"
