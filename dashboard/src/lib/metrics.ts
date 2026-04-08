"use client";

import { useEffect, useRef, useState } from "react";
import { apiFetch } from "@/lib/api";

interface ServiceMetricPoint {
  time: string;
  tps: number;
  memory: number;
}

interface ServiceMetricsResponse {
  tps: number;
  memoryUsedMb: number;
}

export function useServiceMetrics(serviceName: string, intervalMs = 5000) {
  const [metrics, setMetrics] = useState<ServiceMetricPoint[]>([]);
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    function fetchMetric() {
      apiFetch<ServiceMetricsResponse>(`/api/services/${serviceName}`)
        .then((data) => {
          const point: ServiceMetricPoint = {
            time: new Date().toLocaleTimeString([], {
              hour: "2-digit",
              minute: "2-digit",
              second: "2-digit",
            }),
            tps: data.tps ?? 0,
            memory: data.memoryUsedMb ?? 0,
          };
          setMetrics((prev) => {
            const next = [...prev, point];
            return next.length > 60 ? next.slice(-60) : next;
          });
        })
        .catch(() => {});
    }

    fetchMetric();
    intervalRef.current = setInterval(fetchMetric, intervalMs);
    return () => {
      if (intervalRef.current) clearInterval(intervalRef.current);
    };
  }, [serviceName, intervalMs]);

  return metrics;
}
