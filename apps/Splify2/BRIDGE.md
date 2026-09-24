# splify2 на телефоне: мост между экраном и приложением (версия 1)

Экран splify2 на телефоне — веб-страница на `@andromeda/ui` внутри WebView приложения
`com.der.splify2`. На роутере та же роль у rpcd: экран зовёт методы объекта `splify2` по ubus.
Здесь вместо rpcd — мост: объект `SplifyBridge`, который Kotlin кладёт в страницу через
`addJavascriptInterface`. Этот файл описывает то, как три части сведены на самом деле:

| Часть | Где | Что делает | Проверка |
|---|---|---|---|
| Экран | `web/` (исходники), собранное — `assets/web/` | React (Preact) + `@andromeda/ui`, мобильная раскладка | `npm run build` (tsc + vite), снимки `web/scripts/shots.mjs` на заглушке `web/src/mock.ts` |
| Оболочка | `src/com/der/splify2/` (кроме `logic/`) | Activity, WebView, мост, сокет движка, настройки Android, список приложений, фоновое задание | `tools/app-check/build.sh` (APK вне дерева, `STRICT=1` — без предупреждений) |
| Логика | `src/com/der/splify2/logic/` | модель настроек, сборка спеки, каталог списков, подписки, резервная копия, фоновые обновления | `tools/app-check/logic-test.sh` — JVM против настоящего движка |

**Источник правды по данным — логика**: её проверяет стенд, где каждая собранная спека проходит
компилятор движка (`check` управляющего сокета и `steer apply --dry-run`), а файлы списков
заливаются и убираются настоящими командами сокета. Экран подстраивается под логику, оболочка —
под обе. Типы экрана — `web/src/types.ts`, форма модели — шапка `logic/Model.kt`.

## Вызов

```
SplifyBridge.call(id: string, method: string, argsJson: string): void
```

Вызов не блокирует поток страницы: оболочка выполняет метод в своём пуле (четыре потока — столько
запросов одновременно принимает сервер сокета) и отвечает вызовом в странице

```
window.__splifyReply(id, replyJson)
```

где `replyJson` — СТРОКА JSON `{"ok":true,"result":…}` или
`{"ok":false,"error":"<код>","message":"<текст для человека>"}`. `id` — строка, её выбирает
страница (счётчик, `String(++seq)`); по нему она находит свой Promise. Аргументы — тоже строка
JSON объекта (`"{}"`, если их нет). Обёртка на стороне экрана — `web/src/bridge.ts`:
`call<M>(method, args?)`; ответа нет 60 с — отказ `internal`. Без оболочки (браузер,
`npm run dev`) та же обёртка отвечает из заглушки `web/src/mock.ts` тем же путём.

Мост отвечает только странице своего источника `https://appassets.androidplatform.net`
(`WebAssets`); вызов с другой страницы молча отбрасывается.

События без запроса оболочка шлёт вызовом `window.__splifyEvent(name, payloadJson)` (данные —
тоже строкой JSON). Имена — в таблице ниже.

Коды ошибок: `bad-args`, `unknown-method` (метода или команды движка нет в этой версии),
`engine-down` (сокет движка не отвечает), `engine` (движок ответил отказом — фраза в `message`),
`network`, `io`, `internal`. `message` — для человека: состояние и действие, без слов разработки.

## Отступы под системные панели

Окно рисуется под строкой состояния и панелью навигации (edge-to-edge, targetSdk 36). Оболочка
кладёт их размеры CSS-переменными на `<html>` (в CSS-пикселях):

```
--splify-inset-top  --splify-inset-right  --splify-inset-bottom  --splify-inset-left
```

и обновляет их при каждой смене (`MainActivity.applyInsets`). Клавиатура в
`--splify-inset-bottom` не входит: при открытой клавиатуре WebView становится ниже. Экран читает
их через свои `--sp-inset-*` (`web/src/index.css`) с запасным `env(safe-area-inset-*)` — так
же раскладывается и в браузере без оболочки.

## Методы

Имена — `группа.действие`. Группы `engine`, `system`, `apps` — оболочки; `settings`, `spec`,
`lists`, `subs`, `backup` — логики (оболочка передаёт их в `logic.Dispatcher.call` и заворачивает
результат в конверт). У `spec.apply`, `settings.put`, `backup.import`, `backup.export` оболочка
добавляет своё поведение (ниже).

