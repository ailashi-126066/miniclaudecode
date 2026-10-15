// Offline rendering adapted from the user's GoFeed interview book; Python content is indexed locally.
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const {createRequire} = require('node:module');
const data = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const output = process.argv[3];
const candidates = [process.env.CODEX_DOC_NODE_MODULES, path.join(__dirname, 'node_modules'),
  path.join(os.homedir(), '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules')].filter(Boolean);
const modules = candidates.find(folder => fs.existsSync(path.join(folder, 'marked')) && fs.existsSync(path.join(folder, '@viz-js/viz')));
if (!modules) throw new Error('Install tools dependencies with npm install --prefix docs/interview/tools, or set CODEX_DOC_NODE_MODULES.');
const requireBundle = createRequire(path.join(modules, 'package.json'));
const {instance} = requireBundle('@viz-js/viz');
const escape = value => String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
const safeJSON = value => JSON.stringify(value).replace(/</g, '\\u003c');
const styles = fs.readFileSync(path.join(__dirname, 'book.css'), 'utf8') + fs.readFileSync(path.join(__dirname, 'chain.css'), 'utf8');
function dot(text) {
  const nodes = new Map(), edges = [], links = new Map()
  for (const line of text.trim().split('\n').slice(1)) {
    if (!line.trim()) continue
    const click = line.match(/^\s*click\s+(\w+)\s+"(source\.html#[^"]+)"\s*$/)
    if (click) { links.set(click[1], click[2]); continue }
    const tokens = [...line.matchAll(/\b([A-Za-z]\w*)\s*(?:\["([^"\n]*)"\]|\{"([^"\n]*)"\})?/g)]
    for (const [, id, box, decision] of tokens) if (box !== undefined || decision !== undefined) nodes.set(id, { label: box ?? decision, shape: decision !== undefined ? 'diamond' : 'box' })
    const relation = line.match(/^\s*([A-Za-z]\w*)(?:\["[^"\n]*"\]|\{"[^"\n]*"\})?\s*-->\s*(?:\|([^|]*)\|\s*)?([A-Za-z]\w*)/)
    if (!relation) {
      if (/^\s*\w+\s*(?:\["[^"\n]*"\]|\{"[^"\n]*"\})\s*$/.test(line)) continue
      throw new Error('Unsupported flow line: ' + line)
    }
    edges.push([relation[1], relation[3], relation[2] || ''])
  }
  const quote = text => JSON.stringify(text.replace(/<br\s*\/?\s*>/g, '\n').split('\n').map(line => {
    const rows = []; let current = '', width = 0
    for (const word of line.match(/[A-Za-z0-9_./:]+|./gu) || []) {
      const next = [...word].reduce((sum, char) => sum + (/[^\x00-\x7f]/.test(char) ? 2 : 1), 0)
      if (current && width + next > 40) { rows.push(current); current = ''; width = 0 }
      current += word; width += next
    }
    if (current) rows.push(current)
    return rows.join('\n')
  }).join('\n'))
  return 'digraph G { rankdir=TB; graph [bgcolor="transparent", pad="0.25", nodesep="0.3", ranksep="0.4"]; node [shape=box, style="rounded,filled", fillcolor="#eef6fa", color="#51768c", fontname="Arial, PingFang SC", fontsize=14, margin="0.5,0.15"]; edge [color="#617f90",fontname="Arial, PingFang SC",fontsize=12];\n' +
    [...nodes].map(([id, n]) => `${JSON.stringify(id)} [label=${quote(n.label)},shape=${n.shape}${links.has(id) ? ',URL=' + JSON.stringify(links.get(id)) + ',target="_blank",tooltip="打开此节点的完整源码"' : ''}];`).join('\n') + '\n' +
    edges.map(([a, b, label]) => `${JSON.stringify(a)} -> ${JSON.stringify(b)} [label=${quote(label)}];`).join('\n') + '\n}'
}

