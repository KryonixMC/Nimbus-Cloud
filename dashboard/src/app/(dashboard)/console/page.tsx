"use client";

import { useEffect, useRef, useState } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { apiWebSocketReconnect } from "@/lib/api";
import { statusColors } from "@/lib/status";
import { AnsiLine } from "@/components/ansi-line";
import { Send } from "lucide-react";

let messageId = 0;

export default function ConsolePage() {
  const [lines, setLines] = useState<string[]>([]);
  const [command, setCommand] = useState("");
  const [connected, setConnected] = useState(false);
  const wsRef = useRef<WebSocket | null>(null);
  const endRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const { getSocket, cleanup } = apiWebSocketReconnect(
      "/api/console/stream",
      {
        onOpen: (ws) => {
          setConnected(true);
          wsRef.current = ws;
          ws.send(
            JSON.stringify({
              type: "hello",
              text: JSON.stringify({
                username: "dashboard",
                hostname: window.location.hostname,
                os: `${navigator.platform} (Browser)`,
              }),
            })
          );
        },
        onMessage: (event) => {
          try {
            const msg = JSON.parse(event.data);
            if (msg.type === "output" && msg.line) {
              setLines((prev) => {
                const next = [...prev, msg.line.text];
                return next.length > 1000 ? next.slice(-1000) : next;
              });
            } else if (msg.type === "event" && msg.event) {
              const evt = msg.event;
              const info = evt.data
                ? Object.entries(evt.data)
                    .map(([k, v]) => `${k}=${v}`)
                    .join(", ")
                : "";
              setLines((prev) => {
                const next = [...prev, `[event] ${evt.type} ${info}`];
                return next.length > 1000 ? next.slice(-1000) : next;
              });
            }
          } catch {
            setLines((prev) => {
              const next = [...prev, event.data];
              return next.length > 1000 ? next.slice(-1000) : next;
            });
          }
        },
        onClose: () => {
          setConnected(false);
        },
      }
    );

    return cleanup;
  }, []);

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [lines]);

  function sendCommand(e: React.FormEvent) {
    e.preventDefault();
    if (!command.trim() || !wsRef.current) return;
    const id = String(++messageId);
    // Server expects: { type: "execute", id: "...", input: "command" }
    wsRef.current.send(
      JSON.stringify({ type: "execute", id, input: command.trim() })
    );
    setLines((prev) => [...prev, `> ${command}`]);
    setCommand("");
  }

  return (
    <Card className="flex flex-col h-[calc(100vh-7rem)]">
      <CardHeader className="flex flex-row items-center justify-between">
        <CardTitle>Console</CardTitle>
        <Badge
          variant="outline"
          className={connected ? statusColors.online : statusColors.inactive}
        >
          {connected ? "Connected" : "Disconnected"}
        </Badge>
      </CardHeader>
      <CardContent className="flex flex-1 flex-col min-h-0">
        <div className="flex-1 overflow-y-auto rounded-md bg-black p-3 font-mono text-xs text-gray-300 scrollbar-thin">
          {lines.map((line, i) => (
            <AnsiLine key={i} text={line} />
          ))}
          <div ref={endRef} />
        </div>
        <form onSubmit={sendCommand} className="mt-2 flex items-center gap-2">
          <Input
            value={command}
            onChange={(e) => setCommand(e.target.value)}
            placeholder="Enter command..."
            className="font-mono"
            disabled={!connected}
          />
          <Button type="submit" size="icon" disabled={!connected}>
            <Send className="size-4" />
          </Button>
        </form>
      </CardContent>
    </Card>
  );
}