### engine — движок через управляющий сокет (`steer/docs/ctl.md`)

| Метод | Аргументы | Результат |
|---|---|---|
| `engine.state` | — | `{"enabled":bool,"reachable":bool,"version":str}` — выключатель (`persist.der.steer.enabled`) и отвечает ли сокет |
| `engine.setEnabled` | `{"on":bool}` | как `engine.state`; перед включением — `spec.apply` текущей модели; после — сверка Private DNS и событие `engine.changed` |
| `engine.status` | `{"fast"?:bool}` | JSON `steer status` как есть |
| `engine.diag` | — | JSON `steer diag` как есть (код 1 — «есть поломка», JSON полный) |
| `engine.explain` | `{"q":str}` | JSON `steer explain`, а если вывод не JSON — `{"text":str}` |
| `engine.vlessNodes` | `{"out":str}` | JSON `steer vless-nodes`: `{output, sub_file, node, chosen, usable, skipped, foreign, nodes:[{index,name,host,port,type,security,vision,mode?}], skipped_reasons}` |
| `engine.vlessProbe` | `{"out":str,"node"?:int}` | JSON `steer vless-probe`: `{output, sub_file, results:[{index,name,type,ok,handshake_ms,ttfb_ms,why}], working}`; замерять нечего — `{ok:false, error}` |
| `engine.conns` | — | JSON `steer conns`: `{schema, conns:[{family,proto,src,sport?,dst,dport?,mark,out\|null,state?,packets?,bytes?,reply_packets?,reply_bytes?}], shown, total, truncated}`; conntrack недоступен — отказ `engine` «недоступен на этом телефоне» |
| `engine.dnsLog` | — | JSON `steer dns-log`: `{schema, running, size, names:[{name,channel\|null,out\|null,count,last,ago}]}` |

Движок без команды — `unknown-method` (для `conns`/`dns-log`) или `engine` с фразой «так не
умеет». У `conns` нет ни приложения, ни имени хоста — conntrack их не хранит; экран называет
адрес и выход, а правило — только если в выход ведёт ровно одно включённое правило.

`apply`/`check`/файловые команды сокета экран напрямую не зовёт: это дело логики.

### system — настройки Android

| Метод | Аргументы | Результат |
|---|---|---|
| `system.network` | — | `{"type":"wifi"\|"cellular"\|"ethernet"\|"none","name"?:str,"metered":bool}` |
| `system.privateDns` | — | `{"mode":"off"\|"opportunistic"\|"hostname","host"?:str,"managed":bool,"saved"?:{mode,host}}` — `managed`: splify2 сейчас держит его выключенным ради доменных правил телефона, `saved` — что было у человека |

Оркестрация Private DNS (решение владельца, `ANDROID_AGENT_TASK.md` §6) — поведение оболочки, а
не метод: при первом запуске `private_dns_default_mode=off`; после каждого `spec.apply` оболочка
берёт у логики `Dispatcher.lastApply.needsLocalDns` (есть ли в СОХРАНЁННОЙ движком спеке доменные
каналы на сам телефон) и, если они есть и движок включён, запоминает режим человека и ставит
`off`; когда их нет или движок выключен — возвращает запомненное.

### apps — приложения телефона для правил «на приложение»

| Метод | Аргументы | Результат |
|---|---|---|
| `apps.list` | `{"system"?:bool}` | `[{"uid":int,"pkg":str,"label":str,"system":bool,"shared":[str]}]` — по uid (у приложений с общим uid — все пакеты в `shared`) |

Значок приложения экран берёт картинкой `https://appassets.androidplatform.net/icon/<pkg>.png`
(оболочка отдаёт его из `shouldInterceptRequest`).

### settings, spec — модель настроек и спека движка (логика)

Источник правды на телефоне — модель splify2, которую логика хранит в файлах приложения. Спека
движка из неё СОБИРАЕТСЯ (`SpecBuilder`) и отдаётся движку через сокет; обратного разбора нет.

Модель (`settings.get`, резервная копия):

