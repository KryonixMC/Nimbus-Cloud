"use client";

import { useCallback, useEffect, useMemo, useRef } from "react";
import { cn } from "@/lib/utils";

export interface OtpInputProps {
  /** Total number of cells. Default 6. */
  length?: number;
  /** Current raw value. Any non-digit characters are ignored. */
  value: string;
  onChange: (value: string) => void;
  /** Fired when the user types the final digit. */
  onComplete?: (value: string) => void;
  autoFocus?: boolean;
  disabled?: boolean;
  /** Visually tag invalid state (red border). */
  invalid?: boolean;
  id?: string;
  "aria-label"?: string;
  className?: string;
}

/**
 * Six-tile one-time-code input. Each digit lives in its own cell:
 * typing auto-advances, Backspace jumps back, arrows navigate, Paste
 * spreads the clipboard across cells. Non-digit input is filtered.
 *
 * Reused by the login-form code screen and the TOTP verify screen.
 * Apple/Safari one-time-code SMS autofill works when every cell
 * carries `autocomplete="one-time-code"`.
 */
export function OtpInput({
  length = 6,
  value,
  onChange,
  onComplete,
  autoFocus,
  disabled,
  invalid,
  id,
  className,
  "aria-label": ariaLabel,
}: OtpInputProps) {
  const refs = useRef<(HTMLInputElement | null)[]>([]);

  const digits = useMemo(() => {
    const cleaned = value.replace(/\D/g, "").slice(0, length);
    return Array.from({ length }, (_, i) => cleaned[i] ?? "");
  }, [value, length]);

  useEffect(() => {
    if (autoFocus) refs.current[0]?.focus();
    // Only run once on mount; re-autofocusing on every render would steal focus.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const setDigitAt = useCallback(
    (index: number, ch: string) => {
      const next = digits.slice();
      next[index] = ch;
      const joined = next.join("").slice(0, length);
      onChange(joined);
      if (joined.length === length && onComplete) onComplete(joined);
    },
    [digits, length, onChange, onComplete]
  );

  function handleChange(i: number, raw: string) {
    const ch = raw.replace(/\D/g, "").slice(-1);
    if (!ch) {
      // Treat overwrite-with-empty as a clear — some browsers fire this
      // when the user selects + deletes inside the cell.
      setDigitAt(i, "");
      return;
    }
    setDigitAt(i, ch);
    const nextIndex = Math.min(i + 1, length - 1);
    refs.current[nextIndex]?.focus();
    refs.current[nextIndex]?.select();
  }

  function handleKeyDown(i: number, e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === "Backspace") {
      if (digits[i]) {
        setDigitAt(i, "");
        return;
      }
      // Empty cell: jump back and clear the previous one in one go.
      e.preventDefault();
      const prev = Math.max(i - 1, 0);
      setDigitAt(prev, "");
      refs.current[prev]?.focus();
    } else if (e.key === "ArrowLeft") {
      e.preventDefault();
      refs.current[Math.max(i - 1, 0)]?.focus();
    } else if (e.key === "ArrowRight") {
      e.preventDefault();
      refs.current[Math.min(i + 1, length - 1)]?.focus();
    } else if (e.key === "Home") {
      e.preventDefault();
      refs.current[0]?.focus();
    } else if (e.key === "End") {
      e.preventDefault();
      refs.current[length - 1]?.focus();
    }
  }

  function handlePaste(i: number, e: React.ClipboardEvent<HTMLInputElement>) {
    const pasted = e.clipboardData.getData("text").replace(/\D/g, "");
    if (!pasted) return;
    e.preventDefault();
    const next = digits.slice();
    for (let k = 0; k < pasted.length && i + k < length; k++) {
      next[i + k] = pasted[k];
    }
    const joined = next.join("").slice(0, length);
    onChange(joined);
    const settleIndex = Math.min(i + pasted.length, length - 1);
    refs.current[settleIndex]?.focus();
    refs.current[settleIndex]?.select();
    if (joined.length === length && onComplete) onComplete(joined);
  }

  return (
    <div
      role="group"
      aria-label={ariaLabel}
      id={id}
      className={cn("flex items-center gap-2", className)}
    >
      {digits.map((d, i) => (
        <input
          key={i}
          ref={(el) => {
            refs.current[i] = el;
          }}
          type="text"
          inputMode="numeric"
          autoComplete="one-time-code"
          maxLength={1}
          disabled={disabled}
          value={d}
          onChange={(e) => handleChange(i, e.target.value)}
          onKeyDown={(e) => handleKeyDown(i, e)}
          onPaste={(e) => handlePaste(i, e)}
          onFocus={(e) => e.target.select()}
          aria-label={ariaLabel ? `${ariaLabel} digit ${i + 1}` : `Digit ${i + 1}`}
          className={cn(
            // Fixed geometry so adding/removing digits never shifts layout.
            "h-12 w-10 shrink-0 rounded-md border bg-transparent text-center font-mono text-lg",
            "outline-none transition-colors",
            "focus:border-primary focus:ring-2 focus:ring-primary/20",
            invalid
              ? "border-[color:var(--severity-err)]/60"
              : "border-border hover:border-primary/40",
            disabled && "cursor-not-allowed opacity-60"
          )}
        />
      ))}
    </div>
  );
}
