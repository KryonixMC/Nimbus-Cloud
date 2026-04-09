"use client";

import { type ReactNode } from "react";

/**
 * Minecraft color code map (§/& codes → CSS colors).
 * Uses the exact Minecraft color values.
 */
const MC_COLORS: Record<string, string> = {
  "0": "#000000", // Black
  "1": "#0000AA", // Dark Blue
  "2": "#00AA00", // Dark Green
  "3": "#00AAAA", // Dark Aqua
  "4": "#AA0000", // Dark Red
  "5": "#AA00AA", // Dark Purple
  "6": "#FFAA00", // Gold
  "7": "#AAAAAA", // Gray
  "8": "#555555", // Dark Gray
  "9": "#5555FF", // Blue
  a: "#55FF55", // Green
  b: "#55FFFF", // Aqua
  c: "#FF5555", // Red
  d: "#FF55FF", // Light Purple
  e: "#FFFF55", // Yellow
  f: "#FFFFFF", // White
};

interface TextStyle {
  color: string;
  bold: boolean;
  italic: boolean;
  underline: boolean;
  strikethrough: boolean;
  obfuscated: boolean;
}

function parseMinecraftText(text: string): ReactNode[] {
  const nodes: ReactNode[] = [];
  const style: TextStyle = {
    color: "#000000",
    bold: false,
    italic: false,
    underline: false,
    strikethrough: false,
    obfuscated: false,
  };

  let buffer = "";
  let i = 0;
  let key = 0;

  function flush() {
    if (buffer.length === 0) return;
    const decorations: string[] = [];
    if (style.underline) decorations.push("underline");
    if (style.strikethrough) decorations.push("line-through");

    nodes.push(
      <span
        key={key++}
        style={{
          color: style.color,
          fontWeight: style.bold ? 700 : 400,
          fontStyle: style.italic ? "italic" : "normal",
          textDecoration: decorations.length > 0 ? decorations.join(" ") : "none",
        }}
      >
        {style.obfuscated ? buffer.replace(/./g, "?") : buffer}
      </span>
    );
    buffer = "";
  }

  while (i < text.length) {
    const ch = text[i];
    if ((ch === "&" || ch === "\u00A7") && i + 1 < text.length) {
      const code = text[i + 1].toLowerCase();
      if (MC_COLORS[code]) {
        flush();
        style.color = MC_COLORS[code];
        style.bold = false;
        style.italic = false;
        style.underline = false;
        style.strikethrough = false;
        style.obfuscated = false;
        i += 2;
        continue;
      }
      switch (code) {
        case "l":
          flush();
          style.bold = true;
          i += 2;
          continue;
        case "o":
          flush();
          style.italic = true;
          i += 2;
          continue;
        case "n":
          flush();
          style.underline = true;
          i += 2;
          continue;
        case "m":
          flush();
          style.strikethrough = true;
          i += 2;
          continue;
        case "k":
          flush();
          style.obfuscated = true;
          i += 2;
          continue;
        case "r":
          flush();
          style.color = "#000000";
          style.bold = false;
          style.italic = false;
          style.underline = false;
          style.strikethrough = false;
          style.obfuscated = false;
          i += 2;
          continue;
      }
    }
    buffer += ch;
    i++;
  }
  flush();
  return nodes;
}

interface MinecraftSignProps {
  lines: string[];
  label?: string;
  onClick?: () => void;
  className?: string;
}

export function MinecraftSign({ lines, label, onClick, className }: MinecraftSignProps) {
  const signLines = [...lines, "", "", "", ""].slice(0, 4);

  return (
    <div className={className}>
      <div
        onClick={onClick}
        className={`relative select-none ${onClick ? "cursor-pointer" : ""}`}
        style={{
          width: 288,
          transition: "transform 0.15s ease",
        }}
        onMouseEnter={(e) => onClick && (e.currentTarget.style.transform = "scale(1.05)")}
        onMouseLeave={(e) => onClick && (e.currentTarget.style.transform = "scale(1)")}
      >
        {/* Sign board using the real Minecraft oak sign texture */}
        <div
          style={{
            position: "relative",
            backgroundImage: "url(/oak_sign.png)",
            backgroundSize: "100% 100%",
            imageRendering: "pixelated",
            /* Image is 192x96 → aspect ratio 2:1 */
            width: 288,
            height: 144,
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
          }}
        >
          {/* Sign text lines — positioned within the sign face */}
          <div
            style={{
              fontFamily: "'Minecraft', monospace",
              fontSize: 18,
              lineHeight: "28px",
              textAlign: "center",
              textShadow: "1.5px 1.5px 0px rgba(0,0,0,0.12)",
              padding: "0 20px",
              width: "100%",
            }}
          >
            {signLines.map((line, i) => (
              <div key={i} style={{ whiteSpace: "nowrap", overflow: "hidden", textOverflow: "clip" }}>
                {line ? parseMinecraftText(line) : "\u00A0"}
              </div>
            ))}
          </div>
        </div>
      </div>

      {label && (
        <p className="text-center text-xs text-muted-foreground mt-2 font-medium">{label}</p>
      )}
    </div>
  );
}
