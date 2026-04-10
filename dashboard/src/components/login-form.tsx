"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import Image from "next/image";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
} from "@/components/ui/card";
import {
  Field,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { setCredentials } from "@/lib/api";

/**
 * Parse a host input (IP/hostname with optional port) into a full URL.
 * Examples:
 *   "152.53.124.143"       → "http://152.53.124.143:8080"
 *   "152.53.124.143:9090"  → "http://152.53.124.143:9090"
 *   "my.server.com"        → "http://my.server.com:8080"
 *   "my.server.com:443"    → "http://my.server.com:443"
 */
function buildControllerUrl(host: string): string {
  const trimmed = host.trim().replace(/\/+$/, "");

  // If the user already typed a full URL, use it as-is
  if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
    return trimmed;
  }

  // Check if a port is included (handle IPv6 bracket notation too)
  const hasPort = trimmed.includes("]:") || (!trimmed.startsWith("[") && trimmed.includes(":"));
  if (hasPort) {
    return `http://${trimmed}`;
  }

  return `http://${trimmed}:8080`;
}

export function LoginForm({
  className,
  ...props
}: React.ComponentProps<"div">) {
  const [host, setHost] = useState("");
  const [token, setToken] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const router = useRouter();

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError("");
    setLoading(true);

    try {
      const url = buildControllerUrl(host);

      // Determine if we need to proxy (HTTPS dashboard -> HTTP controller)
      const dashboardHttps = window.location.protocol === "https:";
      const controllerHttp = url.startsWith("http://");
      const useProxy = dashboardHttps && controllerHttp;

      let res: Response;
      if (useProxy) {
        res = await fetch("/api/proxy/api/status", {
          headers: {
            "X-Nimbus-Controller": url,
            "X-Nimbus-Token": token,
          },
        });
      } else {
        res = await fetch(`${url}/api/status`, {
          headers: { Authorization: `Bearer ${token}` },
        });
      }

      if (res.status === 401) {
        setError("Invalid API token");
        return;
      }
      if (!res.ok) {
        setError(`Connection failed (${res.status})`);
        return;
      }

      setCredentials(url, token);
      router.push("/");
    } catch {
      setError("Could not connect to Nimbus controller");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className={cn("flex flex-col gap-6", className)} {...props}>
      <Card>
        <CardHeader className="text-center">
          <div className="flex justify-center mb-2">
            <Image
              src="/banner.svg"
              alt="Nimbus"
              width={320}
              height={88}
              priority
              className="h-auto w-[320px]"
            />
          </div>
          <CardDescription>
            Connect to your Nimbus controller
          </CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={handleSubmit}>
            <FieldGroup>
              <Field>
                <FieldLabel htmlFor="host">Controller Address</FieldLabel>
                <Input
                  id="host"
                  type="text"
                  placeholder="IP or hostname (e.g. 192.168.1.100)"
                  value={host}
                  onChange={(e) => setHost(e.target.value)}
                  required
                />
                <p className="text-xs text-muted-foreground mt-1">
                  Port defaults to 8080 if not specified
                </p>
              </Field>
              <Field>
                <FieldLabel htmlFor="token">API Token</FieldLabel>
                <Input
                  id="token"
                  type="password"
                  placeholder="Enter your API token"
                  value={token}
                  onChange={(e) => setToken(e.target.value)}
                  required
                />
              </Field>
              {error && (
                <p className="text-sm text-destructive">{error}</p>
              )}
              <Field>
                <Button type="submit" className="w-full" disabled={loading}>
                  {loading ? "Connecting..." : "Connect"}
                </Button>
              </Field>
            </FieldGroup>
          </form>
        </CardContent>
      </Card>
    </div>
  );
}
