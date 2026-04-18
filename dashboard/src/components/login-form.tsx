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
import { ArrowLeft, CircleCheck, Loader2 } from "@/lib/icons";

type Screen =
  | "connect"
  | "method"
  | "mc-method"
  | "code"
  | "magic-link"
  | "api-token"
  | "totp";

type McMethod = "code" | "magic-link";
type Direction = "forward" | "back";

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
  const [direction, setDirection] = useState<Direction>("forward");
  const [host, setHost] = useState("");
  const [resolvedUrl, setResolvedUrl] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [connectSuccess, setConnectSuccess] = useState(false);

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
    setDirection("forward");
    setScreen(next);
  }

  function back() {
    setError("");
    setDirection("back");
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
      setConnectSuccess(true);
      // Briefly show the success check before we transition — premium feel,
      // not a full success screen.
      window.setTimeout(() => {
        setConnectSuccess(false);
        go("method");
      }, 320);
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

  // Screen-swap animation. Direction-aware: forward slides in from the right,
  // back from the left. Keyed by `screen` so React re-mounts and re-runs the
  // CSS animation on every transition.
  const animated = cn(
    "animate-in fade-in-0 duration-200",
    direction === "forward"
      ? "slide-in-from-right-2"
      : "slide-in-from-left-2"
  );

  const errorAnim =
    "animate-in fade-in-0 slide-in-from-top-1 duration-200";

  const showBack = screen !== "connect";

  // Per-screen copy — warmer than the old generic descriptions.
  const heading =
    screen === "connect"
      ? "Let's get you signed in"
      : screen === "method"
        ? "How would you like to sign in?"
        : screen === "mc-method"
          ? "Pick your preferred sign-in"
          : screen === "code"
            ? "Enter your six-digit code"
            : screen === "magic-link"
              ? linkSent
                ? "Check your chat"
                : "Get a magic link in-game"
              : screen === "api-token"
                ? "Paste your controller token"
                : screen === "totp"
                  ? "Enter your authenticator code"
                  : "";

  const subheading =
    screen === "connect"
      ? "Where does your Nimbus controller live?"
      : screen === "code"
        ? "Grab it in-game with /nimbus dashboard login"
        : undefined;

  return (
    <div className={cn("flex flex-col gap-6", className)} {...props}>
      <Card
        className={cn(
          "relative rounded-2xl p-1 shadow-xl shadow-primary/10",
          "ring-1 ring-primary/10 transition-shadow duration-300",
          "focus-within:ring-primary/25 focus-within:shadow-primary/15 hover:ring-primary/20"
        )}
      >
        {showBack && (
          <button
            type="button"
            onClick={back}
            aria-label="Back"
            className="absolute left-3 top-3 z-10 inline-flex size-9 items-center justify-center rounded-full text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
          >
            <ArrowLeft className="size-4" />
          </button>
        )}

        <CardHeader className="px-6 pt-6 text-center">
          <div className="mb-3 flex justify-center">
            <Image
              src="/icon.png"
              alt="Nimbus"
              width={64}
              height={64}
              priority
              // Skip Next's image optimizer: the source PNG already has
              // very few distinct colours (~227), re-encoding it at q=75
              // adds visible fuzz on the logo's flat-colour edges.
              unoptimized
              quality={100}
              className="h-16 w-16 drop-shadow-[0_4px_16px_rgba(88,166,255,0.25)]"
            />
          </div>
          {heading && (
            <h1
              key={`h-${screen}-${linkSent}`}
              className={cn(
                "text-lg font-semibold tracking-tight",
                animated
              )}
            >
              {heading}
            </h1>
          )}
          {subheading && (
            <CardDescription className="mt-1">{subheading}</CardDescription>
          )}
          {screen !== "connect" && resolvedUrl && (
            <p className="mt-1 truncate text-xs text-muted-foreground">
              Connected to{" "}
              <span className="font-mono">{resolvedUrl}</span>
            </p>
          )}
        </CardHeader>

        <CardContent className="px-6 pb-7">
          {screen === "connect" && (
            <form
              key="s-connect"
              onSubmit={handleConnect}
              className={animated}
            >
              <FieldGroup>
                <Field>
                  <FieldLabel htmlFor="host">Controller Address</FieldLabel>
                  <div className="relative">
                    <Input
                      id="host"
                      type="text"
                      placeholder="IP or hostname (e.g. 192.168.1.100)"
                      value={host}
                      onChange={(e) => setHost(e.target.value)}
                      required
                      autoFocus
                      className={cn(
                        "pr-9 transition-colors",
                        connectSuccess &&
                          "border-[color:var(--severity-ok)]/60"
                      )}
                    />
                    {connectSuccess && (
                      <CircleCheck
                        className="absolute right-2.5 top-1/2 size-5 -translate-y-1/2 text-[color:var(--severity-ok)] animate-in fade-in-0 zoom-in-50 duration-200"
                        aria-hidden
                      />
                    )}
                  </div>
                  <p className="mt-1 text-xs text-muted-foreground">
                    Port defaults to 8080 if not specified
                  </p>
                </Field>
                {error && (
                  <p
                    role="alert"
                    className={cn(
                      "text-sm text-[color:var(--severity-err)]",
                      errorAnim
                    )}
                  >
                    {error}
                  </p>
                )}
                <Field>
                  <SubmitButton
                    loading={loading}
                    label="Continue"
                    loadingLabel="Connecting…"
                  />
                </Field>
              </FieldGroup>
            </form>
          )}

          {screen === "method" && (
            <div
              key="s-method"
              className={cn("flex flex-col gap-3", animated)}
            >
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
                    className="rounded-sm transition-transform duration-200 group-hover:scale-110"
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
                    className="rounded-sm transition-transform duration-200 group-hover:scale-110"
                    loading="lazy"
                  />
                }
                onClick={() => go("api-token")}
              />
            </div>
          )}

          {screen === "mc-method" && (
            <div
              key="s-mc-method"
              className={cn("flex flex-col gap-3", animated)}
            >
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
            <form
              key="s-code"
              onSubmit={handleCodeSubmit}
              className={animated}
            >
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
                    className="text-center font-mono text-lg tracking-[0.4em]"
                  />
                  <p className="mt-1 text-xs text-muted-foreground">
                    Code not working? Make sure you typed{" "}
                    <code>/nimbus dashboard login</code> in-game.
                  </p>
                </Field>
                {error && (
                  <p
                    role="alert"
                    className={cn(
                      "text-sm text-[color:var(--severity-err)]",
                      errorAnim
                    )}
                  >
                    {error}
                  </p>
                )}
                <Field>
                  <SubmitButton
                    loading={loading}
                    label="Sign in"
                    loadingLabel="Signing in…"
                  />
                </Field>
              </FieldGroup>
            </form>
          )}

          {screen === "magic-link" && (
            <form
              key={`s-link-${linkSent}`}
              onSubmit={handleSendMagicLink}
              className={animated}
            >
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
                  <div className="flex flex-col items-center gap-3 py-4 text-center">
                    <span
                      aria-hidden
                      className="nimbus-sparkle text-3xl"
                    >
                      ✨
                    </span>
                    <p className="text-sm">Check your in-game chat</p>
                    <p className="text-xs text-muted-foreground/80">
                      Link expires in {linkTtl}s
                    </p>
                  </div>
                )}
                {error && (
                  <div className={cn("flex flex-col gap-2", errorAnim)}>
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
                      className="h-10 w-full animate-in fade-in-0 duration-200"
                      onClick={() => {
                        setLinkSent(false);
                        setError("");
                      }}
                    >
                      Send another link
                    </Button>
                  ) : (
                    <SubmitButton
                      loading={loading}
                      disabled={linkSent}
                      label={linkSent ? `Link sent (${linkTtl}s)` : "Send link"}
                      loadingLabel="Sending…"
                    />
                  )}
                </Field>
              </FieldGroup>
            </form>
          )}

          {screen === "api-token" && (
            <form
              key="s-token"
              onSubmit={handleApiTokenSubmit}
              className={animated}
            >
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
                    className={cn(
                      "text-sm text-[color:var(--severity-err)]",
                      errorAnim
                    )}
                  >
                    {error}
                  </p>
                )}
                <Field>
                  <SubmitButton
                    loading={loading}
                    label="Sign in"
                    loadingLabel="Connecting…"
                  />
                </Field>
              </FieldGroup>
            </form>
          )}

          {screen === "totp" && (
            <form
              key="s-totp"
              onSubmit={handleTotpSubmit}
              className={animated}
            >
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
                    className="text-center font-mono text-lg tracking-[0.3em]"
                  />
                  <p className="mt-1 text-xs text-muted-foreground">
                    Lost your device? Use a recovery code.
                  </p>
                </Field>
                {error && (
                  <p
                    role="alert"
                    className={cn(
                      "text-sm text-[color:var(--severity-err)]",
                      errorAnim
                    )}
                  >
                    {error}
                  </p>
                )}
                <Field>
                  <SubmitButton
                    loading={loading}
                    label="Verify"
                    loadingLabel="Verifying…"
                  />
                </Field>
              </FieldGroup>
            </form>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

function SubmitButton({
  loading,
  disabled,
  label,
  loadingLabel,
}: {
  loading: boolean;
  disabled?: boolean;
  label: string;
  loadingLabel: string;
}) {
  return (
    <Button
      type="submit"
      className="h-10 w-full gap-2"
      disabled={loading || disabled}
    >
      {loading && <Loader2 className="size-4 animate-spin" aria-hidden />}
      <span>{loading ? loadingLabel : label}</span>
    </Button>
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
        "group flex w-full items-center gap-3 rounded-xl border p-4 text-left",
        "transition-all duration-200 will-change-transform",
        "hover:-translate-y-0.5 active:translate-y-0",
        primary
          ? "border-primary/40 bg-primary/5 shadow-sm shadow-primary/10 hover:border-primary hover:bg-primary/10 hover:shadow-md hover:shadow-primary/20 hover:ring-2 hover:ring-primary/20"
          : "border-border hover:border-foreground/20 hover:bg-muted hover:shadow-sm"
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
