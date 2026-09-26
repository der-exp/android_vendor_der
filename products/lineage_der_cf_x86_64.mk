# Cuttlefish x86_64 + vendor/der. См. AndroidProducts.mk рядом.
#
# По образцу vendor/lineage/build/target/product/lineage_cf_phone_x86_64.mk: имя обязано
# начинаться с lineage_ — по нему envsetup ставит LINEAGE_BUILD, а без него не подключаются
# BoardConfigLineage.mk и переменные, которых ждут модули vendor/lineage/build/soong.
# TARGET_NO_KERNEL_OVERRIDE — ядро и модули берутся из TARGET_KERNEL_PATH, SYSTEM_DLKM_SRC и
# KERNEL_MODULES_PATH доски Cuttlefish, а не собираются Lineage из исходников.
$(call inherit-product, device/google/cuttlefish/vsoc_x86_64/phone/aosp_cf.mk)

include vendor/lineage/build/target/product/lineage_generic_target.mk

$(call inherit-product, vendor/der/config/der.mk)

TARGET_DISABLE_EPPE := true
TARGET_NO_KERNEL_OVERRIDE := true

PRODUCT_NAME := lineage_der_cf_x86_64
PRODUCT_MODEL := Cuttlefish x86_64 phone (der-exp)
