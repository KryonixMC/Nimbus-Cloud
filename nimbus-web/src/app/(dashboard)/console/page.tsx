"use client";

import { useState, useRef, useEffect } from "react";
import { useServices } from "@/lib/hooks";
import { useConsoleSocket } from "@/lib/websocket";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Terminal, Send } from "lucide-react";

export default function ConsolePage() {
  const { data: serviceData } = useServices();
  const [selectedService, setSelectedService] = useState("");
  const [command, setCommand] = useState("");
  const scrollRef = useRef<HTMLDivElement>(null);

  const services = serviceData?.services?.filter((s) => s.state === "READY") ?? [];
  const { lines, connected, send } = useConsoleSocket(selectedService || null);

  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [lines]);

  function handleSend() {
    if (!command.trim()) return;
    send(command.trim());
    setCommand("");
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">Console</h1>
        <p className="text-muted-foreground">Live server console access</p>
      </div>

      <div className="flex items-center gap-4">
        <Select value={selectedService} onValueChange={(v) => setSelectedService(v ?? "")}>
          <SelectTrigger className="w-[250px]">
            <SelectValue placeholder="Select a service" />
          </SelectTrigger>
          <SelectContent>
            {services.map((s) => (
              <SelectItem key={s.name} value={s.name}>
                {s.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        {selectedService && (
          <Badge variant={connected ? "default" : "secondary"}>
            {connected ? "Connected" : "Disconnected"}
          </Badge>
        )}
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Terminal className="h-5 w-5" />
            {selectedService || "Select a service"}
          </CardTitle>
        </CardHeader>
        <CardContent>
          <div
            ref={scrollRef}
            className="h-[500px] overflow-y-auto rounded-md border bg-black p-4 font-mono text-sm text-green-400"
          >
            {!selectedService ? (
              <p className="text-muted-foreground">Select a service to view its console</p>
            ) : lines.length === 0 ? (
              <p className="text-muted-foreground">
                {connected ? "Waiting for output..." : "Connecting..."}
              </p>
            ) : (
              lines.map((line, i) => (
                <div key={i} className="whitespace-pre-wrap break-all">
                  {line}
                </div>
              ))
            )}
          </div>
          {selectedService && (
            <div className="mt-4 flex gap-2">
              <Input
                placeholder="Enter command..."
                value={command}
                onChange={(e) => setCommand(e.target.value)}
                onKeyDown={(e) => e.key === "Enter" && handleSend()}
                className="font-mono"
                disabled={!connected}
              />
              <Button onClick={handleSend} disabled={!connected || !command.trim()}>
                <Send className="mr-2 h-4 w-4" />
                Send
              </Button>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
