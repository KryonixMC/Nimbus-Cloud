"use client";

import { parseAnsi, ansiToStyle } from "@/lib/ansi";

export function AnsiLine({ text }: { text: string }) {
  const spans = parseAnsi(text);
  return (
    <div className="whitespace-pre-wrap break-all">
      {spans.map((span, i) => {
        const style = ansiToStyle(span);
        return Object.keys(style).length > 0 ? (
          <span key={i} style={style}>
            {span.text}
          </span>
        ) : (
          <span key={i}>{span.text}</span>
        );
      })}
    </div>
  );
}
