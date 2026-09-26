# Продукты der-exp для проверки вне телефона.
#
# der_cf_x86_64_phone — виртуальный телефон Cuttlefish (AOSP, x86_64) с надстройкой vendor/der:
# движок steer, Splify2, службы init и политика SELinux ставятся так же, как в прошивку. Нужен,
# чтобы проверить движок рядом с настоящими netd и ConnectivityService на ядре GKI 6.12 ещё до
# появления телефона на этом ядре. Ядро с nf_tables — из kernel/gki-android16-6.12 (см.
# README.md там же, «Cuttlefish»): TARGET_KERNEL_PATH, SYSTEM_DLKM_SRC и KERNEL_MODULES_PATH
# задаются окружением сборки, в продукте путей машины нет.
PRODUCT_MAKEFILES := \
    der_cf_x86_64_phone:$(LOCAL_DIR)/der_cf_x86_64_phone.mk

COMMON_LUNCH_CHOICES := \
    der_cf_x86_64_phone-bp4a-userdebug
