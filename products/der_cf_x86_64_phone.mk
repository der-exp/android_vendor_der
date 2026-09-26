# Cuttlefish x86_64 (только 64 бита) + vendor/der. См. AndroidProducts.mk рядом.
$(call inherit-product, device/google/cuttlefish/vsoc_x86_64_only/phone/aosp_cf.mk)
$(call inherit-product, vendor/der/config/der.mk)

PRODUCT_NAME := der_cf_x86_64_phone
PRODUCT_DEVICE := vsoc_x86_64_only
PRODUCT_MODEL := Cuttlefish x86_64 phone (der-exp)
