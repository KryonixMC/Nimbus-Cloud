// ANSI escape code → styled HTML span converter

const ANSI_COLORS: Record<number, string> = {
  30: "#4d4d4d", 31: "#e06c75", 32: "#98c379", 33: "#e5c07b",
  34: "#61afef", 35: "#c678dd", 36: "#56b6c2", 37: "#abb2bf",
  90: "#5c6370", 91: "#e06c75", 92: "#98c379", 93: "#e5c07b",
  94: "#61afef", 95: "#c678dd", 96: "#56b6c2", 97: "#ffffff",
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
