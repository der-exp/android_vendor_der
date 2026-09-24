#!/bin/sh
# Проверочная сборка приложения splify2 (apps/Splify2) — без дерева платформы.
#
# Что это повторяет. В дереве APK собирает Soong по apps/Splify2/Android.bp: kotlinc над
# src/**/*.kt против полного API платформы (platform_apis), aapt2 над res/ и assets/, R8 с
# proguard.flags и правилами, которые aapt2 выводит из манифеста, kotlin-stdlib статически в
# приложении, подпись платформенным ключом. Здесь — те же шаги теми же инструментами из SDK:
#   - kotlinc против android.jar SDK (android-36.1) и крошечного stub-jar скрытого API
#     (tools/app-check/stubs: android.os.SystemProperties) — stub нужен только компилятору и в
#     APK не попадает, на устройстве класс даёт платформа;
#   - aapt2 compile/link из build-tools 36.1.0, minSdk и targetSdk 36;
#   - R8 из того же build-tools (d8.jar) — с нашими правилами; так ловится, что методы моста
#     (@JavascriptInterface) и компоненты манифеста переживают сжатие;
#   - apksigner тестовым ключом (платформенного ключа вне дерева нет; на домен splify2_app это
#     не влияет — его вне прошивки всё равно не проверить).
#
# Чего это НЕ повторяет: скрытый API здесь — только то, что есть в stub (Soong видит всю
# платформу); глобальные правила R8 платформы (build/make/core/proguard*.flags); dexpreopt;
# сверку privapp-permissions при загрузке. Логика (src/com/der/splify2/logic) — настоящая, та же,
# что гоняет стенд tools/app-check/logic-test.sh; экран — собранный assets/web (web/, npm run build).
#
# Входы (у всех умолчания для этой машины):
#   SDK      Android SDK с platforms/android-36.1 и build-tools/36.1.0
#   KOTLINC  kotlinc (2.x)
#   OUT      каталог сборки (по умолчанию $TMPDIR/der-app-check)
#   KEYSTORE тестовый ключ; создаётся, если его нет
#   STRICT=1 предупреждения kotlinc — ошибка
# Запуск: tools/app-check/build.sh      На выходе: $OUT/Splify2.apk и сводка aapt2 dump badging.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
DER=$(cd "$here/../.." && pwd)
APP=$DER/apps/Splify2
SDK=${SDK:-/root/android-sdk}
KOTLINC=${KOTLINC:-/root/kotlinc/bin/kotlinc}
OUT=${OUT:-${TMPDIR:-/tmp}/der-app-check}
KEYSTORE=${KEYSTORE:-$OUT/../der-app-check-testkey.jks}
STRICT=${STRICT:-0}

PLATFORM=$SDK/platforms/android-36.1
BT=$SDK/build-tools/36.1.0
ANDROID_JAR=$PLATFORM/android.jar
KLIB=$(cd "$(dirname "$KOTLINC")/../lib" && pwd)
STDLIB=$KLIB/kotlin-stdlib.jar
MIN_SDK=36
VERSION_CODE=36
VERSION_NAME=16.2

for f in "$ANDROID_JAR" "$BT/aapt2" "$BT/lib/d8.jar" "$BT/zipalign" "$BT/apksigner" "$KOTLINC" "$STDLIB"; do
	[ -e "$f" ] || { echo "нет $f" >&2; exit 2; }
done

rm -rf "$OUT"
mkdir -p "$OUT/stubs" "$OUT/gen" "$OUT/rclasses" "$OUT/classes" "$OUT/dex"

step() { printf '== %s\n' "$*"; }

[ -f "$APP/src/com/der/splify2/logic/Dispatcher.kt" ] || { echo "нет логики src/com/der/splify2/logic" >&2; exit 2; }
[ -f "$APP/assets/web/index.html" ] || { echo "нет собранного экрана assets/web (cd apps/Splify2/web && npm run build)" >&2; exit 2; }

