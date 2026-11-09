// npm ci --prefix tools/floatwin-katex --ignore-scripts 后运行；产物随 APK 离线分发。
// 悬浮窗自绘页没有网络，也没开 file:// 访问，所以：JS/CSS 由宿主在读页面时内联注入，
// 字体只取 woff2 并转成 data URL 内联进 CSS（现代 WebView 只认这一种就够）。
import {readFile, writeFile, mkdir, copyFile, readdir} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {join} from 'node:path';

const root = new URL('../', import.meta.url);
const dist = fileURLToPath(new URL('tools/floatwin-katex/node_modules/katex/dist/', root));
const out = fileURLToPath(new URL('app/src/main/assets/floatwin/katex/', root));
await mkdir(out, {recursive: true});

const js = await readFile(join(dist, 'katex.min.js'), 'utf8');
let css = await readFile(join(dist, 'katex.min.css'), 'utf8');

// 只保留 woff2：去掉 woff / ttf 两条 fallback，再整体换成 data URL。
css = css.replace(/,url\(fonts\/([^)]+?)\.woff\) format\("woff"\),url\(fonts\/\1\.ttf\) format\("truetype"\)/g, '');
const fonts = {};
for (const name of await readdir(join(dist, 'fonts'))) {
  if (!name.endsWith('.woff2')) continue;
  const bytes = await readFile(join(dist, 'fonts', name));
  fonts[name] = `data:font/woff2;base64,${bytes.toString('base64')}`;
}
css = css.replace(/url\(fonts\/([^)]+?\.woff2)\)/g, (_, name) => {
  const data = fonts[name];
  if (!data) throw new Error(`KaTeX CSS 引用了未打包的字体：${name}`);
  return `url(${data})`;
});
if (/url\(fonts\//.test(css)) throw new Error('KaTeX CSS 里还有未内联的字体引用');

await writeFile(join(out, 'katex.min.js'), js);
await writeFile(join(out, 'katex.inline.css'), css);
await copyFile(join(dist, '..', 'LICENSE'), join(out, 'LICENSE'));

const pkg = JSON.parse(await readFile(fileURLToPath(new URL('tools/floatwin-katex/package.json', root)), 'utf8'));
const inputs = {};
for (const name of ['tools/prepare-floatwin-katex.mjs', 'tools/floatwin-katex/package.json',
                    'tools/floatwin-katex/package-lock.json']) {
  inputs[name] = createHash('sha256').update(await readFile(new URL(name, root))).digest('hex');
}
await writeFile(join(out, 'katex.inputs.json'), JSON.stringify({
  package: `katex@${pkg.dependencies.katex}`,
  license: 'MIT',
  fontFormat: 'woff2',
  fontCount: Object.keys(fonts).length,
  js: {bytes: Buffer.byteLength(js), sha256: createHash('sha256').update(js).digest('hex')},
  css: {bytes: Buffer.byteLength(css), sha256: createHash('sha256').update(css).digest('hex')},
  inputs,
}, null, 2) + '\n');
console.log(`KaTeX 资产：js ${Buffer.byteLength(js)} 字节，css（含 ${Object.keys(fonts).length} 个 woff2）${Buffer.byteLength(css)} 字节`);
