// ANSI escape code → styled HTML span converter

const ANSI_COLORS: Record<number, string> = {
  30: "#6b7280", 31: "#f87171", 32: "#4ade80", 33: "#facc15",
  34: "#60a5fa", 35: "#c084fc", 36: "#22d3ee", 37: "#d1d5db",
  90: "#9ca3af", 91: "#f87171", 92: "#4ade80", 93: "#facc15",
  94: "#60a5fa", 95: "#c084fc", 96: "#22d3ee", 97: "#ffffff",
};

interface Span {
  text: string;
  color?: string;
  bold?: boolean;
  dim?: boolean;
}

export function parseAnsi(input: string): Span[] {
  const spans: Span[] = [];
  // Match ESC[ ... m sequences (handle both \x1b and \u001b)
  const regex = /\x1b\[([0-9;]*)m/g;
  let lastIndex = 0;
  let color: string | undefined;
  let bold = false;
  let dim = false;

  let match;
  while ((match = regex.exec(input)) !== null) {
    // Push text before this escape
    if (match.index > lastIndex) {
      const text = input.slice(lastIndex, match.index);
      if (text) spans.push({ text, color, bold, dim });
    }
    lastIndex = regex.lastIndex;

    // Parse SGR codes
    const codes = match[1].split(";").map(Number);
    for (const code of codes) {
      if (code === 0) {
        color = undefined;
        bold = false;
        dim = false;
      } else if (code === 1) {
        bold = true;
      } else if (code === 2) {
        dim = true;
      } else if (code === 22) {
        bold = false;
        dim = false;
      } else if (ANSI_COLORS[code]) {
        color = ANSI_COLORS[code];
      } else if (code === 39) {
        color = undefined;
      }
    }
  }

  // Remaining text
  if (lastIndex < input.length) {
    const text = input.slice(lastIndex);
    if (text) spans.push({ text, color, bold, dim });
  }

  return spans.length > 0 ? spans : [{ text: input }];
}

export function ansiToStyle(span: Span): React.CSSProperties {
  const style: React.CSSProperties = {};
  if (span.color) style.color = span.color;
  if (span.bold) style.fontWeight = "bold";
  if (span.dim) style.opacity = 0.5;
  return style;
}