step "stub скрытого API"
javac -nowarn --release 17 -d "$OUT/stubs" $(find "$here/stubs" -name '*.java')
(cd "$OUT/stubs" && jar cf "$OUT/hidden-stubs.jar" .)

step "aapt2 compile"
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res.zip"

step "aapt2 link"
"$BT/aapt2" link -I "$ANDROID_JAR" \
	--manifest "$APP/AndroidManifest.xml" \
	--min-sdk-version $MIN_SDK --target-sdk-version $MIN_SDK \
	--version-code $VERSION_CODE --version-name $VERSION_NAME \
	--java "$OUT/gen" --proguard "$OUT/aapt-rules.pro" \
	-A "$APP/assets" -o "$OUT/base.apk" "$OUT/res.zip"

step "javac R"
javac -nowarn --release 17 -cp "$ANDROID_JAR" -d "$OUT/rclasses" $(find "$OUT/gen" -name '*.java')

step "kotlinc"
# -no-jdk: классы java.* — из android.jar, как на устройстве, а не из JDK машины;
# -no-stdlib + явный kotlin-stdlib.jar: компилируем против той же stdlib, что кладём в APK;
# язык 2.2 — не новее Kotlin в дереве Android 16 (prebuilts/kotlin), чтобы то, что собралось
# здесь, собралось и там, и чтобы метаданные Kotlin были по зубам R8 из SDK.
# shellcheck disable=SC2046
"$KOTLINC" -no-jdk -no-stdlib -no-reflect -jvm-target 17 -language-version 2.2 -api-version 2.2 \
	-cp "$ANDROID_JAR:$OUT/hidden-stubs.jar:$STDLIB:$OUT/rclasses" \
	-d "$OUT/classes" \
	$(find "$APP/src" -name '*.kt') \
	2>&1 | tee "$OUT/kotlinc.log"
if grep -q '^error:\|: error:' "$OUT/kotlinc.log"; then echo "kotlinc: ошибки" >&2; exit 1; fi
if grep -q 'warning:' "$OUT/kotlinc.log"; then
	echo "kotlinc: есть предупреждения ($(grep -c 'warning:' "$OUT/kotlinc.log"))"
	[ "$STRICT" = 1 ] && exit 1
fi
cp -r "$OUT/rclasses/." "$OUT/classes/"
(cd "$OUT/classes" && jar cf "$OUT/classes.jar" .)

step "R8"
# Как у Soong: приложение и kotlin-stdlib — программа, android.jar — библиотека. stub
# скрытого API — тоже библиотека: R8 должен знать, что класс есть, но в dex его не класть.
java -cp "$BT/lib/d8.jar" com.android.tools.r8.R8 --release --min-api $MIN_SDK \
	--lib "$ANDROID_JAR" --lib "$OUT/hidden-stubs.jar" \
	--pg-conf "$APP/proguard.flags" --pg-conf "$OUT/aapt-rules.pro" \
	--pg-map-output "$OUT/proguard.map" \
	--output "$OUT/dex" "$OUT/classes.jar" "$STDLIB" 2>&1 | tee "$OUT/r8.log"

step "упаковка"
# classes.dex — без сжатия: у привилегированного приложения Soong так и делает
# (uncompress_dex), ART тогда исполняет dex прямо из APK. resources.arsc aapt2 уже положил
# несжатым; zipalign выравнивает то и другое.
python3 - "$OUT/base.apk" "$OUT/dex" "$OUT/unsigned.apk" <<'EOF'
import os, shutil, sys, zipfile
base, dexdir, out = sys.argv[1:]
shutil.copy(base, out)
with zipfile.ZipFile(out, "a") as z:
    for name in sorted(os.listdir(dexdir)):
        if name.endswith(".dex"):
            z.write(os.path.join(dexdir, name), name, compress_type=zipfile.ZIP_STORED)
EOF
"$BT/zipalign" -P 16 -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

step "подпись тестовым ключом"
if [ ! -f "$KEYSTORE" ]; then
	keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
		-alias test -keyalg RSA -keysize 2048 -validity 10000 \
		-dname "CN=der-app-check test key" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
	--ks-key-alias test --out "$OUT/Splify2.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/Splify2.apk"

