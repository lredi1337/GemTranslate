// Сборка страницы для APK: встраивает стили Tailwind (только используемые классы) прямо в HTML,
// чтобы приложение открывалось мгновенно и работало без интернета.
// Запуск после правок waiter/web/index.html:  cd waiter/tools && npm install && npm run build
const fs = require('fs');
const path = require('path');
const postcss = require('postcss');
const tailwind = require('tailwindcss');

const SRC = path.join(__dirname, '..', 'web', 'index.html');
const OUT = path.join(__dirname, '..', 'android', 'app', 'src', 'main', 'assets', 'index.html');
const MARK = '/* tailwind */';

const html = fs.readFileSync(SRC, 'utf8');
if (!html.includes(MARK)) throw new Error('В index.html нет метки ' + MARK);

postcss([tailwind({ content: [{ raw: html, extension: 'html' }] })])
    .process('@tailwind base;@tailwind components;@tailwind utilities;', { from: undefined })
    .then(({ css }) => {
        const min = css.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\s+/g, ' ').replace(/\s*([{};])\s*/g, '$1').replace(/;}/g, '}').trim();
        fs.mkdirSync(path.dirname(OUT), { recursive: true });
        fs.writeFileSync(OUT, html.replace(MARK, () => min));
        console.log(`${path.relative(process.cwd(), OUT)}: ${(fs.statSync(OUT).size / 1024).toFixed(0)} КБ`);
    });
