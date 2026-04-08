"use client";

import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { apiFetch } from "./api";

export interface ModuleInfo {
  id: string;
  name: string;
  version: string | null;
  description: string;
  status: string;
  dashboard: {
    icon: string;
    apiPrefix: string;
    sections: { title: string; type: string; endpoint: string }[];
  } | null;
}

interface ModulesContextType {
  modules: ModuleInfo[];
  activeModules: ModuleInfo[];
  loading: boolean;
}

const ModulesContext = createContext<ModulesContextType>({
  modules: [],
  activeModules: [],
  loading: true,
});

export function ModulesProvider({ children }: { children: ReactNode }) {
  const [modules, setModules] = useState<ModuleInfo[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    apiFetch<{ modules: ModuleInfo[] }>("/api/modules")
      .then((data) => setModules(data.modules))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  const activeModules = modules.filter((m) => m.status === "active" && m.dashboard);

  return (
    <ModulesContext.Provider value={{ modules, activeModules, loading }}>
      {children}
    </ModulesContext.Provider>
  );
}

export function useModules() {
  return useContext(ModulesContext);
}