step "проверки"
"$BT/aapt2" dump badging "$OUT/Splify2.apk" | tee "$OUT/badging.txt"
fail=0
check() { grep -q "$1" "$OUT/badging.txt" || { echo "НЕТ в badging: $1" >&2; fail=1; }; }
check "package: name='com.der.splify2'"
check "minSdkVersion:'$MIN_SDK'"
check "targetSdkVersion:'$MIN_SDK'"
check "uses-permission: name='android.permission.WRITE_SECURE_SETTINGS'"
check "uses-permission: name='android.permission.QUERY_ALL_PACKAGES'"
check "uses-permission: name='android.permission.INTERNET'"
check "launchable-activity: name='com.der.splify2.MainActivity'"
# Ничего сверх задуманного: список разрешений — ровно этот.
got=$(grep "^uses-permission:" "$OUT/badging.txt" | sed "s/.*name='\([^']*\)'.*/\1/" | sort | tr '\n' ' ')
want="android.permission.ACCESS_NETWORK_STATE android.permission.INTERNET android.permission.QUERY_ALL_PACKAGES android.permission.RECEIVE_BOOT_COMPLETED android.permission.WRITE_SECURE_SETTINGS "
[ "$got" = "$want" ] || { echo "разрешения не те: $got" >&2; fail=1; }

# dex: мост пережил R8 под своим именем, stub скрытого API в dex не попал.
"$BT/dexdump" "$OUT/dex/classes.dex" > "$OUT/dexdump.txt"
grep -q "name *: 'call'" "$OUT/dexdump.txt" || { echo "в dex нет метода моста call" >&2; fail=1; }
# ...и с аннотацией: без неё WebView метод странице не отдаст.
"$BT/dexdump" -a "$OUT/dex/classes.dex" > "$OUT/dexdump-a.txt" 2>/dev/null
grep -q "Landroid/webkit/JavascriptInterface;" "$OUT/dexdump-a.txt" ||
	{ echo "в dex нет аннотации @JavascriptInterface" >&2; fail=1; }
if grep -q "Class descriptor *: 'Landroid/os/SystemProperties;'" "$OUT/dexdump.txt"; then
	echo "stub SystemProperties попал в dex" >&2; fail=1
fi
for c in MainActivity UpdateJobService Splify2App; do
	grep -q "Class descriptor *: 'Lcom/der/splify2/$c;'" "$OUT/dexdump.txt" ||
		{ echo "в dex нет com.der.splify2.$c" >&2; fail=1; }
done

# Экран внутри APK: страница и бандл, на которые она ссылается (WebAssets отдаёт их из assets/).
python3 -c 'import sys, zipfile; print("\n".join(zipfile.ZipFile(sys.argv[1]).namelist()))' "$OUT/Splify2.apk" > "$OUT/apk-list.txt"
for f in $(cd "$APP/assets" && find web -type f | sort); do
	grep -qx "assets/$f" "$OUT/apk-list.txt" || { echo "в APK нет assets/$f" >&2; fail=1; }
done
for f in $(grep -o '\(src\|href\)="[^"]*"' "$APP/assets/web/index.html" | sed 's/^[a-z]*="//; s/"$//; s#^\./##; s#^/##'); do
	case $f in http*|data:*) continue ;; esac
	grep -qx "assets/web/$f" "$OUT/apk-list.txt" || { echo "страница ссылается на $f, а в APK его нет" >&2; fail=1; }
done
# В APK — только приложение: ни исходников экрана, ни стенда, ни карт исходников.
if grep -Eq '^(web|tests|stub-logic|src)/|\.(map|kt|tsx?)$' "$OUT/apk-list.txt"; then echo "в APK лишнее" >&2; fail=1; fi

size=$(wc -c < "$OUT/Splify2.apk")
echo "APK: $OUT/Splify2.apk ($size байт)"
[ $fail = 0 ] && echo ok || exit 1
