#!/bin/sh
# Компиляция политики SELinux vendor/der против настоящей system/sepolicy — без полного дерева.
#
# Что это повторяет. В дереве платформы все neverallow проверяет модуль sepolicy_neverallows
# (system/sepolicy/Android.bp): m4 над public+private платформы, system_ext, product и vendor,
# затем checkpolicy над получившимся policy.conf. Здесь собирается тот же policy.conf из тех
# же каталогов в том же порядке (policyConfOrder в build/soong/policy.go) и с теми же -D, что
# передаёт Soong, и компилируется checkpolicy из external/selinux той же версии платформы.
# Кроме neverallow, отдельно компилируется половина system_ext (plat + system_ext → CIL →
# secilc вместе с plat), как это делает сборка split-политики: так ловятся ссылки на типы,
# которых нет в публичной политике.
#
# Чего это НЕ повторяет: политику производителя устройства (device/qcom, sdm845-common) —
# её нет без полного дерева; treble_sepolicy_tests (сравнение с прошлыми версиями API);
# компиляцию file_contexts/property_contexts/seapp_contexts инструментами платформы (здесь
# они проверяются checkfc-подобной проверкой меток — метка должна быть типом из политики).
#
# Почему свои checkpolicy/secilc, а не пакеты Ubuntu: в политике Android 16 есть то, чего не
# знает checkpolicy 3.5 из Ubuntu 24.04 — первым отказом идёт «policycap functionfs_seclabel»,
# дальше «allowxperm … nlmsg» (nlmsg_macros), бэкпорт AOSP поверх libsepol 3.7.
#
# Входы (переменные окружения, у всех есть умолчания для этой машины):
#   SEPOLICY  system/sepolicy (LineageOS/android_system_sepolicy, lineage-23.2)
#   LINEAGE   device/lineage/sepolicy (LineageOS/android_device_lineage_sepolicy, lineage-23.2)
#   SELINUX   external/selinux (AOSP, android-16.0.0_r4) с собранными checkpolicy и secilc
#   DER       vendor/der (по умолчанию — корень этого репозитория)
#   OUT       каталог для policy.conf и логов
# Запуск: tools/sepolicy-check/check.sh [user|userdebug ...]   (по умолчанию оба)
# Код выхода 0 — всё скомпилировалось, neverallow не нарушены.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
DER=${DER:-$(cd "$here/../.." && pwd)}
SEPOLICY=${SEPOLICY:-/root/der-exp/src/sepolicy-lineage}
LINEAGE=${LINEAGE:-/root/der-exp/src/device-lineage-sepolicy}
SELINUX=${SELINUX:-/root/der-exp/src/external-selinux}
OUT=${OUT:-${TMPDIR:-/tmp}/der-sepolicy-check}
CHECKPOLICY=$SELINUX/checkpolicy/checkpolicy
SECILC=$SELINUX/secilc/secilc

[ -x "$CHECKPOLICY" ] && [ -x "$SECILC" ] || {
	echo "нет $CHECKPOLICY или $SECILC — собрать:" >&2
	echo "  make -C $SELINUX/libsepol/src libsepol.a CFLAGS='-O2 -Wno-unused-function'" >&2
	echo "  make -C $SELINUX/checkpolicy checkpolicy LIBSEPOLA=\$PWD/libsepol/src/libsepol.a" >&2
	echo "  make -C $SELINUX/secilc secilc LIBSEPOLA=... LDLIBS=.../libsepol.a" >&2
	exit 2
}
mkdir -p "$OUT"

# Порядок файлов внутри policy.conf — как policyConfOrder в build/soong/policy.go: checkpolicy
# требует, чтобы классы шли до векторов доступа, типы — до ролей и т.д. Файлы с одинаковым
# местом в порядке идут в порядке каталогов (сортировка там стабильная). TE — это позиция
# «attributes|*.te».
ORDER="flagging_macros security_classes initial_sids access_vectors global_macros
neverallow_macros mls_macros mls_decl mls policy_capabilities te_macros ioctl_defines
ioctl_macros nlmsg_defines nlmsg_macros TE roles_decl roles users initial_sid_contexts
fs_use genfs_contexts port_contexts"

