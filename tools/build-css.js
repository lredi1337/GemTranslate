// Собирает стили Tailwind по классам из index.html и вставляет их в файл между метками.
// Запуск после правок внешнего вида:  cd tools && npm install && npm run css
const fs = require('fs');
const path = require('path');
const postcss = require('postcss');
const tailwind = require('tailwindcss');

const FILE = path.join(__dirname, '..', 'index.html');
const START = '/* tailwind:start */';
const END = '/* tailwind:end */';
const BLOCK = /\/\* tailwind:start \*\/[\s\S]*?\/\* tailwind:end \*\//;

const html = fs.readFileSync(FILE, 'utf8');
if (!BLOCK.test(html)) throw new Error('В index.html нет меток ' + START + ' … ' + END);
// Классы ищем в разметке и скриптах, но не в уже вставленных стилях
const content = [{ raw: html.replace(BLOCK, ''), extension: 'html' }];
const config = { ...require('./tailwind.config.js'), content };

postcss([tailwind(config)])
    .process('@tailwind base;@tailwind components;@tailwind utilities;', { from: undefined })
    .then(({ css }) => {
        const min = css
            .replace(/\/\*[\s\S]*?\*\//g, '')
            .replace(/\s+/g, ' ')
            .replace(/\s*([{};])\s*/g, '$1')
            .replace(/;}/g, '}')
            .trim();
        fs.writeFileSync(FILE, html.replace(BLOCK, () => `${START}${min}${END}`));
        console.log(`Стили обновлены: ${(min.length / 1024).toFixed(1)} КБ`);
    });
