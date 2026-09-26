# GKI android16-6.12 с nf_tables

Ядро Google (GKI) для телефонов на Android 16 с ядром 6.12, в которое встроен nf_tables —
то, на чём движок steer ставит свои правила. Модули производителя остаются заводскими:
меняется только раздел `boot`.

Первая цель — OnePlus 15T (`fairlady`, ПО 16.0.10.500): его заводское ядро —
`6.12.38-android16-5-g844001fb8721-ab14552068-4k`, сборка Google 14552068.

## Почему своё ядро

В заводском GKI `# CONFIG_NF_TABLES is not set`, и модулем nf_tables не добавить: модуль
обязан зарегистрироваться в nfnetlink, а в экспорте ядра нет ни одной функции `nfnetlink_*`,
`nf_nat_*` и `nf_ct_netns_*` (сверено по `vmlinux.symvers` сборки 14552068).

Встроенному nf_tables экспорт не нужен. Мешает другое: опция добавляет поле в середину
`struct net`, и у 1089 из 9358 экспортов меняются контрольные суммы — модули производителя
отказываются грузиться. Правка `0001-…patch` ставит поле последним, в 16 байт выравнивающего
хвоста структуры (размер `struct net` остаётся 4160), и прячет его от подсчёта сумм
(`ANDROID_KABI_IGNORE`).

## Что проверено

На сборочной машине, против сборки Google 14552068:

- заводской GKI, собранный из тех же исходников, даёт `vmlinux.symvers`, совпадающий с
  опубликованным Google строка в строку — то есть сравнению можно доверять;
- наше ядро: 9358 экспортов, все контрольные суммы как у Google; у всех типов, известных
  заводскому ядру, размер тот же (`pahole --sizes`), новые типы — только nf_tables;
  в конфигурации изменились только опции nf_tables;
- в QEMU (aarch64, `vmcheck-init.sh` как `/init`) движок сам выбрал современную раскладку
  (одна таблица `inet steer`), `nft` загрузил его правила; работают NAT в `inet`
  (`redirect`, `masquerade`), наборы с таймаутом, `meta skuid`, `mark` и `ct mark`, цепочка
  `route`. Заводское ядро на том же тесте отвечает `cache initialization failed`.

Не проверено: загрузка на телефоне (15T ещё нет) и работа модулей производителя с этим ядром
на железе — совпадение сумм говорит, что загрузчик модулей их примет, но не заменяет запуска.

## Как собрать

```
kernel/gki-android16-6.12/build.sh [DIR]      # DIR по умолчанию ~/gki, BUILD_ID=14552068
```

Скрипт берёт манифест и эталонные суммы сборки с ci.android.com, синхронизирует исходники по
манифесту, накладывает правку, собирает `kernel_aarch64_dist` с фрагментом
`nftables_defconfig` и отказывает, если хоть одна сумма разошлась с заводской. Около 35 ГБ
исходников; сама сборка — около пяти минут на 16 потоках.

`BUILD_ID` — всегда номер сборки из строки версии ядра, которое стоит на телефоне
(`cat /proc/version`). После обновления ПО телефона ядро нужно пересобрать под новый номер.

## Проверка в QEMU

`vmcheck-init.sh` — `/init` для initramfs с busybox, статическими `nft` и `steer` из
`tools/ndk-check`:

```
mkdir -p root/bin && cp busybox nft steer root/bin/ && cp vmcheck-init.sh root/init
(cd root && find . | cpio -o -H newc | gzip > ../initramfs.gz)
qemu-system-aarch64 -M virt -cpu max -smp 2 -m 1024 -nographic -no-reboot \
    -kernel Image -initrd initramfs.gz -append "console=ttyAMA0 rdinit=/init panic=-1"
```

В конце печатается `VMCHECK-OK` или `VMCHECK-FAIL`. Для busybox нужны ссылки `sh`, `mount`,
`ip`, `cat`, `grep`, `wc`, `mkdir`, `printf`, `poweroff`.

## Cuttlefish: движок рядом с настоящим netd

Виртуальный телефон Android 16 (DerpFest, x86_64) с `vendor/der` и этим ядром — чтобы
проверить движок рядом с netd, DnsResolver и SELinux до появления телефона на GKI. Продукт —
`products/lineage_der_cf_x86_64.mk`.

1. Ядро x86_64 с модулями виртуального устройства, в дереве GKI после `build.sh`:
   ```
   tools/bazel run --defconfig_fragment=//der:nftables_defconfig \
       //common-modules/virtual-device:virtual_device_x86_64_dist -- --destdir=out-cf-x86
   ```
   Разложить как готовое ядро Cuttlefish, внутри дерева прошивки (Soong не берёт путей вне
   дерева), в `vendor/der/local/cfk`: `sys/kernel-6.12` — `bzImage`; `sys/*.ko` и
   `sys/system_dlkm_staging/` — из `system_dlkm_staging_archive.tar.gz` (модули GKI — из его
   `flatten/lib/modules`); `vendor/` — остальные `*.ko` и `initramfs.img`.
2. Сборка образа:
   ```
   export TARGET_KERNEL_USE=6.12 SYSTEM_DLKM_SRC=vendor/der/local/cfk/sys \
          KERNEL_MODULES_PATH=vendor/der/local/cfk/vendor \
          TARGET_KERNEL_PATH=vendor/der/local/cfk/sys/kernel-6.12
   lunch lineage_der_cf_x86_64-bp4a-userdebug && m
   ```
   На Ubuntu 24.04 (и KDE Neon) сборка Trusty в песочнице nsjail требует
   `sysctl kernel.apparmor_restrict_unprivileged_userns=0` — до перезагрузки машины.
3. Хост: пакеты `cuttlefish-base` и `cuttlefish-user` (репозиторий
   `https://us-apt.pkg.dev/projects/android-cuttlefish-artifacts`), пользователь в группах
   `kvm`, `cvdnetwork`, `render`. Запуск — из сеанса, который переживёт выход из SSH
   (`systemd-run --user --scope tmux …`), иначе виртуальная машина гаснет вместе с ним:
   ```
   launch_cvd --daemon --cpus=4 --memory_mb=6144 --system_image_dir=$ANDROID_PRODUCT_OUT \
       --extra_bootconfig_args=androidboot.cuttlefish_service_bluetooth_checker=false
   ```
   Без последнего флага Cuttlefish объявляет загрузку неудачной, не дождавшись Bluetooth.
4. В образе: Wi-Fi подключается вручную (`cmd wifi connect-network VirtWifi open`), root по adb
   — переключателем «Rooted debugging» в параметрах разработчика (`su` в DerpFest нет).

Что проверено на этом стенде (steer 0abb75a):
- выход `awg` на сервер WireGuard хоста — движок создаёт устройство вида `wireguard`;
- канал по uid приложения уходит в туннель (сервер видит пакеты с адреса туннеля), трафик вне
  списка — напрямую;
- доменный канал: DNS перехватывается, имя из списка уходит в туннель, остальные имена
  разрешаются как обычно;
- счётчики `status`;
- после `kill netd` правило 9000 и masquerade возвращаются менее чем за 5 с.

Ограничения стенда:
- Splify2 здесь не запускается: WebView в образе — Google Trichrome только с библиотеками arm64.
  По той же причине падает Gboard.
- Проверять каналы по uid надо от приложения с живым процессом. Android 15+ режет сеть uid без
  процесса на переднем плане, включая его DNS. Годится, например, `org.lineageos.updater` на
  экране.
