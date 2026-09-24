# splify2 на телефоне: мост между экраном и приложением (версия 1)

Экран splify2 на телефоне — веб-страница на `@andromeda/ui` внутри WebView приложения
`com.der.splify2`. На роутере та же роль у rpcd: экран зовёт методы объекта `splify2` по ubus.
Здесь вместо rpcd — мост: объект `SplifyBridge`, который Kotlin кладёт в страницу через
`addJavascriptInterface`. Этот файл — договор между тремя частями, которые пишутся отдельно:

| Часть | Где | Что делает |
|---|---|---|
| Экран | `web/` (исходники), собранное — в `assets/web/` | React + `@andromeda/ui`, мобильная раскладка |
| Оболочка | `src/com/der/splify2/` (кроме `logic/`) | Activity, WebView, мост, сокет движка, настройки Android, список приложений |
| Логика | `src/com/der/splify2/logic/` | модель настроек, сборка спеки, каталог списков, подписки, резервная копия, фоновые обновления |

## Вызов

```
SplifyBridge.call(id: string, method: string, argsJson: string): void
```

Вызов не блокирует поток страницы: оболочка выполняет метод в своём пуле потоков и отвечает
вызовом в странице

```
window.__splifyReply(id, replyJson)
```

где `replyJson` — `{"ok":true,"result":…}` или `{"ok":false,"error":"<код>","message":"<текст для человека>"}`.
`id` выбирает страница (счётчик); по нему она находит свой Promise. Обёртка на стороне экрана —
`web/src/bridge.ts`: `call<T>(method, args?): Promise<T>`; в браузере без оболочки (разработка
экрана, `npm run dev`) та же обёртка отвечает из заглушки `web/src/mock.ts`.

События без запроса (закончилось обновление списков, сменилась сеть, движок включился) оболочка
шлёт вызовом `window.__splifyEvent(name, payloadJson)`. Имена событий — в таблице ниже.

Коды ошибок: `bad-args`, `unknown-method`, `engine-down` (сокет движка не отвечает), `engine`
(движок ответил отказом — подробности в `message`), `network`, `io`, `internal`.

## Методы

Имена — `группа.действие`. Аргументы и результат — JSON. Группа `engine`, `system`, `apps`
принадлежит оболочке; `settings`, `spec`, `lists`, `subs`, `backup` — логике (оболочка
передаёт их в `logic.Dispatcher`).

### engine — движок через управляющий сокет (`steer/docs/ctl.md`)

| Метод | Аргументы | Результат |
|---|---|---|
| `engine.state` | — | `{"enabled":bool,"reachable":bool,"version":str}` — выключатель (`persist.der.steer.enabled`) и отвечает ли сокет |
| `engine.setEnabled` | `{"on":bool}` | как `engine.state`; при включении — предварительно `apply` текущей спеки |
| `engine.status` | `{"fast"?:bool}` | JSON `steer status` как есть (объект) |
| `engine.diag` | — | JSON `steer diag` как есть |
| `engine.explain` | `{"q":str}` | JSON/текст `steer explain` как есть: `{"text":str}` если не JSON |
| `engine.vlessNodes` | `{"out":str}` | JSON `steer vless-nodes` |
| `engine.vlessProbe` | `{"out":str,"node"?:str}` | JSON `steer vless-probe` |
| `engine.conns` | — | (появится с командой `conns` сокета) список соединений |
| `engine.dnsLog` | — | (появится с командой `dns-log` сокета) недавние имена |

`apply`/`check` сокета экран напрямую не зовёт: спеку собирает логика (`spec.apply`).

### system — настройки Android

| Метод | Аргументы | Результат |
|---|---|---|
| `system.network` | — | `{"type":"wifi"|"cellular"|"ethernet"|"none","name"?:str,"metered":bool}` |
| `system.privateDns` | — | `{"mode":"off"|"opportunistic"|"hostname","host"?:str,"managed":bool,"saved"?:{mode,host}}` — `managed`: splify2 сейчас держит его выключенным ради доменных каналов телефона, `saved` — что было у человека |

