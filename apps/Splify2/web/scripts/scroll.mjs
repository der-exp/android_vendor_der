// Прокрутка касанием на заглушке моста: каждый раздел листается вертикальным свайпом и не
// уезжает вбок горизонтальным. Жест — сырыми touch-событиями CDP: synthesizeScrollGesture в
// headless-режиме не прокручивает и простую страницу, на нём проверка ничего бы не значила.
//
//   npm run dev -- --port 5391 &
//   node scripts/scroll.mjs            # или: node scripts/scroll.mjs http://localhost:5391
import { chromium } from "playwright"

const base = process.argv[2] || "http://localhost:5391"
const b = await chromium.launch()
let bad = 0
async function swipe(p, cdp, dx, dy) {
  await cdp.send("Input.dispatchTouchEvent", { type: "touchStart", touchPoints: [{ x: 200, y: 600 }] })
  for (let i = 1; i <= 20; i++) {
    await cdp.send("Input.dispatchTouchEvent", { type: "touchMove", touchPoints: [{ x: 200 + (dx * i) / 20, y: 600 + (dy * i) / 20 }] })
    await p.waitForTimeout(16)
  }
  await cdp.send("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] })
  await p.waitForTimeout(400)
}
for (const tab of ["home", "outputs", "rules", "conns", "more"]) {
  const ctx = await b.newContext({ viewport: { width: 360, height: 700 }, hasTouch: true, isMobile: true, deviceScaleFactor: 3 })
  const p = await ctx.newPage()
  const cdp = await ctx.newCDPSession(p)
  await p.goto(`${base}/?tab=${tab}`)
  await p.waitForTimeout(1200)
  const tall = await p.evaluate(() => document.documentElement.scrollHeight > innerHeight + 50)
  await swipe(p, cdp, 0, -400)
  const y = await p.evaluate(() => scrollY)
  await swipe(p, cdp, -150, 0)
  const x = await p.evaluate(() => scrollX)
  const ok = (!tall || y > 0) && x === 0
  if (!ok) bad++
  console.log(`${ok ? "ok  " : "FAIL"} ${tab}: ${tall ? `вниз ${y}px` : "короче экрана"}, вбок ${x}px`)
  await ctx.close()
}
await b.close()
process.exit(bad ? 1 : 0)