# collect DIR... — печатает файлы политики из каталогов в порядке policy.conf.
collect() {
	# flagging_macros лежит не в public/private, а в system/sepolicy/flagging и входит в
	# srcs каждого se_policy_conf первым (se_policy_conf_flags_defaults).
	echo "$SEPOLICY/flagging/flagging_macros"
	for pat in $ORDER; do
		for d in "$@"; do
			[ -d "$d" ] || continue
			if [ "$pat" = TE ]; then
				# attributes и *.te — одна позиция порядка, как в policy.go.
				[ -f "$d/attributes" ] && echo "$d/attributes"
				for f in "$d"/*.te; do [ -f "$f" ] && echo "$f"; done
			elif [ -f "$d/$pat" ]; then
				echo "$d/$pat"
			fi
		done
	done
	return 0
}

# Каталоги по разделам — как se_build_files: plat — system/sepolicy/{public,private},
# system_ext — то, что добавляют SYSTEM_EXT_*_SEPOLICY_DIRS (Lineage common + наш vendor/der),
# plat_vendor — system/sepolicy/vendor, vendor — BOARD_VENDOR_SEPOLICY_DIRS Lineage (у
# beryllium свой раздел vendor, поэтому ветка dynamic+vendor в common/sepolicy.mk).
PLAT_PUB="$SEPOLICY/public"
PLAT_PRIV="$SEPOLICY/private"
SE_PUB="$LINEAGE/common/public $DER/sepolicy/public"
SE_PRIV="$LINEAGE/common/private $DER/sepolicy/private"
VENDOR="$SEPOLICY/vendor $LINEAGE/common/dynamic $LINEAGE/common/vendor"

# Флаги выпуска (se_flags aosp_selinux_flags в system/sepolicy/flagging/Android.bp), которые
# Soong передаёт как -D target_flag_ИМЯ=значение. Настоящие значения живут в конфигурации
# выпуска (build/release), которой без дерева нет. Все включены: в выпуске android16 AVF,
# ranging и unlocked storage включены, а часть правил platform-политики ссылается на типы из
# этих блоков без условия — с выключенным RELEASE_AVF_ENABLE_EARLY_VM policy.conf не
# компилируется вовсе («unknown type early_virtmgr»), то есть сборка так не настроена.
FLAGS=${FLAGS:-"RELEASE_AVF_SUPPORT_CUSTOM_VM_WITH_PARAVIRTUALIZED_DEVICES RELEASE_AVF_ENABLE_EARLY_VM
RELEASE_AVF_ENABLE_DEVICE_ASSIGNMENT RELEASE_AVF_ENABLE_LLPVM_CHANGES RELEASE_AVF_ENABLE_NETWORK
RELEASE_AVF_ENABLE_MICROFUCHSIA RELEASE_AVF_ENABLE_VM_TO_TEE_SERVICES_ALLOWLIST
RELEASE_AVF_ENABLE_WIDEVINE_PVM RELEASE_RANGING_STACK RELEASE_READ_FROM_NEW_STORAGE
RELEASE_SUPERVISION_SERVICE RELEASE_HARDWARE_BLUETOOTH_RANGING_SERVICE RELEASE_UNLOCKED_STORAGE_API
RELEASE_BLUETOOTH_SOCKET_SERVICE RELEASE_SEPOLICY_RESTRICT_KERNEL_KEYRING_SEARCH
RELEASE_TELEPHONY_MODULE"}
flagdefs() { for f in $FLAGS; do printf -- '-D target_flag_%s=true\n' "$f"; done; }

# m4 VARIANT OUTFILE DIR... — policy.conf так, как его пишет transformPolicyToConf.
m4conf() {
	variant=$1 out=$2; shift 2
	# shellcheck disable=SC2046
	m4 --fatal-warnings -s \
		-D mls_num_sens=1 -D mls_num_cats=1024 \
		-D target_arch=arm64 -D target_with_asan=false \
		-D target_with_dexpreopt=true -D target_with_native_coverage=false \
		-D target_build_variant="$variant" -D target_full_treble=true \
		-D target_compatible_property=true -D target_treble_sysprop_neverallow=true \
		-D target_enforce_sysprop_owner=true -D target_exclude_build_test=false \
		-D target_requires_insecure_execmem_for_swiftshader=false \
		-D target_enforce_debugfs_restriction=true -D target_recovery=false \
		-D target_board_api_level=202504 \
		$(flagdefs) \
		$(collect "$@") > "$out"
}

# Метки из *_contexts: каждая u:object_r:ТИП:s0 должна быть типом скомпилированной политики.
# Это минимум того, что делают checkfc и property_info_checker; синтаксис регулярных
# выражений и пересечения с чужими строками они проверяют сверх этого.
check_labels() {
	conf=$1 bad=0
	for f in "$DER"/sepolicy/*/*_contexts; do
		[ -f "$f" ] || continue
		for t in $(grep -v '^[[:space:]]*#' "$f" | grep -o 'u:object_r:[a-z0-9_]*:s0' | cut -d: -f3 | sort -u); do
			grep -Eq "^[[:space:]]*type $t[,; ]" "$conf" || { echo "  $f: тип $t не объявлен"; bad=1; }
		done
		# seapp_contexts: domain= и type= тоже должны существовать.
		case $f in *seapp_contexts)
			for t in $(grep -v '^[[:space:]]*#' "$f" | grep -oE '(domain|type)=[a-z0-9_]+' | cut -d= -f2 | sort -u); do
				grep -Eq "^[[:space:]]*type $t[,; ]" "$conf" || { echo "  $f: тип $t не объявлен"; bad=1; }
			done;;
		esac
	done
	return $bad
}

