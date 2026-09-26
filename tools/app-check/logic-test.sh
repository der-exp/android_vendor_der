#!/bin/sh
# Стенд логики приложения splify2 (apps/Splify2/src/com/der/splify2/logic) — одной командой.
#
# Что делает:
#   1. собирает хостовый движок steer под платформу android (каналы from:self/uid:N принимает
#      только она) и -DSTEER_EXTENDED с заглушками туннеля (tests/vless-stub.c дерева steer):
#      спеку с kind: vless базовая сборка отвергает парсером, а проверять её надо;
#   2. собирает эталон счёта узлов подписки из sub.c движка (tests/logic/subcount.c);
#   3. компилирует ВСЮ логику против android.jar — это ловит вызовы org.json, которых нет в
#      Android (на JVM стенд идёт с настоящей org.json, где методов больше);
#   4. компилирует логику без Background.kt (ему нужен Android) вместе со стендом и гоняет его
#      на JVM против настоящего движка (ctl-serve + apply --dry-run).
#
# Файловые команды сокета (put-file, list-files, rm-file) по умолчанию тоже настоящие:
# ctl-serve стенда запускается с --lists-dir своего каталога, и логика заливает и убирает файлы
# через тот же протокол, что на телефоне, — это и есть сквозная проверка без телефона.
# STEER_REAL_FILES=0 — для дерева steer старше этих команд: стенд делает их сам (HostEngine).
#
# Пути — переменными с умолчаниями этой машины: STEER_DIR (дерево steer), STEER_VIA_DIR
# (необязательно: дерево steer с via, если его ещё нет в STEER_DIR), LISTS_DIR
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

# Движок стенда: расширенная сборка с заглушками туннеля под платформу android. Дерево steer
# с build/sources.mk (раскладка по слоям, 1.6+) собирается своей целью diagsim — список
# исходников там единственный, и переписывать его сюда значило бы снова разойтись с ним молча;
# платформа выбирается умолчанием сборки. Дерево старше — прежним списком и -DSTEER_ANDROID.
build_engine() { # каталог-дерева выход версия
    if [ -f "$1/build/sources.mk" ]; then
        make -s -C "$1" BUILD="$2.d" \
            "DEFS=-DSTEER_VERSION='\"$3\"' -DSTEER_DEFAULT_PLATFORM=android" "$2.d/diagsim"
        cp "$2.d/diagsim" "$2"
    else
        src="src/steer.c src/spec.c src/dnsd.c src/failover.c src/aggregate.c src/obfs.c src/cli.c src/srs.c src/puff.c src/hwid.c src/ctl.c"
        # Выход kind=awg (src/awg.c) есть в дереве steer с ветки android-awg; движок старше его
        # не знает. Файл берётся, если он есть.
        [ -f "$1/src/awg.c" ] && src="$src src/awg.c"
        # shellcheck disable=SC2086
        (cd "$1" && $CC -O2 -w -DSTEER_VERSION="\"$3\"" -DSTEER_ANDROID -DSTEER_EXTENDED \
            -o "$2" $src tests/vless-stub.c)
    fi
}
echo "logic-test: движок (android, extended с заглушками туннеля)"
build_engine "$STEER_DIR" "$OUT/steer-android-ext" logic-test

# Второй движок — для спек с via, пока via не влит в дерево STEER_DIR (ветка android-via).
# STEER_VIA_DIR — дерево steer с via; не задано — via-спеки против движка стенд честно
# помечает ожидающими (AwgTest.kt, pending), а проверки модели via идут всё равно.
STEER_VIA=""
if [ -n "${STEER_VIA_DIR:-}" ]; then
    echo "logic-test: движок с via ($STEER_VIA_DIR)"
    build_engine "$STEER_VIA_DIR" "$OUT/steer-android-via" logic-test-via
    STEER_VIA="$OUT/steer-android-via"
fi

echo "logic-test: эталон счёта узлов подписки"
# sub.c и vless_proto.c: src/proto/vless в раскладке по слоям, src/ext — в прежней.
$CC -O1 -w -I"$STEER_DIR/src/proto/vless" -I"$STEER_DIR/src/ext" -o "$OUT/subcount" "$APP/tests/logic/subcount.c"

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
STEER_REAL_FILES="${STEER_REAL_FILES:-1}" STEER="$OUT/steer-android-ext" STEER_VIA="$STEER_VIA" STEER_SRC="$STEER_DIR" LISTS_JSON="$LISTS_DIR/lists.json" \
SUBCOUNT="$OUT/subcount" WORK="$WORK" \
    java -cp "$OUT/logic-test.jar:$JSON_JAR" LogicTestKt
