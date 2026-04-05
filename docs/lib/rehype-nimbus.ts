/**
 * Rehype plugin that colorizes Nimbus CLI output in code blocks.
 * Targets <pre> elements where the title property starts with "Nimbus".
 * Runs after rehypeCode/Shiki in the MDX pipeline.
 */

const C = {
  green: '#4ade80',
  yellow: '#facc15',
  red: '#f87171',
  cyan: '#22d3ee',
  brightCyan: '#67e8f9',
  blue: '#60a5fa',
  brightBlue: '#93c5fd',
  magenta: '#c084fc',
  dim: '#6b7280',
};

type Seg = { text: string; color?: string };

function colorizeLine(line: string): Seg[] {
  const out: Seg[] = [];
  let rest = line;
  const p = (t: string, c?: string) => { if (t) out.push({ text: t, color: c }); };

  // Banner: 4-line ASCII art gradient (blue → bright blue → cyan → bright cyan)
  if (line.includes('__ ___  _ __  ___')) { p(line, C.blue); return out; }
  if (line.includes('o.)///')) { p(line, C.brightBlue); return out; }
  if (line.includes('o \\/ U')) { p(line, C.cyan); return out; }
  if (line.includes("/___,'")) { p(line, C.brightCyan); return out; }
  if (/^\s+C\sL\sO\sU\sD/.test(line)) { p(line, C.dim); return out; }
  if (line.includes("Let's get your cloud ready")) { p(line, C.dim); return out; }
  if (line.includes('Fetching available versions')) {
    const m = line.match(/^(.+)(✓)$/);
    if (m) { p(m[1], C.dim); p(m[2], C.green); return out; }
    p(line, C.dim); return out;
  }

  const ts = rest.match(/^(\[[\d:]+\])/);
  if (ts) { p(ts[1], C.dim); rest = rest.slice(ts[1].length); }

  const ws = rest.match(/^(\s+)/);
  if (ws) { p(ws[1]); rest = rest.slice(ws[1].length); }

  const rules: [RegExp, (m: RegExpMatchArray) => void][] = [
    [/^(● READY)(.*)$/, m => { p(m[1], C.green); rest = m[2]; }],
    [/^(▲ STARTING)(.*)$/, m => { p(m[1], C.yellow); rest = m[2]; }],
    [/^(▼ STOPPING)(.*)$/, m => { p(m[1], C.yellow); rest = m[2]; }],
    [/^(● STARTING)(.*)$/, m => { p(m[1], C.yellow); rest = m[2]; }],
    [/^(○ STOPPED)(.*)$/, m => { p(m[1], C.dim); rest = m[2]; }],
    [/^(✖ CRASHED)(.*)$/, m => { p(m[1], C.red); rest = m[2]; }],
    [/^(◉ DRAINING)(.*)$/, m => { p(m[1], C.magenta); rest = m[2]; }],
    [/^(↑ SCALE UP)(.*)$/, m => { p(m[1], C.green); rest = m[2]; }],
    [/^(↓ SCALE DOWN)(.*)$/, m => { p(m[1], C.yellow); rest = m[2]; }],
    [/^(nimbus)( »)(.*)$/, m => { p(m[1], C.brightCyan); p(m[2], C.cyan); rest = m[3]; }],
    [/^(──.+)$/, m => { p(m[1], C.cyan); rest = ''; }],
    [/^(─{4,})$/, m => { p(m[1], C.dim); rest = ''; }],
    [/^(▸)(.*)$/, m => { p(m[1], C.cyan); rest = m[2]; }],
    [/^(✓)(.*)$/, m => { p(m[1], C.green); rest = m[2]; }],
    [/^(✗)(.*)$/, m => { p(m[1], C.red); rest = m[2]; }],
    [/^(ℹ)(.*)$/, m => { p(m[1], C.cyan); rest = m[2]; }],
    [/^(⚠)(.*)$/, m => { p(m[1], C.yellow); rest = m[2]; }],
    [/^(!)(.+)$/, m => { p(m[1], C.yellow); rest = m[2]; }],
    [/^(↑)(.*)$/, m => { p(m[1], C.green); rest = m[2]; }],
    [/^(↓)(.*)$/, m => { p(m[1], C.cyan); rest = m[2]; }],
    [/^(⚡)(.*)$/, m => { p(m[1], C.magenta); rest = m[2]; }],
    [/^(↻)(.*)$/, m => { p(m[1], C.cyan); rest = m[2]; }],
    [/^(◆)(.*)$/, m => { p(m[1], C.brightCyan); rest = m[2]; }],
    [/^(◇)(.*)$/, m => { p(m[1], C.cyan); rest = m[2]; }],
    [/^(◈)(.*)$/, m => { p(m[1], C.cyan); rest = m[2]; }],
    [/^(\+)(.*)$/, m => { p(m[1], C.green); rest = m[2]; }],
    [/^(█+)(░*)(.*)$/, m => { p(m[1], C.green); if (m[2]) p(m[2], C.dim); rest = m[3]; }],
  ];

  for (const [re, fn] of rules) {
    const m = rest.match(re);
    if (m) { fn(m); break; }
  }

  if (rest) {
    let pos = 0;
    const re = /\([^)]+\)/g;
    let m;
    while ((m = re.exec(rest)) !== null) {
      if (m.index > pos) p(rest.slice(pos, m.index));
      p(m[0], C.dim);
      pos = m.index + m[0].length;
    }
    if (pos < rest.length) p(rest.slice(pos));
  }

  return out.length ? out : [{ text: line }];
}

