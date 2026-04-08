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
  CardTitle,
} from "@/components/ui/card";
import {
  Field,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { setCredentials } from "@/lib/api";

export function LoginForm({
  className,
  ...props
}: React.ComponentProps<"div">) {
  const [apiUrl, setApiUrl] = useState("http://localhost:8080");
  const [token, setToken] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const router = useRouter();

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError("");
    setLoading(true);

    try {
      const url = apiUrl.replace(/\/+$/, "");
      const res = await fetch(`${url}/api/status`, {
        headers: { Authorization: `Bearer ${token}` },
      });

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
            <Image src="/icon.png" alt="Nimbus" width={48} height={48} />
          </div>
          <CardTitle>Nimbus Dashboard</CardTitle>
          <CardDescription>
            Connect to your Nimbus controller
          </CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={handleSubmit}>
            <FieldGroup>
              <Field>
                <FieldLabel htmlFor="apiUrl">Controller URL</FieldLabel>
                <Input
                  id="apiUrl"
                  type="url"
                  placeholder="http://localhost:8080"
                  value={apiUrl}
                  onChange={(e) => setApiUrl(e.target.value)}
                  required
                />
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
