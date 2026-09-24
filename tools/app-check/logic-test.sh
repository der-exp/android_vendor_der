#!/bin/sh
# Стенд логики приложения splify2 (apps/Splify2/src/com/der/splify2/logic) — одной командой.
#
# Что делает:
#   1. собирает хостовый движок steer с -DSTEER_ANDROID (каналы from:self/uid:N принимает только
#      эта сборка) и -DSTEER_EXTENDED с заглушками туннеля (tests/vless-stub.c дерева steer):
#      спеку с kind: vless базовая сборка отвергает парсером, а проверять её надо;
#   2. собирает эталон счёта узлов подписки из sub.c движка (tests/logic/subcount.c);
#   3. компилирует ВСЮ логику против android.jar — это ловит вызовы org.json, которых нет в
#      Android (на JVM стенд идёт с настоящей org.json, где методов больше);
#   4. компилирует логику без Background.kt (ему нужен Android) вместе со стендом и гоняет его
#      на JVM против настоящего движка (ctl-serve + apply --dry-run).
#
# Пути — переменными с умолчаниями этой машины: STEER_DIR (дерево steer), LISTS_DIR
# (splify2-lists), KOTLINC, ANDROID_JAR, JSON_JAR, OUT.
set -eu
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
APP="$HERE/apps/Splify2"
STEER_DIR="${STEER_DIR:-/root/splicicd/steer}"
LISTS_DIR="${LISTS_DIR:-/root/splicicd/splify2-lists}"
KOTLINC="${KOTLINC:-/root/kotlinc/bin/kotlinc}"
ANDROID_JAR="${ANDROID_JAR:-/root/android-sdk/platforms/android-36.1/android.jar}"
JSON_JAR="${JSON_JAR:-/root/android-sdk/extra-jars/json-20250517.jar}"
OUT="${OUT:-/root/wt/out-logic}"
CC="${CC:-cc}"
mkdir -p "$OUT"

SRC="src/steer.c src/spec.c src/dnsd.c src/failover.c src/aggregate.c src/obfs.c src/cli.c src/srs.c src/puff.c src/hwid.c src/ctl.c"
echo "logic-test: движок (android, extended с заглушками туннеля)"
(cd "$STEER_DIR" && $CC -O2 -w -DSTEER_VERSION='"logic-test"' -DSTEER_ANDROID -DSTEER_EXTENDED \
    -o "$OUT/steer-android-ext" $SRC tests/vless-stub.c)

echo "logic-test: эталон счёта узлов подписки"
$CC -O1 -w -I"$STEER_DIR/src/ext" -o "$OUT/subcount" "$APP/tests/logic/subcount.c"

LOGIC="$APP/src/com/der/splify2/logic"
echo "logic-test: компиляция против android.jar"
rm -rf "$OUT/cls-android"
"$KOTLINC" "$LOGIC"/*.kt -cp "$ANDROID_JAR" -d "$OUT/cls-android" -jvm-target 17 -nowarn 2>&1 | grep -v '^warning:' || true
[ -f "$OUT/cls-android/com/der/splify2/logic/Dispatcher.class" ] || { echo "logic-test: логика не собралась против android.jar"; exit 1; }

echo "logic-test: компиляция стенда"
rm -rf "$OUT/logic-test.jar"
JVM_SRC="$(ls "$LOGIC"/*.kt | grep -v '/Background.kt$')"
# shellcheck disable=SC2086
"$KOTLINC" $JVM_SRC "$APP"/tests/logic/*.kt -cp "$JSON_JAR" -include-runtime -d "$OUT/logic-test.jar" -jvm-target 17 -nowarn 2>&1 | grep -v '^warning:' || true
[ -f "$OUT/logic-test.jar" ] || { echo "logic-test: стенд не собрался"; exit 1; }

WORK="$(mktemp -d /tmp/logic-test.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT INT TERM
echo "logic-test: прогон"
STEER="$OUT/steer-android-ext" STEER_SRC="$STEER_DIR" LISTS_JSON="$LISTS_DIR/lists.json" \
SUBCOUNT="$OUT/subcount" WORK="$WORK" \
    java -cp "$OUT/logic-test.jar:$JSON_JAR" LogicTestKt
