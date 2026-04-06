import { defineDocs, defineConfig } from 'fumadocs-mdx/config';

export const docs = defineDocs({
  dir: 'content/docs',
});

/* ── Nimbus console colorizer (rehype plugin) ─────────────────────── */

const C: Record<string, string> = {
  green: '#4ade80', yellow: '#facc15', red: '#f87171',
  cyan: '#22d3ee', brightCyan: '#67e8f9', blue: '#60a5fa',
  brightBlue: '#93c5fd', magenta: '#c084fc', dim: '#6b7280',
};

type Seg = { text: string; color?: string };

function colorizeLine(line: string): Seg[] {
  const o: Seg[] = [];
  let r = line;
  const p = (t: string, c?: string) => { if (t) o.push({ text: t, color: c }); };

  // Banner
  if (line.includes('__ ___  _ __  ___')) { p(line, C.blue); return o; }
  if (line.includes('o.)///')) { p(line, C.brightBlue); return o; }
  if (line.includes('o \\/ U')) { p(line, C.cyan); return o; }
  if (line.includes("/___,'")) { p(line, C.brightCyan); return o; }
  if (/^\s+C\sL\sO\sU\sD/.test(line)) { p(line, C.dim); return o; }
  if (line.includes("Let's get your cloud ready")) { p(line, C.dim); return o; }
  if (line.includes('Fetching available versions')) {
    const m = line.match(/^(.+)(✓)$/);
    if (m) { p(m[1], C.dim); p(m[2], C.green); return o; }
    p(line, C.dim); return o;
  }

  // Timestamp
  const ts = r.match(/^(\[[\d:]+\])/);
  if (ts) { p(ts[1], C.dim); r = r.slice(ts[1].length); }

  // Whitespace
  const ws = r.match(/^(\s+)/);
  if (ws) { p(ws[1]); r = r.slice(ws[1].length); }

  // Patterns
  const rules: [RegExp, (m: RegExpMatchArray) => void][] = [
    [/^(● READY)(.*)$/, m => { p(m[1], C.green); r = m[2]; }],
    [/^(▲ STARTING)(.*)$/, m => { p(m[1], C.yellow); r = m[2]; }],
    [/^(▼ STOPPING)(.*)$/, m => { p(m[1], C.yellow); r = m[2]; }],
    [/^(● STARTING)(.*)$/, m => { p(m[1], C.yellow); r = m[2]; }],
    [/^(○ STOPPED)(.*)$/, m => { p(m[1], C.dim); r = m[2]; }],
    [/^(✖ CRASHED)(.*)$/, m => { p(m[1], C.red); r = m[2]; }],
    [/^(◉ DRAINING)(.*)$/, m => { p(m[1], C.magenta); r = m[2]; }],
    [/^(↑ SCALE UP)(.*)$/, m => { p(m[1], C.green); r = m[2]; }],
    [/^(↓ SCALE DOWN)(.*)$/, m => { p(m[1], C.yellow); r = m[2]; }],
    [/^(nimbus)( »)(.*)$/, m => { p(m[1], C.brightCyan); p(m[2], C.cyan); r = m[3]; }],
    [/^(──.+)$/, m => { p(m[1], C.cyan); r = ''; }],
    [/^(─{4,})$/, m => { p(m[1], C.dim); r = ''; }],
    [/^(▸)(.*)$/, m => { p(m[1], C.cyan); r = m[2]; }],
    [/^(✓)(.*)$/, m => { p(m[1], C.green); r = m[2]; }],
    [/^(✗)(.*)$/, m => { p(m[1], C.red); r = m[2]; }],
    [/^(ℹ)(.*)$/, m => { p(m[1], C.cyan); r = m[2]; }],
    [/^(⚠)(.*)$/, m => { p(m[1], C.yellow); r = m[2]; }],
    [/^(!)(.+)$/, m => { p(m[1], C.yellow); r = m[2]; }],
    [/^(↑)(.*)$/, m => { p(m[1], C.green); r = m[2]; }],
    [/^(↓)(.*)$/, m => { p(m[1], C.cyan); r = m[2]; }],
    [/^(⚡)(.*)$/, m => { p(m[1], C.magenta); r = m[2]; }],
    [/^(↻)(.*)$/, m => { p(m[1], C.cyan); r = m[2]; }],
    [/^(◆)(.*)$/, m => { p(m[1], C.brightCyan); r = m[2]; }],
    [/^(◇)(.*)$/, m => { p(m[1], C.cyan); r = m[2]; }],
    [/^(◈)(.*)$/, m => { p(m[1], C.cyan); r = m[2]; }],
    [/^(\+)(.*)$/, m => { p(m[1], C.green); r = m[2]; }],
    [/^(█+)(░*)(.*)$/, m => { p(m[1], C.green); if (m[2]) p(m[2], C.dim); r = m[3]; }],
  ];
  for (const [re, fn] of rules) { const m = r.match(re); if (m) { fn(m); break; } }

  // Remaining: dim (parenthesized)
  if (r) {
    let pos = 0; const re = /\([^)]+\)/g; let m;
    while ((m = re.exec(r)) !== null) {
      if (m.index > pos) p(r.slice(pos, m.index));
      p(m[0], C.dim);
      pos = m.index + m[0].length;
    }
    if (pos < r.length) p(r.slice(pos));
  }
  return o.length ? o : [{ text: line }];
}

function getText(n: any): string {
  if (n.type === 'text') return n.value ?? '';
  return n.children ? n.children.map(getText).join('') : '';
}

function walk(n: any, fn: (n: any) => void) {
  fn(n);
  if (n.children) for (const c of n.children) walk(c, fn);
}

function hasNimbusTitle(node: any): boolean {
  const p = node.properties ?? {};
  if (typeof p.title === 'string' && p.title.startsWith('Nimbus')) return true;
  let found = false;
  walk(node, (n: any) => {
    if (n.type === 'element' && n.tagName === 'figcaption' && getText(n).startsWith('Nimbus'))
      found = true;
  });
  return found;
}

function rehypeNimbus() {
  return (tree: any) => {
    console.log('[rehype-nimbus] Plugin running');
    walk(tree, (node: any) => {
      if (node.type !== 'element') return;
      if (node.tagName !== 'pre' && node.tagName !== 'figure') return;
      if (!hasNimbusTitle(node)) return;

      walk(node, (el: any) => {
        if (el.type !== 'element' || el.tagName !== 'span') return;
        const cls = Array.isArray(el.properties?.className)
          ? el.properties.className
          : [String(el.properties?.className ?? '')];
        if (!cls.some((c: string) => c === 'line' || c.includes('line'))) return;

        const text = getText(el);
        if (!text) return;
        const segs = colorizeLine(text);
        if (!segs.some(s => s.color)) return;

        el.children = segs.map(s =>
          s.color
            ? { type: 'element', tagName: 'span', properties: { style: `color:${s.color}` }, children: [{ type: 'text', value: s.text }] }
            : { type: 'text', value: s.text },
        );
      });
    });
  };
}

/* ── Config ────────────────────────────────────────────────────────── */

export default defineConfig({
  mdxOptions: {
    rehypePlugins: (v) => [...v, rehypeNimbus],
  },
});