async function main() {
  const {marked} = await import(path.join(modules, 'marked/lib/marked.esm.js'));
  const viz = await instance();
  const pages = []; let chart = 0;
  const chapterFiles = new Set(data.chapters.map(chapter => chapter.file));
  for (const chapter of data.chapters) {
    const placeholders = new Map();
    let md = chapter.text.replace(/```mermaid\n([\s\S]*?)\n```/g, (_, text) => {
      const id = `DOCFLOWPLACEHOLDER${chart++}`;
      const svg = viz.renderString(dot(text), {format: 'svg'}).replace(/<\?xml[^>]*>|<!DOCTYPE[\s\S]*?>/g, '');
      placeholders.set(id, `<div class="flow-tools"><button type="button" data-flow-toggle="${chart}">缩小看全图</button><span>按箭头看协作关系；框架回调以章节说明为准。</span></div><div class="flow" data-flow="${chart}">${svg}</div>`);
      return `\n${id}\n`;
    });
    let html = marked.parse(md);
    for (const [id, svg] of placeholders) html = html.replace(`<p>${id}</p>`, svg);
    html = html.replace(/<table>/g, '<div class="table-wrap"><table>').replace(/<\/table>/g, '</table></div>');
    html = html.replace(/href="([^"#]+\.md)(#[^"]*)?"/g, (whole, file) => chapterFiles.has(file) ? `href="#chapter=${encodeURIComponent(file)}"` : whole);
    pages.push({file: chapter.file, title: chapter.text.match(/^# (.*)$/m)[1], html});
  }
  const nav = pages.map(page => `<a href="#chapter=${encodeURIComponent(page.file)}" data-file="${escape(page.file)}">${escape(page.title)}</a>`).join('');
  const viewer = fs.readFileSync(path.join(__dirname, 'book-view.js'), 'utf8');
  const shell = (title, body, script) => `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${title}</title><style>${styles}</style><body>${body}<script>${script}</script></body></html>`;
  fs.writeFileSync(path.join(output, 'index.html'), shell('pycode 功能与调用链手册', `<nav><h1>pycode<br>功能与调用链手册</h1><input id="find" aria-label="筛选章节" placeholder="按模块名称筛选"><a href="source.html">完整源码与函数调用</a>${nav}<p class="caption">源代码基线 ${data.head.slice(0,7)}<br>先读步骤说明，再按需展开代码 · 离线可读</p></nav><main><article id="content"></article></main>`, `const pages=${safeJSON(pages)};${viewer}`));
  const sample = pages.find(page => page.file === '05-agent.md') || pages[0];
  const sampleHTML = sample.html.replace(/href="#chapter=/g, 'href="index.html#chapter=');
  fs.writeFileSync(path.join(output, 'run.html'), shell('Agent 执行 · 原地展开调用链', `<style>main{margin:0 auto;max-width:1120px;padding:30px}</style><main><article><p class="account-preview-header"><a href="index.html">返回完整手册</a> · Agent 执行 · 点击函数就地展开</p>${sampleHTML}</article></main>`, ''));
  const sourceScript = fs.readFileSync(path.join(__dirname, 'source-view.js'), 'utf8');
  fs.writeFileSync(path.join(output, 'source.html'), shell('pycode 完整源码浏览器', `<nav><h1>完整源码浏览器</h1><a href="index.html">返回功能手册</a><input id="find" aria-label="筛选源码" placeholder="输入路径或函数名"><select id="files" aria-label="源文件"></select><select id="symbols" aria-label="函数与类型"></select><p class="caption">完整 Python 源码、类、方法与嵌套函数。源代码基线 ${data.head.slice(0,7)}；动态依赖按组装与章节链路核对。</p></nav><main><article id="content"></article></main>`, `const files=${safeJSON(data.files)};const revision=${safeJSON(data.head)};const repository=${safeJSON(data.repository)};const explanations=${safeJSON(data.explanations)};${sourceScript}`));
}
main().catch(error => { console.error(error); process.exit(1); });
