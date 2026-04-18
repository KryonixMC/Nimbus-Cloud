"use client";

import { useEffect, useRef, useState } from "react";
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
import { Field, FieldGroup, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import {
  setApiTokenCredentials,
  setUserSessionCredentials,
  type UserInfo,
} from "@/lib/api";
import { buildControllerUrl, controllerFetch } from "@/lib/controller-url";
import { ArrowLeft } from "@/lib/icons";

type Screen =
  | "connect"
  | "method"
  | "mc-method"
  | "code"
  | "magic-link"
  | "api-token"
  | "totp";

type McMethod = "code" | "magic-link";

interface ConsumeChallengeResponse {
  token?: string;
  expiresAt?: number;
  user?: UserInfo;
  totpRequired?: boolean;
  challengeId?: string;
}

interface TotpVerifyResponse {
  token: string;
  expiresAt: number;
  user: UserInfo;
}

interface ApiErrorBody {
  success?: boolean;
  message?: string;
  error?: string;
}

async function readError(res: Response, fallback: string): Promise<string> {
  const body: ApiErrorBody = await res.json().catch(() => ({}));
  return body.message || body.error || `${fallback} (${res.status})`;
}

function friendlyNetworkError(err: unknown): string {
  const isNetworkError =
    err instanceof TypeError &&
    (err.message === "Failed to fetch" ||
      err.message.includes("NetworkError"));
  return isNetworkError
    ? "Could not reach the Nimbus controller. Check that the address is correct, the controller is running, and the API port is open."
    : "Could not connect to Nimbus controller";
}

/**
 * Step-based login flow. Only one screen is rendered at a time; the screen
 * state machine acts as implicit routing without touching Next's router.
 *
 * Flow:
 *   connect → method → {mc-method → {code|magic-link} | api-token}
 *   code|magic-link → (optional) totp
 */
export function LoginForm({
  className,
  ...props
}: React.ComponentProps<"div">) {
  const router = useRouter();

  const [screen, setScreen] = useState<Screen>("connect");
  const [host, setHost] = useState("");
  const [resolvedUrl, setResolvedUrl] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  // API-token screen state
  const [apiToken, setApiToken] = useState("");

  // Code screen state
  const [mcCode, setMcCode] = useState("");

  // Magic-link screen state
  const [mcName, setMcName] = useState("");
  const [linkSent, setLinkSent] = useState(false);
  const [linkTtl, setLinkTtl] = useState(0);
  const linkTimer = useRef<ReturnType<typeof setInterval> | null>(null);

  // TOTP state
  const [totpCode, setTotpCode] = useState("");
  const [challengeId, setChallengeId] = useState<string | null>(null);
  const [lastMcMethod, setLastMcMethod] = useState<McMethod>("code");

  useEffect(() => {
    return () => {
      if (linkTimer.current) clearInterval(linkTimer.current);
    };
  }, []);

  function go(next: Screen) {
    setError("");
    setScreen(next);
  }

  function back() {
    setError("");
    switch (screen) {
      case "method":
        setScreen("connect");
        break;
      case "mc-method":
        setScreen("method");
        break;
      case "code":
        setScreen("mc-method");
        break;
      case "magic-link":
        setScreen("mc-method");
        break;
      case "api-token":
        setScreen("method");
        break;
      case "totp":
        setScreen(lastMcMethod === "magic-link" ? "magic-link" : "code");
        break;
      default:
        break;
    }
  }

  function startLinkCountdown(ttlSeconds: number) {
    setLinkTtl(ttlSeconds);
    if (linkTimer.current) clearInterval(linkTimer.current);
    linkTimer.current = setInterval(() => {
      setLinkTtl((t) => {
        if (t <= 1) {
          if (linkTimer.current) clearInterval(linkTimer.current);
          setLinkSent(false);
          return 0;
        }
        return t - 1;
      });
    }, 1000);
  }

  // ---- actions ---------------------------------------------------------

  async function handleConnect(e: React.FormEvent) {
    e.preventDefault();
    setError("");
    setLoading(true);
    try {
      const url = buildControllerUrl(host);
      const res = await controllerFetch(url, "/api/status");
      // /api/status requires auth — any response (incl. 401) proves the
      // controller is reachable. Treat a non-5xx/non-network result as OK.
      if (res.status >= 500) {
        setError(`Controller error (${res.status}). Try again.`);
        return;
      }
      setResolvedUrl(url);
      go("method");
    } catch (err) {
      setError(friendlyNetworkError(err));
    } finally {
      setLoading(false);
    }
  }

  async function handleCodeSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError("");
    setLoading(true);
    try {
      const res = await controllerFetch(
        resolvedUrl,
        "/api/auth/consume-challenge",
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ challenge: mcCode.trim() }),
        }
      );
      if (!res.ok) {
        setError(await readError(res, "Login failed"));
        return;
      }
      const body: ConsumeChallengeResponse = await res.json();
      if (body.totpRequired && body.challengeId) {
        setChallengeId(body.challengeId);
        setLastMcMethod("code");
        setTotpCode("");
        go("totp");
        return;
      }
      if (body.token) {
        setUserSessionCredentials(resolvedUrl, body.token);
        router.push("/");
        return;
      }
      setError("Unexpected response from controller");
    } catch (err) {
      setError(friendlyNetworkError(err));
    } finally {
      setLoading(false);
    }
  }

  async function handleSendMagicLink(e: React.FormEvent) {
    e.preventDefault();
    if (linkSent) return;
    setError("");
    setLoading(true);
    try {
      const res = await controllerFetch(
        resolvedUrl,
        "/api/auth/deliver-magic-link",
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ name: mcName.trim() }),
        }
      );
      if (res.status === 404) {
        setError("You need to be online on a Nimbus server first.");
        return;
      }
      if (res.status === 403) {
        setError("Magic link login is disabled on this network.");
        return;
      }
      if (!res.ok) {
        setError(await readError(res, "Could not send magic link"));
        return;
      }
      const body = await res.json().catch(() => ({} as { ttlSeconds?: number }));
      const ttl = typeof body.ttlSeconds === "number" ? body.ttlSeconds : 60;
      setLastMcMethod("magic-link");
      setLinkSent(true);
      startLinkCountdown(ttl);
    } catch (err) {
      setError(friendlyNetworkError(err));
    } finally {
      setLoading(false);
    }
  }

  async function handleApiTokenSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError("");
    setLoading(true);
    try {
      const res = await controllerFetch(resolvedUrl, "/api/status", {
        headers: { Authorization: `Bearer ${apiToken}` },
      });
      if (res.status === 401) {
        setError("Invalid API token");
        return;
      }
      if (!res.ok) {
        setError(`Connection failed (${res.status})`);
        return;
      }
      setApiTokenCredentials(resolvedUrl, apiToken);
      router.push("/");
    } catch (err) {
      setError(friendlyNetworkError(err));
    } finally {
      setLoading(false);
    }
  }

  async function handleTotpSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!challengeId) return;
    setError("");
    setLoading(true);
    try {
      const res = await controllerFetch(
        resolvedUrl,
        "/api/auth/totp-verify",
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ challengeId, code: totpCode.trim() }),
        }
      );
      if (!res.ok) {
        setError(await readError(res, "Invalid TOTP code"));
        return;
      }
      const body: TotpVerifyResponse = await res.json();
      setUserSessionCredentials(resolvedUrl, body.token);
      router.push("/");
    } catch (err) {
      setError(friendlyNetworkError(err));
    } finally {
      setLoading(false);
    }
  }

  // ---- rendering -------------------------------------------------------

  const animated =
    "animate-in fade-in-0 duration-200";

  const showBack = screen !== "connect";

  const description =
    screen === "connect"
      ? "Connect to your Nimbus controller"
      : screen === "totp"
        ? "Two-factor authentication"
        : undefined;

  return (
    <div className={cn("flex flex-col gap-6", className)} {...props}>
      <Card className="relative">
        {showBack && (
          <button
            type="button"
            onClick={back}
            aria-label="Back"
            className="absolute left-3 top-3 inline-flex size-8 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
          >
            <ArrowLeft className="size-4" />
          </button>
        )}

        <CardHeader className="text-center">
          <div className="mb-2 flex justify-center">
            <Image
              src="/icon.png"
              alt="Nimbus"
              width={64}
              height={64}
              priority
              className="h-16 w-16"
            />
          </div>
          {description && <CardDescription>{description}</CardDescription>}
          {screen !== "connect" && resolvedUrl && (
            <p className="mt-1 truncate text-xs text-muted-foreground">
              Connected to{" "}
              <span className="font-mono">{resolvedUrl}</span>
            </p>
          )}
        </CardHeader>

        <CardContent>
          {screen === "connect" && (
            <form onSubmit={handleConnect} className={animated}>
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
                    autoFocus
                  />
                  <p className="mt-1 text-xs text-muted-foreground">
                    Port defaults to 8080 if not specified
                  </p>
                </Field>
                {error && (
                  <p
                    role="alert"
                    className="text-sm text-[color:var(--severity-err)]"
                  >
                    {error}
                  </p>
                )}
                <Field>
                  <Button type="submit" className="w-full" disabled={loading}>
                    {loading ? "Connecting…" : "Continue"}
                  </Button>
                </Field>
              </FieldGroup>
            </form>
          )}

          {screen === "method" && (
            <div className={cn("flex flex-col gap-3", animated)}>
              <MethodCard
                title="Minecraft Account"
                description="Sign in with an in-game code or a magic link."
                primary
                icon={
                  /* eslint-disable-next-line @next/next/no-img-element */
                  <img
                    src="https://mc-heads.net/avatar/MHF_Alex/64"
                    alt=""
                    width={40}
                    height={40}
                    className="rounded-sm"
                    loading="lazy"
                  />
                }
                onClick={() => go("mc-method")}
              />
              <MethodCard
                title="API Token"
                description="Use a long-lived controller token."
                icon={
                  /* Custom head from minecraft-heads.com #120843 (Command Block).
                     Rendered via mc-heads.net using the textures.minecraft.net
                     hash — MHF_CommandBlock renders as a plain head on mc-heads,
                     so we go through the real custom-head texture instead. */
                  /* eslint-disable-next-line @next/next/no-img-element */
                  <img
                    src="https://mc-heads.net/avatar/eb6cee8fda7ef0b3ae0eb0579d5676ce36af7efc574d88728f3894f6b166538/64"
                    alt=""
                    width={40}
                    height={40}
                    className="rounded-sm"
                    loading="lazy"
                  />
                }
                onClick={() => go("api-token")}
              />
            </div>
          )}

          {screen === "mc-method" && (
            <div className={cn("flex flex-col gap-3", animated)}>
              <MethodCard
                title="Login code"
                description="Type /nimbus dashboard login on any Nimbus server."
                primary
                onClick={() => go("code")}
              />
              <MethodCard
                title="Magic link ✨"
                description="Get a clickable sign-in link in your in-game chat."
                onClick={() => go("magic-link")}
              />
            </div>
          )}

          {screen === "code" && (
            <form onSubmit={handleCodeSubmit} className={animated}>
              <FieldGroup>
                <Field>
                  <FieldLabel htmlFor="mc-code">Login code</FieldLabel>
                  <Input
                    id="mc-code"
                    type="text"
                    inputMode="numeric"
                    maxLength={6}
                    placeholder="123456"
                    value={mcCode}
                    onChange={(e) => setMcCode(e.target.value)}
                    required
                    autoComplete="one-time-code"
                    autoFocus
                  />
                  <p className="mt-1 text-xs text-muted-foreground">
                    Code not working? Make sure you typed{" "}
                    <code>/nimbus dashboard login</code> in-game.
                  </p>
                </Field>
                {error && (
                  <p
                    role="alert"
                    className="text-sm text-[color:var(--severity-err)]"
                  >
                    {error}
                  </p>
                )}
                <Field>
                  <Button type="submit" className="w-full" disabled={loading}>
                    {loading ? "Signing in…" : "Sign in"}
                  </Button>
                </Field>
              </FieldGroup>
            </form>
          )}

          {screen === "magic-link" && (
            <form onSubmit={handleSendMagicLink} className={animated}>
              <FieldGroup>
                {!linkSent ? (
                  <Field>
                    <FieldLabel htmlFor="mc-name">
                      Minecraft username
                    </FieldLabel>
                    <Input
                      id="mc-name"
                      type="text"
                      placeholder="Notch"
                      value={mcName}
                      onChange={(e) => setMcName(e.target.value)}
                      required
                      autoFocus
                    />
                  </Field>
                ) : (
                  <div className="flex flex-col items-center gap-2 py-4 text-center">
                    <p className="text-sm">Check your in-game chat ✨</p>
                    <p className="text-xs text-muted-foreground">
                      Link expires in {linkTtl}s
                    </p>
                  </div>
                )}
                {error && (
                  <div className="flex flex-col gap-2">
                    <p
                      role="alert"
                      className="text-sm text-[color:var(--severity-err)]"
                    >
                      {error}
                    </p>
                    {error.includes("disabled") && (
                      <button
                        type="button"
                        onClick={() => go("code")}
                        className="text-left text-xs text-muted-foreground underline underline-offset-4 hover:text-foreground"
                      >
                        Use code instead
                      </button>
                    )}
                  </div>
                )}
                <Field>
                  {linkSent && linkTtl === 0 ? (
                    <Button
                      type="button"
                      className="w-full"
                      onClick={() => {
                        setLinkSent(false);
                        setError("");
                      }}
                    >
                      Send another link
                    </Button>
                  ) : (
                    <Button
                      type="submit"
                      className="w-full"
                      disabled={loading || linkSent}
                    >
                      {loading
                        ? "Sending…"
                        : linkSent
                          ? `Link sent (${linkTtl}s)`
                          : "Send link"}
                    </Button>
                  )}
                </Field>
              </FieldGroup>
            </form>
          )}

          {screen === "api-token" && (
            <form onSubmit={handleApiTokenSubmit} className={animated}>
              <FieldGroup>
                <Field>
                  <FieldLabel htmlFor="token">API Token</FieldLabel>
                  <Input
                    id="token"
                    type="password"
                    placeholder="Enter your API token"
                    value={apiToken}
                    onChange={(e) => setApiToken(e.target.value)}
                    required
                    autoFocus
                  />
                </Field>
                {error && (
                  <p
                    role="alert"
                    className="text-sm text-[color:var(--severity-err)]"
                  >
                    {error}
                  </p>
                )}
                <Field>
                  <Button type="submit" className="w-full" disabled={loading}>
                    {loading ? "Connecting…" : "Sign in"}
                  </Button>
                </Field>
              </FieldGroup>
            </form>
          )}

          {screen === "totp" && (
            <form onSubmit={handleTotpSubmit} className={animated}>
              <FieldGroup>
                <Field>
                  <FieldLabel htmlFor="totp">Authenticator code</FieldLabel>
                  <Input
                    id="totp"
                    type="text"
                    inputMode="numeric"
                    autoComplete="one-time-code"
                    placeholder="6-digit code or recovery code"
                    value={totpCode}
                    onChange={(e) => setTotpCode(e.target.value)}
                    required
                    autoFocus
                  />
                  <p className="mt-1 text-xs text-muted-foreground">
                    Lost your device? Use a recovery code.
                  </p>
                </Field>
                {error && (
                  <p
                    role="alert"
                    className="text-sm text-[color:var(--severity-err)]"
                  >
                    {error}
                  </p>
                )}
                <Field>
                  <Button type="submit" className="w-full" disabled={loading}>
                    {loading ? "Verifying…" : "Verify"}
                  </Button>
                </Field>
              </FieldGroup>
            </form>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

function MethodCard({
  title,
  description,
  primary,
  onClick,
  icon,
}: {
  title: string;
  description: string;
  primary?: boolean;
  onClick: () => void;
  icon?: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        "group flex w-full items-center gap-3 rounded-lg border p-4 text-left transition-colors",
        primary
          ? "border-primary/40 bg-primary/5 hover:border-primary hover:bg-primary/10"
          : "border-border hover:bg-muted"
      )}
    >
      {icon && <div className="shrink-0">{icon}</div>}
      <div className="flex min-w-0 flex-col gap-0.5">
        <span className="font-medium">{title}</span>
        <span className="text-xs text-muted-foreground">{description}</span>
      </div>
    </button>
  );
}

