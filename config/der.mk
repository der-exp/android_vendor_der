# der.mk — надстройка der-exp над прошивкой: движок steer и его userspace-обвязка.
#
# Подключается из device.mk устройства (beryllium) строкой
#   $(call inherit-product, vendor/der/config/der.mk)
# после того, как локальный манифест положил vendor/der на место. Здесь только то, что
# общее для любого устройства; всё, что зависит от железа, остаётся в дереве устройства.
#
# Почему system_ext, а не vendor. splify2 (управляющее приложение) живёт на стороне
# платформы — это coredomain. В Treble coredomain НЕ имеет права ходить в unix-сокеты
# доменов из вендора (это отдельный класс запретов в system/sepolicy). Канал splify2 → steer
# идёт именно через unix-сокет, поэтому и демон, и его бинарники обязаны быть на платформенной
# стороне раздела. system_ext — это платформенное расширение вендора прошивки, ровно наш
# случай: не AOSP, но и не привязка к железу Qualcomm.

PRODUCT_PACKAGES += \
    steerd \
    steer \
    nft \
    steerd.rc \
    Splify2

# steerd — расширенная сборка движка (клиент VLESS/Reality, xsteer, tgws; решение владельца —
# сразу в первой версии), с ссылкой steer-tools (symlinks в Android.bp движка); steer — клиент
# сокета демона. Ей нужен mbedtls 3.6 из external/der-mbedtls: копия AOSP в
# external/mbedtls — 3.5.2 без модулей Soong. Базовая сборка без mbedtls — модуль steer_base.
# Оба ставятся в /system_ext/bin/der/ (relative_install_path в Android.bp), nft — рядом в
# /system_ext/bin. Резолвер, сторож и помощники — это тот же steerd, отдельных пакетов у них нет.

# Splify2 — приложение управления (apps/Splify2): system_ext/priv-app на платформенной подписи,
# единственная дверь к движку. Белый список его привилегированных разрешений
# (privapp_allowlist_com.der.splify2 → system_ext/etc/permissions) приходит сам — через required
# модуля, отдельной строки здесь не нужно.

# Политика SELinux для домена steerd (демон) и заготовки типов, которые будет использовать
# приложение splify2. Кладётся в приватную и публичную политику system_ext: приватную видит
# только платформа, публичную (объявления типов) — все, кому нужно на них ссылаться.
SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += vendor/der/sepolicy/private
SYSTEM_EXT_PUBLIC_SEPOLICY_DIRS  += vendor/der/sepolicy/public

# Свойство-выключатель. Демон не стартует сам по себе: init поднимает его, только когда
# splify2 (или человек через splify2) выставит persist.der.steer.enabled=1. Так свежая
# прошивка с нашей обвязкой, но без настроенного туннеля, не трогает сеть вообще.
PRODUCT_PRODUCT_PROPERTIES += \
    persist.der.steer.enabled=0

# Каталог состояния и конфигурации создаёт init (см. steerd.rc), а не этот файл: путь и
# владелец должны совпадать с тем, что ждёт SELinux-метка, и держать это в одном месте
# надёжнее.

# Ключ adb сборочной машины — только для тестовых сборок (userdebug/eng) и только если он
# задан переменной окружения DER_ADB_KEYS — путём ВНУТРИ дерева (Soong-модуль adb_keys
# отвергает пути вне $TOP): build.sh копирует ключ из build.env в vendor/der/local/ (каталог
# в .gitignore) и экспортирует относительный путь. Зачем: у тестового телефона после каждой прошивки новая система и новый
# recovery, и без ключа adb не пускает ни туда, ни туда, пока человек не нажмёт «разрешить» на
# экране — а при бутлупе нажать негде, и журнал ядра прошлой загрузки (pstore) из recovery
# не прочитать. В пользовательских сборках (user) ключ не вшивается никогда.
ifneq ($(filter userdebug eng,$(TARGET_BUILD_VARIANT)),)
ifneq ($(DER_ADB_KEYS),)
PRODUCT_ADB_KEYS := $(DER_ADB_KEYS)
endif
endif
