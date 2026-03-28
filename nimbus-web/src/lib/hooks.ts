"use client";

import { useState, useEffect, useCallback, useRef } from "react";
import {
  getStatus,
  getServices,
  getGroups,
  getPlayers,
  getConfig,
} from "./api";
import type {
  StatusResponse,
  ConfigResponse,
} from "./types";

interface UseQueryResult<T> {
  data: T | null;
  error: Error | null;
  loading: boolean;
  refetch: () => void;
}

function usePollingQuery<T>(
  fetcher: () => Promise<T>,
  intervalMs: number,
  deps: unknown[] = [],
): UseQueryResult<T> {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const [loading, setLoading] = useState(true);
  const mountedRef = useRef(true);

  const execute = useCallback(async () => {
    try {
      const result = await fetcher();
      if (mountedRef.current) {
        setData(result);
        setError(null);
      }
    } catch (e) {
      if (mountedRef.current) {
        setError(e instanceof Error ? e : new Error(String(e)));
      }
    } finally {
      if (mountedRef.current) {
        setLoading(false);
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  useEffect(() => {
    mountedRef.current = true;
    setLoading(true);
    execute();

    const timer = setInterval(execute, intervalMs);
    return () => {
      mountedRef.current = false;
      clearInterval(timer);
    };
  }, [execute, intervalMs]);

  return { data, error, loading, refetch: execute };
}

export function useStatus(): UseQueryResult<StatusResponse> {
  return usePollingQuery(() => getStatus(), 5000);
}

export function useServices(
  group?: string,
  state?: string,
): UseQueryResult<{ services: any[]; total: number }> {
  return usePollingQuery(() => getServices(group, state), 10000, [group, state]);
}

export function useGroups(): UseQueryResult<{ groups: any[]; total: number }> {
  return usePollingQuery(() => getGroups(), 10000);
}

export function usePlayers(): UseQueryResult<{ players: any[]; total: number }> {
  return usePollingQuery(() => getPlayers(), 10000);
}

export function useConfig(): UseQueryResult<ConfigResponse> {
  return usePollingQuery(() => getConfig(), 30000);
}
