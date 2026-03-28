"use client";

import { useEffect, useRef, useState, useCallback } from "react";
import type { WebSocketEvent } from "./types";

const API_TOKEN = process.env.NEXT_PUBLIC_NIMBUS_API_TOKEN || "";

function buildWsUrl(path: string): string {
  if (typeof window === "undefined") return "";
  const proto = window.location.protocol === "https:" ? "wss:" : "ws:";
  const url = `${proto}//${window.location.host}${path}`;
  return API_TOKEN ? `${url}?token=${encodeURIComponent(API_TOKEN)}` : url;
}

const RECONNECT_BASE_MS = 1000;
const RECONNECT_MAX_MS = 30000;

export function useWebSocket() {
  const [connected, setConnected] = useState(false);
  const [events, setEvents] = useState<WebSocketEvent[]>([]);
  const wsRef = useRef<WebSocket | null>(null);
  const retriesRef = useRef(0);

  useEffect(() => {
    let mounted = true;
    let timer: ReturnType<typeof setTimeout>;

    function connect() {
      if (!mounted) return;

      const ws = new WebSocket(buildWsUrl("/api/events"));
      wsRef.current = ws;

      ws.onopen = () => {
        if (!mounted) return;
        setConnected(true);
        retriesRef.current = 0;
      };

      ws.onmessage = (msg) => {
        if (!mounted) return;
        try {
          const event: WebSocketEvent = JSON.parse(msg.data);
          setEvents((prev) => [...prev.slice(-99), event]);
        } catch {
          // ignore malformed messages
        }
      };

      ws.onclose = () => {
        if (!mounted) return;
        setConnected(false);
        wsRef.current = null;
        const delay = Math.min(
          RECONNECT_BASE_MS * 2 ** retriesRef.current,
          RECONNECT_MAX_MS,
        );
        retriesRef.current++;
        timer = setTimeout(connect, delay);
      };

      ws.onerror = () => ws.close();
    }

    connect();

    return () => {
      mounted = false;
      clearTimeout(timer);
      wsRef.current?.close();
    };
  }, []);

  return { connected, events };
}

export function useConsoleSocket(serviceName: string | null) {
  const [connected, setConnected] = useState(false);
  const [lines, setLines] = useState<string[]>([]);
  const wsRef = useRef<WebSocket | null>(null);
  const retriesRef = useRef(0);

  const send = useCallback((command: string) => {
    if (wsRef.current?.readyState === WebSocket.OPEN) {
      wsRef.current.send(command);
    }
  }, []);

  useEffect(() => {
    if (!serviceName) {
      setLines([]);
      setConnected(false);
      return;
    }

    let mounted = true;
    let timer: ReturnType<typeof setTimeout>;

    function connect() {
      if (!mounted) return;

      const path = `/api/services/${encodeURIComponent(serviceName!)}/console`;
      const ws = new WebSocket(buildWsUrl(path));
      wsRef.current = ws;

      ws.onopen = () => {
        if (!mounted) return;
        setConnected(true);
        retriesRef.current = 0;
      };

      ws.onmessage = (msg) => {
        if (!mounted) return;
        setLines((prev) => [...prev, msg.data]);
      };

      ws.onclose = () => {
        if (!mounted) return;
        setConnected(false);
        wsRef.current = null;
        const delay = Math.min(
          RECONNECT_BASE_MS * 2 ** retriesRef.current,
          RECONNECT_MAX_MS,
        );
        retriesRef.current++;
        timer = setTimeout(connect, delay);
      };

      ws.onerror = () => ws.close();
    }

    setLines([]);
    connect();

    return () => {
      mounted = false;
      clearTimeout(timer);
      wsRef.current?.close();
      wsRef.current = null;
    };
  }, [serviceName]);

  return { connected, lines, send };
}
