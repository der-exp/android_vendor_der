// Снимки экранов с заглушкой моста: каждый раздел и подэкраны, светлая и тёмная тема,
// ширина 390 px. Заодно проверяет, что на 360 px нет горизонтальной прокрутки.
//
//   npm run dev -- --port 5391 &
//   npm run shots                  # или: node scripts/shots.mjs http://localhost:5391 shots
//
// Нужен Chromium Playwright (npx playwright install chromium, если его нет в кэше).
import { chromium } from "playwright"
import fs from "node:fs"
import path from "node:path"

const base = process.argv[2] || "http://localhost:5391"
const outDir = path.resolve(process.argv[3] || "shots")
fs.mkdirSync(outDir, { recursive: true })

const settle = (page, ms = 900) => page.waitForTimeout(ms)

/** Экраны: имя файла, адрес, действия после загрузки. */
const openRule = (name) => async (p) => { await p.getByText(name, { exact: true }).click(); await settle(p, 400) }
const more = (item) => async (p) => { await p.getByText(item, { exact: true }).click(); await settle(p) }

const addAwg = async (p) => { await p.getByRole("button", { name: /AmneziaWG/ }).click(); await settle(p, 300) }
const AWG_TEXT = `[Interface]
PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
Address = 10.8.0.2/32
MTU = 1280

[Peer]
PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
Endpoint = 203.0.113.9:51820
AllowedIPs = 0.0.0.0/0`

const screens = [
  { name: "01-home", q: "?tab=home" },
  { name: "02-outputs", q: "?tab=outputs", after: async (p) => { await p.getByRole("button", { name: "Замерить" }).first().click(); await settle(p, 2200) } },
  { name: "03-rules", q: "?tab=rules" },
  { name: "04-rule-editor", q: "?tab=rules", after: openRule("Банки напрямую") },
  { name: "05-rule-apps-picker", q: "?tab=rules", after: async (p) => { await openRule("Банки напрямую")(p); await p.getByRole("button", { name: "Изменить выбор" }).click(); await settle(p) } },
  { name: "06-rules-pending", q: "?tab=rules", after: async (p) => { await p.getByRole("button", { name: "Порядок" }).click(); await p.getByRole("button", { name: "Ниже" }).first().click(); await settle(p, 500) } },
  { name: "07-rules-apply-failed", q: "?tab=rules&apply=fail", after: async (p) => { await p.getByRole("switch").last().click(); await settle(p, 300); await p.getByRole("button", { name: /Применить/ }).click(); await settle(p, 1800) } },
  { name: "08-conns", q: "?tab=conns", after: async (p) => { await p.getByPlaceholder(/youtube/).fill("youtube.com"); await p.getByRole("button", { name: "Проверить" }).click(); await settle(p) } },
  { name: "09-conns-missing", q: "?tab=conns&conns=0" },
  { name: "10-dns", q: "?tab=conns", after: async (p) => { await p.getByRole("button", { name: "Имена" }).click(); await settle(p) } },
  { name: "11-more", q: "?tab=more" },
  { name: "12-more-lists", q: "?tab=more", after: more("Списки") },
  { name: "13-more-subs", q: "?tab=more", after: more("Подписки") },
  { name: "14-more-backup", q: "?tab=more", after: more("Резервная копия") },
  { name: "15-more-engine", q: "?tab=more", after: async (p) => { await more("Движок")(p); await p.getByRole("button", { name: "Проверить" }).click(); await settle(p, 1200) } },
  { name: "16-home-engine-down", q: "?tab=home&engine=down" },
  { name: "17-rule-lists-picker", q: "?tab=rules", after: async (p) => { await openRule("YouTube")(p); await p.getByRole("button", { name: "Изменить списки" }).click(); await settle(p) } },
  { name: "18-rule-new-list", q: "?tab=rules", after: async (p) => { await openRule("YouTube")(p); await p.getByRole("button", { name: "Свои домены и подсети" }).click(); await settle(p) } },
  { name: "19-more-custom", q: "?tab=more", after: more("Свои списки") },
  { name: "20-more-custom-edit", q: "?tab=more", after: async (p) => { await more("Свои списки")(p); await p.getByText("work", { exact: true }).click(); await settle(p) } },
  { name: "21-dns-add-rule", q: "?tab=conns", after: async (p) => { await p.getByRole("button", { name: "Имена" }).click(); await settle(p); await p.getByRole("button", { name: "Добавить правило" }).first().click(); await settle(p) } },
  { name: "22-home-off", q: "?tab=home", after: async (p) => { await p.getByRole("switch", { name: "Маршрутизация" }).click(); await settle(p, 1200) } },
  { name: "24-add-output-awg", q: "?tab=outputs", after: async (p) => { await addAwg(p); await p.getByLabel("Имя").last().fill("nl-home"); await p.getByLabel("Текст файла").fill(AWG_TEXT); await settle(p, 300); await p.getByText("Добавить выход", { exact: true }).last().scrollIntoViewIfNeeded() } },
  { name: "25-add-output-awg-error", q: "?tab=outputs", after: async (p) => { await addAwg(p); await p.getByLabel("Имя").last().fill("nl-home"); await p.getByLabel("Текст файла").fill(AWG_TEXT.replace("MTU = 1280", "MTU = 1280\nFoo = 1")); await p.getByRole("button", { name: "Добавить выход" }).last().click(); await settle(p, 600) } },
  { name: "26-add-output-awg-picked", q: "?tab=outputs", after: async (p) => { await addAwg(p); await p.getByRole("button", { name: "Выбрать файл" }).click(); await settle(p, 1400) } },
  { name: "23-new-rule", q: "?tab=rules", after: async (p) => { await p.getByRole("button", { name: "Новое правило" }).click(); await settle(p, 300); await p.getByRole("button", { name: "Добавить правило" }).click(); await settle(p, 300) } },
]