# libsepolwrap — C++-обёртка над libsepol, через которую sepolicy_tests.py читает двоичную
# политику (system/sepolicy/tests/sepol_wrap.cpp). В дереве её собирает Soong; здесь — g++
# против того же libsepol.a. Заголовки android-base обёртка подключает, но ничего из них не
# зовёт, поэтому хватает пустых.
sepolwrap() {
	so=$OUT/libsepolwrap.so
	[ -f "$so" ] && { echo "$so"; return 0; }
	mkdir -p "$OUT/sw/android-base"
	: > "$OUT/sw/android-base/file.h"; : > "$OUT/sw/android-base/strings.h"
	g++ -O2 -shared -fPIC -include memory -o "$so" "$SEPOLICY/tests/sepol_wrap.cpp" \
		-I"$SEPOLICY/tests/include" -I"$OUT/sw" -I"$SELINUX/libsepol/include" \
		"$SELINUX/libsepol/src/libsepol.a" >&2 && echo "$so"
}

# file_contexts в сборке тоже проходят m4 (selinux_contexts.go) — в них бывают макросы
# флагов. Флаги и уровень API те же, что у policy.conf.
fcm4() {
	# shellcheck disable=SC2046
	m4 --fatal-warnings -s -D target_board_api_level=202504 $(flagdefs) \
		"$SEPOLICY/flagging/flagging_macros" "$1" > "$2"
}

rc=0
for variant in ${*:-user userdebug}; do
	echo "== $variant"
	d=$OUT/$variant
	mkdir -p "$d"

	# 1. Все neverallow: вся политика одним файлом (sepolicy_neverallows).
	m4conf "$variant" "$d/neverallows.conf" $PLAT_PUB $PLAT_PRIV $SE_PUB $SE_PRIV $VENDOR
	if "$CHECKPOLICY" -M -c 30 -o "$d/neverallows.bin" "$d/neverallows.conf" > "$d/neverallows.log" 2>&1; then
		echo "ok   neverallow (plat + system_ext + vendor)"
	else
		echo "FAIL neverallow — $d/neverallows.log:"; grep -v '^checkpolicy:  loading\|^checkpolicy:  policy configuration loaded\|^checkpolicy:  writing' "$d/neverallows.log" | head -40
		rc=1
	fi

	# 2. system_ext как отдельная половина split-политики: plat + system_ext → CIL, затем
	#    secilc вместе с plat_sepolicy.cil (как system_ext_sepolicy.cil в сборке).
	m4conf "$variant" "$d/plat.conf" $PLAT_PUB $PLAT_PRIV
	m4conf "$variant" "$d/system_ext.conf" $PLAT_PUB $PLAT_PRIV $SE_PUB $SE_PRIV
	if "$CHECKPOLICY" -M -C -c 30 -o "$d/plat.cil" "$d/plat.conf" > "$d/plat.log" 2>&1 &&
	   "$CHECKPOLICY" -M -C -c 30 -o "$d/system_ext.cil" "$d/system_ext.conf" > "$d/system_ext.log" 2>&1 &&
	   "$SECILC" -m -M true -G -N -c 30 -o "$d/system_ext.bin" -f /dev/null "$d/system_ext.cil" > "$d/secilc.log" 2>&1; then
		echo "ok   system_ext (checkpolicy -C + secilc)"
	else
		echo "FAIL system_ext — логи в $d:"; cat "$d/plat.log" "$d/system_ext.log" "$d/secilc.log" 2>/dev/null | grep -iv 'loading\|loaded\|writing' | head -40
		rc=1
	fi

	# 3. Метки файлов, свойств и приложений ссылаются на существующие типы.
	if check_labels "$d/neverallows.conf"; then
		echo "ok   метки в *_contexts"
	else
		echo "FAIL метки в *_contexts (выше)"
		rc=1
	fi

	# 4. Тесты платформы над готовой политикой (sepolicy_tests.py: типы файлов /data и
	#    /system с нужными атрибутами, coredomain, свойства). Это те же проверки, что
	#    запускает сборка модулем sepolicy_tests, но над политикой без вендора устройства.
	so=$(sepolwrap) || { echo "FAIL libsepolwrap не собралась"; rc=1; continue; }
	n=0 fcargs=""
	for fc in "$SEPOLICY/private/file_contexts" "$LINEAGE/common/private/file_contexts" \
	          "$DER/sepolicy/private/file_contexts" "$SEPOLICY/vendor/file_contexts" \
	          "$LINEAGE/common/vendor/file_contexts"; do
		[ -f "$fc" ] || continue
		n=$((n + 1)); fcm4 "$fc" "$d/fc$n"; fcargs="$fcargs $d/fc$n"
	done
	if [ -f "$d/neverallows.bin" ] && (cd "$SEPOLICY/tests" && python3 -c '
import sys
so, pol, fcs = sys.argv[1], sys.argv[2], sys.argv[3:]
sys.argv = ["sepolicy_tests", "-p", pol] + [a for f in fcs for a in ("-f", f)]
import sepolicy_tests
sepolicy_tests.do_main(so)
' "$so" "$d/neverallows.bin" $fcargs) > "$d/sepolicy_tests.log" 2>&1; then
		echo "ok   sepolicy_tests"
	else
		echo "FAIL sepolicy_tests — $d/sepolicy_tests.log:"; tail -20 "$d/sepolicy_tests.log"
		rc=1
	fi
done
exit $rc
