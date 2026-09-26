#!/bin/bash
# Ядро GKI android16-6.12 с nf_tables для движка steer — с проверкой, что договор с модулями
# производителя (KMI) не нарушен.
#
# Зачем своё ядро. В GKI Google nf_tables выключен, а модулем его не завести: nfnetlink, NAT и
# conntrack наружу не экспортированы (в vmlinux.symvers сборки 14552068 нет ни одного
# nfnetlink_* и nf_nat_*). Встроенному nf_tables экспорт не нужен, но включение опции сдвигает
# поля struct net, и контрольные суммы 1089 из 9358 экспортов расходятся с заводскими —
# модули производителя с таким ядром не грузятся. Правка 0001 ставит поле nft последним (оно
# ложится в выравнивающий хвост, размер struct net не меняется) и прячет его от
# gendwarfksyms; после неё суммы совпадают все.
#
# Что делает скрипт:
#   1. берёт с ci.android.com манифест и vmlinux.symvers заводской сборки BUILD_ID — манифест
#      закрепляет ревизии всех проектов, symvers служит эталоном сумм;
#   2. repo init/sync по этому манифесту в DIR;
#   3. накладывает 0001 на common и кладёт фрагмент конфигурации пакетом //der;
#   4. собирает kernel_aarch64_dist с --defconfig_fragment=//der:nftables_defconfig;
#   5. сверяет vmlinux.symvers с эталоном: любое расхождение — отказ.
#
# BUILD_ID — номер сборки Google из строки версии заводского ядра (…-ab14552068-4k у OnePlus
# 15T, ПО 16.0.10.500): ядро должно быть того же коммита, что стоит на телефоне, иначе
# модули производителя разойдутся с ним и без нашей правки.
#
#   kernel/gki-android16-6.12/build.sh [DIR]      DIR по умолчанию ~/gki
#   BUILD_ID=14552068 — по умолчанию
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
DIR="${1:-$HOME/gki}"
BUILD_ID="${BUILD_ID:-14552068}"
CI="https://ci.android.com/builds/submitted/$BUILD_ID/kernel_aarch64/latest/raw"

mkdir -p "$DIR/ref"
cd "$DIR"
for f in "manifest_$BUILD_ID.xml" vmlinux.symvers; do
    [ -s "ref/$f" ] || curl -fsSL -o "ref/$f" "$CI/$f"
done

if [ ! -d .repo ]; then
    repo init -q -u https://android.googlesource.com/kernel/manifest -b common-android16-6.12
fi
cp "ref/manifest_$BUILD_ID.xml" ".repo/manifests/ab$BUILD_ID.xml"
repo init -q -m "ab$BUILD_ID.xml"
repo sync -c -j8 --no-tags

# Правка — одним коммитом поверх ревизии манифеста; повторный запуск её не удваивает.
if ! git -C common log -1 --format=%s | grep -q "keep struct net KMI with nf_tables"; then
    git -C common am -q "$HERE"/0001-*.patch
fi
mkdir -p der
[ "$HERE" -ef der ] || cp "$HERE/BUILD.bazel" "$HERE/nftables_defconfig" der/

tools/bazel run --defconfig_fragment=//der:nftables_defconfig //common:kernel_aarch64_dist \
    -- --destdir="$DIR/out-der"

# Сверка сумм: имя и сумма каждого экспорта — как у Google, и экспортов не больше и не меньше.
diffs=$(diff <(awk '{print $1, $2}' ref/vmlinux.symvers | sort) \
             <(awk '{print $1, $2}' out-der/vmlinux.symvers | sort) | grep -c '^[<>]' || true)
total=$(wc -l < ref/vmlinux.symvers)
if [ "$diffs" != 0 ]; then
    echo "build.sh: суммы экспортов расходятся с заводскими ($diffs строк из $total) — модули производителя с этим ядром не загрузятся" >&2
    exit 1
fi
grep -q '^CONFIG_NF_TABLES=y' out-der/kernel_aarch64_dot_config
echo "build.sh: ok — $total экспортов, все суммы как у сборки $BUILD_ID; nf_tables встроен"
echo "build.sh: образ $DIR/out-der/boot.img, ядро $DIR/out-der/Image"