const browser = await chromium.launch()
const problems = []
for (const scheme of ["light", "dark"]) {
  for (const width of [390, 360]) {
    const ctx = await browser.newContext({ viewport: { width, height: 844 }, deviceScaleFactor: width === 390 ? 2 : 1, colorScheme: scheme, locale: "ru-RU", hasTouch: true, isMobile: true })
    for (const s of screens) {
      const page = await ctx.newPage()
      const errors = []
      page.on("pageerror", (e) => errors.push(String(e)))
      // Значки приложений в браузере не грузятся (их отдаёт оболочка) — это не ошибка.
      page.on("console", (m) => m.type() === "error" && !m.text().includes("ERR_NAME_NOT_RESOLVED") && errors.push(m.text()))
      await page.goto(base + "/" + s.q)
      await settle(page)
      // Шаг не удался (кнопку переименовали, экран не открылся) — это находка, а не повод
      // оборвать все остальные снимки.
      if (s.after) await s.after(page).catch((e) => problems.push(`${scheme} ${width} ${s.name}: шаг не выполнен: ${String(e).split("\n")[0]}`))
      const overflow = await page.evaluate(() => {
        const w = document.documentElement.clientWidth
        const wide = [...document.querySelectorAll("body *")].filter((el) => el.getBoundingClientRect().right > w + 0.5 && getComputedStyle(el).position !== "fixed")
        return { scroll: document.documentElement.scrollWidth - w, wide: wide.slice(0, 5).map((el) => el.tagName + ":" + (el.textContent || "").slice(0, 40)) }
      })
      if (overflow.scroll > 0 || overflow.wide.length) problems.push(`${scheme} ${width} ${s.name}: +${overflow.scroll}px ${overflow.wide.join(" | ")}`)
      if (errors.length) problems.push(`${scheme} ${width} ${s.name}: ${errors.join(" | ")}`)
      if (width === 390) {
        // Во весь рост: окно растягивается под страницу, чтобы нижняя панель легла внизу
        // снимка, а не посреди (fixed держится за окно, а не за документ).
        const h = await page.evaluate(() => Math.max(document.documentElement.scrollHeight, document.querySelector("[style*='position: fixed'][style*='inset']")?.scrollHeight ?? 0))
        await page.setViewportSize({ width, height: Math.max(844, h) })
        await settle(page, 300)
        await page.screenshot({ path: path.join(outDir, `${s.name}-${scheme}.png`) })
      }
      await page.close()
    }
    await ctx.close()
  }
}
await browser.close()
console.log(problems.length ? "ПРОБЛЕМЫ:\n" + problems.join("\n") : "без горизонтальной прокрутки и ошибок страницы")
console.log("снимки:", outDir)