```json
{"version":1,
 "outputs":[{"name":"vpn","kind":"interface","devices":["wg0"],"on_fail":"drop"},
            {"name":"nl","kind":"vless","sub":"s1","nodes":[2,5],"on_fail":"drop"},
            {"name":"tg","kind":"tgws","domain":"example.com"}],
 "channels":[{"name":"YouTube","enabled":true,
              "who":{"kind":"phone"} | {"kind":"apps","uids":[10123]} | {"kind":"tether","from":[]},
              "what":{"lists":["itdoginfo:youtube"],"custom":["work"],"all":false},
              "out":"nl"}],
 "lists":["itdoginfo:youtube"],
 "custom":[{"name":"work","domains":["corp.example"],"prefixes":["203.0.113.0/24"]}],
 "subs":[{"id":"s1","name":"Моя подписка","url":"https://…","kind":"url"}],
 "tether":{"devices":["rndis0","ncm0","softap0","ap0","swlan0","bt-pan"]},
 "catalog_url":null,
 "update":{"unmetered_only":true}}
```

- Выход `direct` есть всегда и в модели не заводится: правило с `"out":"direct"` — «напрямую».
  Виды выходов: `interface`, `vless`, `tgws` (мост Telegram — только для правил раздачи).
  «При отказе» — `drop` (умолчание) или `direct`; zapret на телефоне нет.
- Правила идут сверху вниз, первое совпадение побеждает. «Кому»: `phone` — весь телефон
  (`from:"self"`), `apps` — uid (`from:"uid:N"`), `tether` — раздача (`from` пуст — все её
  устройства, иначе адреса, подсети или MAC). «Что»: службы каталога (`lists`, id из
  `lists.catalog`), свои списки (`custom`, по имени) или `all` — весь трафик. Доменов прямо в
  правиле нет: свой домен — это свой список.
- `update.unmetered_only` — ежесуточное обновление только без лимитной сети (по умолчанию да).

| Метод | Аргументы | Результат |
|---|---|---|
| `settings.get` | — | модель целиком |
| `settings.put` | модель целиком или часть | `{"saved":true}`; проверка схемы, без применения. Поля верхнего уровня, которых нет в запросе, остаются как были. Подписки не добавляются и не удаляются (только `subs.*`), из присланных берутся лишь новые названия известных. Оболочка после сохранения переставляет задание обновления по `update.unmetered_only` |
| `spec.preview` | — | `{"spec":obj,"check":{"code":int,"stderr":str,"error"?:str},"warnings":[str],"needs_local_dns":bool}` |
| `spec.apply` | — | `{"applied":bool,"saved":bool,"rolled_back"?:true,"message"?:str,"warnings":[str]}` — докачка недостающих списков, сборка, `check`, заливка файлов (`put-file`), `apply`, уборка старых файлов. `saved` без `applied` — движок выключен, настройка сохранена; `rolled_back` — система не приняла правила, стоят прежние. Отказ проверки — ошибка `engine` с причиной. Оболочка после — Private DNS и событие `engine.changed` |

Экран держит черновиком только выходы и правила и при «Применить» шлёт
`settings.put {outputs, channels}` → `spec.apply`; всё прочее сохраняет сразу своими методами.

### lists — каталог списков (splify2-lists)

| Метод | Аргументы | Результат |
|---|---|---|
| `lists.catalog` | — | `{"version":str,"updated":int,"last_update":{ok,changed,message?,at}\|null,"items":[{"id","name","description"?,"source"?,"kinds":["domains"\|"prefixes"],"count"?,"tag"?,"default_on","selected","used","downloaded"?:{count,updated},"narrow"?:{proto,ports}}]}` — служба каталога = доменная и адресная записи, склеенные по `same_as_ip`; `updated` — когда скачан сам каталог |
| `lists.select` | `{"id":str,"on":bool}` | `{"saved":true}` — выбранные службы обновляются, даже если их ещё нет в правилах |
| `lists.update` | `{"force"?:bool}` | `{"started":true}` или `{"started":false,"running":true}`; по окончании — событие `lists.updated` |
| `lists.custom` | — | `[{"name","domains":[…],"prefixes":[…],"used":bool}]` |
| `lists.custom` | `{"put":{"name":str,"text"?:str,"domains"?:[…],"prefixes"?:[…]}}` | `{"saved":true,"domains":n,"prefixes":n,"dropped":n}` — `text` логика сама делит на домены и подсети (IPv4); имя — латиница, цифры, `_`, `-`, до 24 знаков; переименования нет |
| `lists.custom` | `{"remove":str}` | `{"saved":true}`; список из сохранённого правила не удаляется (`bad-args`) |

