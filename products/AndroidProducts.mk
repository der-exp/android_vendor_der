# Продукты der-exp для проверки вне телефона.
#
# lineage_der_cf_x86_64 — виртуальный телефон Cuttlefish (AOSP, x86_64) с надстройкой vendor/der:
# движок steer, Splify2, службы init и политика SELinux ставятся так же, как в прошивку. Нужен,
# чтобы проверить движок рядом с настоящими netd и ConnectivityService на ядре GKI 6.12 ещё до
# появления телефона на этом ядре. Ядро с nf_tables — из kernel/gki-android16-6.12 (см.
# README.md там же, «Cuttlefish»): TARGET_KERNEL_PATH, SYSTEM_DLKM_SRC и KERNEL_MODULES_PATH
# задаются окружением сборки, в продукте путей машины нет.
PRODUCT_MAKEFILES := \
    lineage_der_cf_x86_64:$(LOCAL_DIR)/lineage_der_cf_x86_64.mk

COMMON_LUNCH_CHOICES := \
    lineage_der_cf_x86_64-bp4a-userdebug