Оркестрация Private DNS (решение владельца, см. `ANDROID_AGENT_TASK.md` §6) — не метод, а
поведение оболочки: при первом запуске `private_dns_default_mode=off`; когда в применённой спеке
появляются доменные каналы на сам телефон, оболочка запоминает `private_dns_mode`/`private_dns_specifier`
человека и ставит `off`; когда их не остаётся — возвращает запомненное. Логика сообщает оболочке
после каждого `spec.apply`, нужны ли доменные каналы телефона (`logic.ApplyResult.needsLocalDns`).

### apps — приложения телефона для каналов «на приложение»

| Метод | Аргументы | Результат |
|---|---|---|
| `apps.list` | `{"system"?:bool}` | `[{"uid":int,"pkg":str,"label":str,"system":bool,"shared":[str]}]` — по uid (у приложений с общим uid — все пакеты в `shared`) |

Значок приложения экран берёт картинкой по адресу `https://appassets.androidplatform.net/icon/<pkg>.png`
(оболочка отдаёт его из `shouldInterceptRequest`).

### settings, spec — модель настроек и спека движка (логика)

Источник правды на телефоне — модель splify2 (выходы, каналы, выбранные списки, подписки),
которую логика хранит в файлах приложения. Спека движка (`spec.json`) из неё СОБИРАЕТСЯ и
отдаётся движку через сокет; обратного разбора нет.

| Метод | Аргументы | Результат |
|---|---|---|
| `settings.get` | — | модель целиком (схема — в `logic/Model.kt`, повторяет понятия спеки: `outputs`, `channels`, `lists`, `subs`) |
| `settings.put` | модель целиком | `{"saved":true}`; проверка схемы, без применения |
| `spec.preview` | — | `{"spec":obj,"check":{"code":int,"stderr":str}}` — собранная спека и `check` движка |
| `spec.apply` | — | `{"applied":bool,"rolled_back"?:bool,"message"?:str}` — сборка, заливка файлов списков, `apply` |

Каналы (правила) идут сверху вниз, первое совпадение побеждает. «Кому» у канала: `phone`
(весь телефон — `from:"self"`), `apps` (список uid — `from:"uid:N"`), `tether` (раздача —
как на роутере, без `from`).

### lists — каталог списков (splify2-lists)

| Метод | Аргументы | Результат |
|---|---|---|
| `lists.catalog` | — | каталог (как на роутере: имя, название, описание, размер, дата) + отметка «выбран» |
| `lists.select` | `{"id":str,"on":bool}` | `{"saved":true}` |
| `lists.update` | `{"force"?:bool}` | `{"started":true}`; по окончании событие `lists.updated` |
| `lists.custom` | — / `{"put":{name,domains,prefixes}}` | свои списки человека |

### subs — подписки (VLESS и др.)

| Метод | Аргументы | Результат |
|---|---|---|
| `subs.list` | — | `[{id,name,url,nodes:int,updated:int,quota?:…}]` |
| `subs.add` / `subs.remove` / `subs.refresh` | `{url}` / `{id}` / `{id?}` | как `subs.list` |

### backup

| Метод | Аргументы | Результат |
|---|---|---|
| `backup.export` | — | `{"file":str}` — JSON модели; оболочка отдаёт его системному «Сохранить как» |
| `backup.import` | `{"json":str}` | `{"saved":true}` |

## События

| Имя | Когда | Данные |
|---|---|---|
| `engine.changed` | включили/выключили, применили спеку | как `engine.state` |
| `network.changed` | смена сети (callback ConnectivityManager) | как `system.network` |
| `lists.updated` | закончилось обновление списков | `{"ok":bool,"changed":int,"message"?:str}` |
| `subs.updated` | закончилось обновление подписок | как `subs.list` |

## Файлы списков и движок

Движок читает файлы списков, на которые ссылается спека (`domains_file(s)`, `prefixes_file(s)`,
`sub_file`), из своего каталога `/data/misc/steer`; приложение туда писать не может (SELinux).
Логика заливает их командой сокета `put-file` (появится в движке следующей волной; до того —
метод `EngineClient.putFile(name, bytes)` в оболочке, который ответит `engine`/«нет команды»).
Пути в собранной спеке — `/data/misc/steer/lists/<имя>`.

## Батарея

Ничего периодического по будильнику. Обновление списков и подписок — `JobScheduler` с условием
сети (и «не на лимитной сети» по настройке), не чаще раза в сутки; в фоне приложение не держит
ни сервиса, ни wakelock. Смена сети — `registerDefaultNetworkCallback` только пока открыт экран.
