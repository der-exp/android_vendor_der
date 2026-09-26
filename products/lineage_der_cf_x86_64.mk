# Cuttlefish x86_64 + vendor/der. См. AndroidProducts.mk рядом.
#
# По образцу vendor/lineage/build/target/product/lineage_cf_phone_x86_64.mk: имя обязано
# начинаться с lineage_ — по нему envsetup ставит LINEAGE_BUILD, а без него не подключаются
# BoardConfigLineage.mk и переменные, которых ждут модули vendor/lineage/build/soong.
# TARGET_NO_KERNEL_OVERRIDE — ядро и модули берутся из TARGET_KERNEL_PATH, SYSTEM_DLKM_SRC и
# KERNEL_MODULES_PATH доски Cuttlefish, а не собираются Lineage из исходников.
$(call inherit-product, device/google/cuttlefish/vsoc_x86_64/phone/aosp_cf.mk)

# Разблокировка по лицу DerpFest (ParanoidSense) — только с библиотеками arm64; на x86_64 её
# зависимостей нет. Переключатель читается в vendor/lineage/config/derpfest.mk через ?=, поэтому
# задаётся до подключения конфигурации Lineage.
TARGET_FACE_UNLOCK_SUPPORTED := false
# Модуль всё равно разбирается, и его обязательные библиотеки (только arm64) сверяются даже без
# установки — каталог исключается из разбора Soong. Кроме interfaces: на AIDL
# vendor.aospa.biometrics.face оттуда опирается services.core. Побеждает самый длинный
# префикс (blueprint SourceRootDirAllowed), поэтому вложенный путь снова включается.
PRODUCT_SOURCE_ROOT_DIRS += -packages/apps/FaceUnlock
PRODUCT_SOURCE_ROOT_DIRS += packages/apps/FaceUnlock/interfaces

include vendor/lineage/build/target/product/lineage_generic_target.mk

$(call inherit-product, vendor/der/config/der.mk)

TARGET_DISABLE_EPPE := true
TARGET_NO_KERNEL_OVERRIDE := true

# Cuttlefish запрещает Android.mk целиком, а оверлеи DerpFest (значки, темы — зависимости
# frameworks-base-overlays) описаны именно там.
PRODUCT_IGNORE_ALL_ANDROIDMK := false
PRODUCT_SOONG_ONLY := false

PRODUCT_NAME := lineage_der_cf_x86_64
PRODUCT_MODEL := Cuttlefish x86_64 phone (der-exp)

# DerpFest кладёт в system библиотеку, которой нет в списке generic_system.mk; на телефоне
# такого требования нет, а Cuttlefish его проверяет. Разрешено поимённо.
PRODUCT_ARTIFACT_PATH_REQUIREMENT_ALLOWED_LIST += \
    system/lib/libtensorflowlite_jni.so
