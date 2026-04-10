"use client";

import { useEffect, useRef, useState } from "react";
import { apiFetch } from "@/lib/api";

export interface ServiceMetricPoint {
  time: string;
  memory: number;
  memoryMax: number;
  players: number;
}

interface ServiceMetricsResponse {
  memoryUsedMb: number;
  memoryMaxMb: number;
  playerCount: number;
}

interface ServiceMetricHistoryResponse {
  service: string;
  samples: Array<{
    timestamp: string;
    memoryUsedMb: number;
    memoryMaxMb: number;
    playerCount: number;
  }>;
}

function formatTime(date: Date): string {
  return date.toLocaleTimeString([], {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}

/**
 * Returns a rolling window of memory + player samples for a service.
 *
 * On mount it fetches the last [historyMinutes] minutes from the controller's
 * `/api/services/{name}/metrics/history` endpoint (which reads from the
 * service_metric_samples DB table written by MetricsCollector). Then it keeps
 * polling the live `/api/services/{name}` endpoint so that new samples are
 * appended in real time without the user having to refresh.
 */
export function useServiceMetrics(
  serviceName: string,
  { intervalMs = 5000, historyMinutes = 60, maxPoints = 240 } = {}
) {
  const [metrics, setMetrics] = useState<ServiceMetricPoint[]>([]);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    let cancelled = false;

    // 1. Load history once.
    apiFetch<ServiceMetricHistoryResponse>(
      `/api/services/${serviceName}/metrics/history?minutes=${historyMinutes}`
    )
      .then((data) => {
        if (cancelled) return;
        setMetrics(
          data.samples.map((s) => ({
            time: formatTime(new Date(s.timestamp)),
            memory: s.memoryUsedMb,
            memoryMax: s.memoryMaxMb,
            players: s.playerCount,
          }))
        );
      })
      .catch(() => {
        // History is best-effort — empty start is fine, live polling will fill in.
      });

    // 2. Start live polling for new samples.
    function fetchLive() {
      apiFetch<ServiceMetricsResponse>(`/api/services/${serviceName}`)
        .then((data) => {
          if (cancelled) return;
          const point: ServiceMetricPoint = {
            time: formatTime(new Date()),
            memory: data.memoryUsedMb ?? 0,
            memoryMax: data.memoryMaxMb ?? 0,
            players: data.playerCount ?? 0,
          };
          setMetrics((prev) => {
            const next = [...prev, point];
            return next.length > maxPoints ? next.slice(-maxPoints) : next;
          });
        })
        .catch(() => {});
    }

    pollRef.current = setInterval(fetchLive, intervalMs);
    return () => {
      cancelled = true;
      if (pollRef.current) clearInterval(pollRef.current);
    };
  }, [serviceName, intervalMs, historyMinutes, maxPoints]);

  return metrics;
}