### subs — подписки (VLESS)

| Метод | Аргументы | Результат |
|---|---|---|
| `subs.list` | — | `[{"id","name","kind":"url"\|"links","url"?,"nodes","updated","skipped","foreign","used_by":[выходы],"hwid","quota"?,"warn"?,"link"?,"reasons"?}]` |
| `subs.add` | `{"url":str,"name"?:str}` | как `subs.list`; `url` — ссылка на подписку `https://…` или сами ссылки `vless://` (через пробел или строки) |
| `subs.remove` | `{"id":str}` | как `subs.list`; подписку, на которую ссылается выход, удалить нельзя (`bad-args`) |
| `subs.refresh` | `{"id"?:str}` | как `subs.list`; вставленные ссылки обновить нечем (`bad-args`) |

`quota` — остаток по заголовку `subscription-userinfo`: `{"up":str,"down":str,"total":str,"expire":int,"at":int}`,
байты — строками (JSON-число в JavaScript точно только до 2^53), `total:""` — объём не назван,
`expire:0` — срок не назван.

Узлы подписки логика считает своим разбором (`Subs.kt`), а не командой сокета `sub-check`: стенд
сверяет этот счёт с разбором движка (`sub.c`) на его же образцах, он работает без запущенного
движка и в фоновом задании, и не добавляет разговора с сокетом к каждому добавлению подписки.

### backup

| Метод | Аргументы | Результат |
|---|---|---|
| `backup.export` | — | `{"file":str,"name":str,"bytes":int,"saved":bool}` — логика пишет файл (модель и содержимое подписок) в свои данные; оболочка отдаёт его системному «Сохранить как» с именем `name` и добавляет `saved` (false — окно закрыли) |
| `backup.import` | `{"json":str}` | `{"saved":true}` — модель заменяется, но не применяется; оболочка переставляет задание обновления |

## События

| Имя | Когда | Данные |
|---|---|---|
| `engine.changed` | включили/выключили, применили спеку | как `engine.state` |
| `network.changed` | смена сети (callback ConnectivityManager, только пока открыт экран) | как `system.network` |
| `lists.updated` | закончилось обновление списков по `lists.update` | `{"ok":bool,"changed":int,"message"?:str}` |
| `subs.updated` | закончилось обновление подписок с `emit` (сейчас так не зовётся: `subs.refresh` отвечает списком сам, ночное обновление идёт без экрана) | как `subs.list` |

## Файлы списков и движок

Движок читает файлы списков, на которые ссылается спека (`domains_files`, `prefixes_files`,
`sub_file`), из каталога `/data/misc/steer/lists`; приложение писать туда не может (SELinux), и
всё идёт командами сокета (`steer/docs/ctl.md`, «put-file, list-files, rm-file»):

- `put-file <имя> <длина>` — логика заливает файлы собранной спеки перед `apply` (при
  `spec.apply` — все, в фоне — только изменившиеся). Путь в спеке — тот, что движок назвал в
  ответе (`path`); логика запоминает его каталог и, если он отличается от ожидаемого,
  пересобирает спеку с ним. Имена — `[A-Za-z0-9_.-]`, до 64 знаков: службы каталога `d-…`,
  `p-…`, `m-…`, свои списки `ud-…`, `up-…`, подписки `sub-<id>-<хеш>.txt` (новое содержимое —
  новое имя).
- `list-files` + `rm-file <имя>` — после каждого применения, которое движок сохранил, логика
  убирает СВОИ файлы (по этим именам), на которые спека больше не ссылается: старые версии
  подписок, списки ушедших правил. Файл из сохранённой спеки движок удалить не даст (`in-use`).
- Движок без `put-file` — применение со списками или подписками не проходит с отказом `engine`
  «обновите систему»; без `list-files`/`rm-file` — старые файлы просто остаются.

## Батарея

Ничего периодического по будильнику. Обновление списков и подписок — одно задание `JobScheduler`
раз в сутки (окно 6 ч) с условием сети — «без лимитной сети» по `update.unmetered_only` — и не на
исходе батареи; в фоне приложение не держит ни сервиса, ни wakelock. Ночное обновление
переприменяет СНИМОК применённой модели, а не черновик человека. Смена сети —
`registerDefaultNetworkCallback` только пока открыт экран; опрос `engine.status` — раз в 5 с,
только пока страница видна.
