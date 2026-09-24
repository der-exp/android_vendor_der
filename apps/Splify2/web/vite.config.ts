import path from "node:path"
import fs from "node:fs"
import { defineConfig, type Plugin } from "vite"
import react from "@vitejs/plugin-react"

// Каталог пакета @andromeda/ui. Пакет исходниками, подключён как file:-зависимость
// (симлинк в node_modules), и его CSS-токены нужны по одному файлу: styles.css тянет
// tokens/fonts.css, а тот — Google Fonts по сети. WebView приложения чужих адресов не
// достаёт, и такой @import только задержал бы первый кадр до таймаута. Поэтому токены
// подключаются через этот псевдоним, а семейства шрифтов задаются в src/index.css
// системными (на телефоне это Roboto / Google Sans самой прошивки).
const andromeda = fs.realpathSync(path.resolve(import.meta.dirname, "node_modules/@andromeda/ui"))

// Страница грузится из assets APK — через WebViewAssetLoader или прямо с file://,
// как решит оболочка. Модульный <script type="module" crossorigin> с file:// браузер
// не исполнит (у модулей CORS, а у file:// нет источника), поэтому сборка — один
// IIFE-бандл, и тег переписывается в обычный отложенный скрипт. crossorigin снимается и
// со стилей по той же причине. Так страница работает при любом способе загрузки.
function classicScripts(): Plugin {
  return {
    name: "splify-classic-scripts",
    apply: "build",
    enforce: "post",
    transformIndexHtml(html) {
      return html
        .replace(/<script type="module" crossorigin src=/g, "<script defer src=")
        .replace(/ crossorigin(?=[ >])/g, "")
    },
  }
}

export default defineConfig({
  base: "./",
  plugins: [react(), classicScripts()],
  resolve: {
    alias: [
      { find: /^@andromeda-tokens\/(.*)$/, replacement: `${andromeda}/tokens/$1` },
      // React -> Preact на сборке, исходники остаются на React (как у splify2 на роутере):
      // весь экран — карточки, строки и кнопки, и React 19 весил бы больше самого экрана.
      { find: /^react$/, replacement: "preact/compat" },
      { find: /^react-dom$/, replacement: "preact/compat" },
      { find: /^react-dom\/client$/, replacement: "preact/compat/client" },
      { find: /^react\/jsx-runtime$/, replacement: "preact/jsx-runtime" },
      { find: /^react\/jsx-dev-runtime$/, replacement: "preact/jsx-dev-runtime" },
    ],
    // Компоненты пакета лежат вне этого каталога (симлинк), и их import "react" без
    // dedupe искал бы preact рядом с пакетом, где его нет.
    dedupe: ["preact", "react", "react-dom"],
  },
  server: {
    fs: { allow: [import.meta.dirname, andromeda] },
  },
  build: {
    outDir: path.resolve(import.meta.dirname, "../assets/web"),
    emptyOutDir: true,
    target: "es2020",
    modulePreload: false,
    cssCodeSplit: false,
    assetsInlineLimit: 8192,
    rolldownOptions: {
      output: {
        format: "iife",
        entryFileNames: "assets/splify.js",
        assetFileNames: "assets/splify[extname]",
      },
    },
  },
})