function getText(node: any): string {
  if (node.type === 'text') return node.value ?? '';
  if (node.children) return node.children.map(getText).join('');
  return '';
}

function walk(node: any, fn: (n: any, parent?: any) => void, parent?: any) {
  fn(node, parent);
  if (node.children) for (const c of node.children) walk(c, fn, node);
}

function hasNimbusTitle(node: any): boolean {
  // Check every possible way the title could be stored
  const props = node.properties ?? {};

  // Direct property: pre.properties.title
  if (typeof props.title === 'string' && props.title.startsWith('Nimbus')) return true;

  // data-title attribute
  if (typeof props['data-title'] === 'string' && props['data-title'].startsWith('Nimbus')) return true;
  if (typeof props.dataTitle === 'string' && props.dataTitle.startsWith('Nimbus')) return true;

  // Check if there's a figcaption child with "Nimbus"
  let found = false;
  walk(node, (n: any) => {
    if (n.type === 'element' && n.tagName === 'figcaption') {
      const text = getText(n);
      if (text.startsWith('Nimbus')) found = true;
    }
  });

  return found;
}

export function rehypeNimbus() {
  let logged = false;
  return (tree: any) => {
    // Debug: log first invocation to verify plugin runs
    if (!logged) {
      console.log('[rehype-nimbus] Plugin running');
      logged = true;
    }

    // Collect all elements first, then process (avoid walk-during-mutate issues)
    const targets: any[] = [];
    walk(tree, (node: any) => {
      if (node.type !== 'element') return;
      if (node.tagName === 'pre' || node.tagName === 'figure' || node.tagName === 'code') {
        const props = node.properties ?? {};
        // Log first few elements to see what properties exist
        if (!logged) {
          console.log(`[rehype-nimbus] Found <${node.tagName}> props:`, Object.keys(props));
        }
      }
    });

    walk(tree, (node: any) => {
      if (node.type !== 'element') return;

      const isTarget =
        (node.tagName === 'pre' && hasNimbusTitle(node)) ||
        (node.tagName === 'figure' && hasNimbusTitle(node));

      if (!isTarget) return;
      console.log(`[rehype-nimbus] Found Nimbus block: <${node.tagName}>`);

      // Find span.line elements inside and colorize
      walk(node, (el: any) => {
        if (el.type !== 'element' || el.tagName !== 'span') return;
        const cls = Array.isArray(el.properties?.className)
          ? el.properties.className
          : [String(el.properties?.className ?? el.properties?.class ?? '')];
        if (!cls.some((c: string) => c === 'line' || c.includes('line'))) return;

        const text = getText(el);
        if (!text) return;

        const segs = colorizeLine(text);
        if (!segs.some(s => s.color)) return;

        el.children = segs.map(s =>
          s.color
            ? {
                type: 'element',
                tagName: 'span',
                properties: { style: `color:${s.color}` },
                children: [{ type: 'text', value: s.text }],
              }
            : { type: 'text', value: s.text },
        );
      });
    });
  };
}
