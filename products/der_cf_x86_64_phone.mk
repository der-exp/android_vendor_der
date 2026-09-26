# Cuttlefish x86_64 (только 64 бита) + vendor/der. См. AndroidProducts.mk рядом.
$(call inherit-product, device/google/cuttlefish/vsoc_x86_64_only/phone/aosp_cf.mk)
$(call inherit-product, vendor/der/config/der.mk)

PRODUCT_NAME := der_cf_x86_64_phone
PRODUCT_DEVICE := vsoc_x86_64_only
PRODUCT_MODEL := Cuttlefish x86_64 phone (der-exp)

# Каталоги, которые Lineage исключает из разбора Soong в vendor/lineage/config/common.mk: продукт
# AOSP этот файл не наследует, а без исключения protobuf_vendorcompat определён дважды (здесь и
# в hardware/lineage/compat) и Soong отказывается собирать дерево.
PRODUCT_SOURCE_ROOT_DIRS += -kernel/platform
PRODUCT_SOURCE_ROOT_DIRS += -prebuilts/misc/protobuf_vendorcompat
