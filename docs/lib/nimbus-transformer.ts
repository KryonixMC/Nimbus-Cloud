/**
 * Shiki transformer that colorizes Nimbus CLI output in code blocks
 * with title containing "Nimbus". Runs after Shiki's highlighting.
 */
import type { ShikiTransformer, ShikiTransformerContext } from 'shiki';

const C = {
  green: '#4ade80',
  yellow: '#facc15',
  red: '#f87171',
  cyan: '#22d3ee',
  brightCyan: '#67e8f9',
  blue: '#60a5fa',
  magenta: '#c084fc',
  dim: '#6b7280',
};

type Segment = { text: string; color?: string };

function colorizeLine(line: string): Segment[] {
  const segments: Segment[] = [];
  let rest = line;

  function push(text: string, color?: string) {
    if (text) segments.push({ text, color });
  }

  // Timestamp at start: [HH:MM:SS] or [12:00:01]
  const tsMatch = rest.match(/^(\[[\d:]+\])/);
  if (tsMatch) {
    push(tsMatch[1], C.dim);
    rest = rest.slice(tsMatch[1].length);
  }

  // Preserve leading whitespace
  const wsMatch = rest.match(/^(\s+)/);
  if (wsMatch) {
    push(wsMatch[1]);
    rest = rest.slice(wsMatch[1].length);
  }

  // Try matching patterns in order of priority
  const patterns: [RegExp, (m: RegExpMatchArray) => void][] = [
    // Status indicators
    [/^(● READY)(.*)$/, (m) => { push(m[1], C.green); rest = m[2]; }],
    [/^(▲ STARTING)(.*)$/, (m) => { push(m[1], C.yellow); rest = m[2]; }],
    [/^(▼ STOPPING)(.*)$/, (m) => { push(m[1], C.yellow); rest = m[2]; }],
    [/^(● STARTING)(.*)$/, (m) => { push(m[1], C.yellow); rest = m[2]; }],
    [/^(○ STOPPED)(.*)$/, (m) => { push(m[1], C.dim); rest = m[2]; }],
    [/^(✖ CRASHED)(.*)$/, (m) => { push(m[1], C.red); rest = m[2]; }],
    [/^(◉ DRAINING)(.*)$/, (m) => { push(m[1], C.magenta); rest = m[2]; }],
    [/^(↑ SCALE UP)(.*)$/, (m) => { push(m[1], C.green); rest = m[2]; }],
    [/^(↓ SCALE DOWN)(.*)$/, (m) => { push(m[1], C.yellow); rest = m[2]; }],

    // Prompt
    [/^(nimbus)( »)(.*)$/, (m) => { push(m[1], C.brightCyan); push(m[2], C.cyan); rest = m[3]; }],

    // Section header: ── Title ──────
    [/^(──.+)$/, (m) => { push(m[1], C.cyan); rest = ''; }],

    // Separator line
    [/^(─{4,})$/, (m) => { push(m[1], C.dim); rest = ''; }],

    // Section marker: ▸ Title
    [/^(▸)(.*)$/, (m) => { push(m[1], C.cyan); rest = m[2]; }],

    // Single-char symbols
    [/^(✓)(.*)$/, (m) => { push(m[1], C.green); rest = m[2]; }],
    [/^(✗)(.*)$/, (m) => { push(m[1], C.red); rest = m[2]; }],
    [/^(ℹ)(.*)$/, (m) => { push(m[1], C.cyan); rest = m[2]; }],
    [/^(⚠)(.*)$/, (m) => { push(m[1], C.yellow); rest = m[2]; }],
    [/^(!)(.*)$/, (m) => { push(m[1], C.yellow); rest = m[2]; }],
    [/^(↑)(.*)$/, (m) => { push(m[1], C.green); rest = m[2]; }],
    [/^(↓)(.*)$/, (m) => { push(m[1], C.cyan); rest = m[2]; }],
    [/^(⚡)(.*)$/, (m) => { push(m[1], C.magenta); rest = m[2]; }],
    [/^(↻)(.*)$/, (m) => { push(m[1], C.cyan); rest = m[2]; }],
    [/^(◆)(.*)$/, (m) => { push(m[1], C.brightCyan); rest = m[2]; }],
    [/^(◇)(.*)$/, (m) => { push(m[1], C.cyan); rest = m[2]; }],
    [/^(◈)(.*)$/, (m) => { push(m[1], C.cyan); rest = m[2]; }],
    [/^(\+)(.*)$/, (m) => { push(m[1], C.green); rest = m[2]; }],

    // Progress bar
    [/^(█+)(░*)(.*)$/, (m) => { push(m[1], C.green); if (m[2]) push(m[2], C.dim); rest = m[3]; }],
  ];

  let matched = false;
  for (const [pattern, handler] of patterns) {
    const m = rest.match(pattern);
    if (m) {
      handler(m);
      matched = true;
      break;
    }
  }

  // Remaining text: colorize (parenthesized) parts as dim
  if (rest) {
    let pos = 0;
    const parenRe = /\([^)]+\)/g;
    let pm;
    while ((pm = parenRe.exec(rest)) !== null) {
      if (pm.index > pos) push(rest.slice(pos, pm.index));
      push(pm[0], C.dim);
      pos = pm.index + pm[0].length;
    }
    if (pos < rest.length) push(rest.slice(pos));
  }

  return segments.length > 0 ? segments : [{ text: line }];
}

function makeSpan(text: string, color: string) {
  return {
    type: 'element' as const,
    tagName: 'span',
    properties: { style: `color:${color}` },
    children: [{ type: 'text' as const, value: text }],
  };
}

export function transformerNimbus(): ShikiTransformer {
  return {
    name: 'nimbus-colorizer',
    code(this: ShikiTransformerContext, node) {
      // Check if this code block has a Nimbus title via meta
      const meta: any = this.options.meta;
      const raw: string = meta?.__raw ?? meta?.['__raw'] ?? '';
      if (!raw.includes('title="Nimbus')) return;

      for (const lineEl of node.children) {
        if (lineEl.type !== 'element') continue;

        // Get text content of this line
        const text = getTextContent(lineEl);
        if (!text) continue;

        const segments = colorizeLine(text);

        // Only replace if we actually added colors
        if (segments.some((s) => s.color)) {
          lineEl.children = segments.map((seg) =>
            seg.color
              ? makeSpan(seg.text, seg.color)
              : { type: 'text' as const, value: seg.text },
          );
        }
      }
    },
  };
}

function getTextContent(node: any): string {
  if (node.type === 'text') return node.value ?? '';
  if (node.children) return node.children.map(getTextContent).join('');
  return '';
}
