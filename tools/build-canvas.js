// Компактная сборка для Canvas: без комментариев и лишних пробелов.
// Зачем: Gemini, создавая Canvas, заново выводит весь файл своей моделью. Полная версия (~96 тыс. токенов)
// в один ответ не помещается, и Gemini молча выкидывает куски кода. Компактная помещается целиком.
// Запуск: cd tools && npm install && npm run canvas  →  dist/translator-canvas.html
const fs = require('fs');
const path = require('path');
const { minify } = require('html-minifier-terser');

const SRC = path.join(__dirname, '..', 'index.html');
const OUT_DIR = path.join(__dirname, '..', 'dist');
const OUT = path.join(OUT_DIR, 'translator-canvas.html');

(async () => {
    let html = fs.readFileSync(SRC, 'utf8');
    // Встроенные стили (~30 КБ) заменяем загрузкой Tailwind с CDN, как в ранних версиях: так файл заметно короче
    // и Gemini воспроизводит его целиком. Запуск чуть медленнее, зато Canvas собирается без потерь
    const config = require('./tailwind.config.js');
    html = html.replace(/<style id="tailwind">[\s\S]*?<\/style>/, () =>
        `<script src="https://cdn.tailwindcss.com"></script>\n<script>if (window.tailwind) tailwind.config = ${JSON.stringify(config)};</script>`);
    if (!html.includes('cdn.tailwindcss.com')) throw new Error('Не нашёл блок встроенных стилей');
    let out = await minify(html, {
        collapseWhitespace: true,
        conservativeCollapse: true,
        removeComments: true,
        minifyCSS: true,
        // Только убрать комментарии и пробелы: никаких «умных» сжатий, которые меняют логику.
        // Глобальные имена (apiKey, __firebase_config и т. п.) не переименовываются
        minifyJS: { compress: false, mangle: { toplevel: false }, format: { comments: false } }
    });
    // Строка с ключом — ровно в том виде, в каком её ищет окно Gemini, чтобы подставить доступ к API
    const keyLine = /const apiKey\s*=\s*""\s*[,;]/;
    if (!keyLine.test(out)) throw new Error('Не нашёл объявление apiKey в сжатом коде');
    out = out.replace(keyLine, 'const apiKey = ""; // Автоматически подставляется средой Canvas\n');
    fs.mkdirSync(OUT_DIR, { recursive: true });
    fs.writeFileSync(OUT, out);
    const cyr = (out.match(/[а-яёА-ЯЁ]/g) || []).length;
    const tokens = Math.round((out.length - cyr) / 4 + cyr / 2);
    console.log(`${path.relative(process.cwd(), OUT)}: ${(out.length / 1024).toFixed(0)} КБ, ~${tokens} токенов (было ${(html.length / 1024).toFixed(0)} КБ)`);
})();
