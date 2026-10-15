const article = document.querySelector('#content');
let active = '';
const positions = new Map();
function remember() {
  if (!active) return;
  positions.set(active, {scroll: window.scrollY, open: [...article.querySelectorAll('details')].map((node, i) => node.open ? i : -1).filter(i => i >= 0)});
}
function show() {
  remember();
  const key = new URLSearchParams(location.hash.slice(1)).get('chapter') || 'README.md';
  const page = pages.find(page => page.file === key) || pages[0];
  active = page.file;
  article.innerHTML = page.html;
  document.querySelectorAll('nav [data-file]').forEach(link => link.classList.toggle('active', link.dataset.file === page.file));
  document.title = page.title + ' · pycode';
  const saved = positions.get(active);
  if (saved) {
    const nodes = article.querySelectorAll('details');
    saved.open.forEach(i => { if (nodes[i]) nodes[i].open = true; });
  }
  window.scrollTo(0, saved?.scroll || 0);
}
addEventListener('hashchange', show);
document.querySelector('#find').addEventListener('input', event => {
  const query = event.target.value.trim().toLowerCase();
  document.querySelectorAll('nav [data-file]').forEach(link => { link.hidden = !link.textContent.toLowerCase().includes(query); });
});
document.addEventListener('click', event => {
  const button = event.target.closest('[data-flow-toggle]');
  if (!button) return;
  const flow = document.querySelector('[data-flow="' + button.dataset.flowToggle + '"]');
  if (flow) button.textContent = flow.classList.toggle('fit') ? '恢复原始字号' : '缩小看全图';
});
show();
