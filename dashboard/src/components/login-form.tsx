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
import { Field, FieldGroup, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import {
  setApiTokenCredentials,
  setUserSessionCredentials,
  type UserInfo,
} from "@/lib/api";
import { buildControllerUrl, controllerFetch } from "@/lib/controller-url";
import { ArrowLeft, CircleCheck, Loader2 } from "@/lib/icons";
import { OtpInput } from "@/components/otp-input";
import { isPasskeySupported, loginWithPasskey } from "@/lib/passkeys";

type Screen =
  | "connect"
  | "method"
  | "code"
  | "api-token"
  | "totp";

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
 *   connect → method → {passkey | code | api-token}
 *   code → (optional) totp
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

  // TOTP state
  const [totpCode, setTotpCode] = useState("");
  const [challengeId, setChallengeId] = useState<string | null>(null);
  const [useRecoveryCode, setUseRecoveryCode] = useState(false);

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
      case "code":
      case "api-token":
        setScreen("method");
        break;
      case "totp":
        setScreen("code");
        break;
      default:
        break;
    }
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

  async function handlePasskeyLogin() {
    setError("");
    setLoading(true);
    try {
      const res = await loginWithPasskey(resolvedUrl || host);
      setUserSessionCredentials(resolvedUrl || host, res.token);
      router.push("/");
    } catch (err) {
      const msg = err instanceof Error ? err.message : "Passkey login failed";
      // Abort / user-cancel = silent.
      if (!/cancel|abort|NotAllowedError/i.test(msg)) setError(msg);
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
  // Shorter travel (1 instead of 2) + a slightly longer duration reads as
  // calmer motion — reduces the snap that felt "busy" on the previous pass.
  const animated = cn(
    "animate-in fade-in-0 duration-300 ease-out",
    direction === "forward"
      ? "slide-in-from-right-1"
      : "slide-in-from-left-1"
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
        : screen === "code"
          ? "Enter your six-digit code"
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
      <Card className="relative">
        {showBack && (
          <Button
            type="button"
            variant="ghost"
            size="icon"
            onClick={back}
            aria-label="Back"
            className="absolute left-3 top-3 z-10"
          >
            <ArrowLeft className="size-4" />
          </Button>
        )}

        <CardHeader className="text-center">
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
              className="h-16 w-16"
            />
          </div>
          {heading && (
            <CardTitle
              key={`h-${screen}`}
              className={animated}
            >
              {heading}
            </CardTitle>
          )}
          {subheading && <CardDescription>{subheading}</CardDescription>}
          {/* Always-rendered placeholder keeps the header height stable across
             screens so the card doesn't jump when we first learn the URL. */}
          <p
            className={cn(
              "mt-1 truncate text-xs text-muted-foreground transition-opacity",
              screen !== "connect" && resolvedUrl ? "opacity-100" : "opacity-0"
            )}
            aria-hidden={!(screen !== "connect" && resolvedUrl)}
          >
            Connected to{" "}
            <span className="font-mono">{resolvedUrl || "—"}</span>
          </p>
        </CardHeader>

        <CardContent>
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
                <div className={cn("min-h-5", error && errorAnim)}>
                  {error && (
                    <p
                      role="alert"
                      className="text-sm text-[color:var(--severity-err)]"
                    >
                      {error}
                    </p>
                  )}
                </div>
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
              {isPasskeySupported() && (
                <MethodCard
                  title="Passkey"
                  description="Sign in with Touch ID, Windows Hello, or a security key."
                  icon={
                    /* Custom head from minecraft-heads.com #43871 (Keypad).
                       Same pattern as the API-token card: resolve the
                       textures.minecraft.net hash once and render via
                       mc-heads.net. */
                    /* eslint-disable-next-line @next/next/no-img-element */
                    <img
                      src="https://mc-heads.net/avatar/7e1959dd4a10841dbf5e02749a2f5b09cc47874ec182fc544302decb6232947c/64"
                      alt=""
                      width={40}
                      height={40}
                      className="rounded-sm transition-transform duration-200 group-hover:scale-110"
                      loading="lazy"
                    />
                  }
                  onClick={() => void handlePasskeyLogin()}
                />
              )}
              <MethodCard
                title="Minecraft Account"
                description="Sign in with a one-time code from /nimbus dashboard login."
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
                onClick={() => go("code")}
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

          {screen === "code" && (
            <form
              key="s-code"
              onSubmit={handleCodeSubmit}
              className={animated}
            >
              <FieldGroup>
                <Field>
                  <FieldLabel htmlFor="mc-code">Login code</FieldLabel>
                  <OtpInput
                    id="mc-code"
                    aria-label="Login code"
                    value={mcCode}
                    onChange={setMcCode}
                    invalid={Boolean(error)}
                    autoFocus
                    className="justify-center"
                    onComplete={(v) => {
                      // Auto-submit once all six digits are filled so the
                      // user doesn't need to reach for the button.
                      if (!loading) {
                        setMcCode(v);
                        void handleCodeSubmit(
                          new Event("submit") as unknown as React.FormEvent
                        );
                      }
                    }}
                  />
                  <p className="mt-2 text-xs text-muted-foreground">
                    Code not working? Make sure you typed{" "}
                    <code>/nimbus dashboard login</code> in-game.
                  </p>
                </Field>
                <div className={cn("min-h-5", error && errorAnim)}>
                  {error && (
                    <p
                      role="alert"
                      className="text-sm text-[color:var(--severity-err)]"
                    >
                      {error}
                    </p>
                  )}
                </div>
                <Field>
                  <SubmitButton
                    loading={loading}
                    disabled={mcCode.length !== 6}
                    label="Sign in"
                    loadingLabel="Signing in…"
                  />
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
                <div className={cn("min-h-5", error && errorAnim)}>
                  {error && (
                    <p
                      role="alert"
                      className="text-sm text-[color:var(--severity-err)]"
                    >
                      {error}
                    </p>
                  )}
                </div>
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
                  <FieldLabel htmlFor="totp">
                    {useRecoveryCode ? "Recovery code" : "Authenticator code"}
                  </FieldLabel>
                  {useRecoveryCode ? (
                    <Input
                      id="totp"
                      type="text"
                      placeholder="ABCD-1234"
                      value={totpCode}
                      onChange={(e) =>
                        setTotpCode(e.target.value.toUpperCase())
                      }
                      required
                      autoFocus
                      className="text-center font-mono text-base tracking-[0.2em]"
                    />
                  ) : (
                    <OtpInput
                      id="totp"
                      aria-label="Authenticator code"
                      value={totpCode}
                      onChange={setTotpCode}
                      invalid={Boolean(error)}
                      autoFocus
                      className="justify-center"
                      onComplete={(v) => {
                        if (!loading) {
                          setTotpCode(v);
                          void handleTotpSubmit(
                            new Event("submit") as unknown as React.FormEvent
                          );
                        }
                      }}
                    />
                  )}
                  <button
                    type="button"
                    onClick={() => {
                      setUseRecoveryCode((v) => !v);
                      setTotpCode("");
                      setError("");
                    }}
                    className="mt-2 text-left text-xs text-muted-foreground underline underline-offset-4 hover:text-foreground"
                  >
                    {useRecoveryCode
                      ? "Use authenticator code instead"
                      : "Lost your device? Use a recovery code."}
                  </button>
                </Field>
                <div className={cn("min-h-5", error && errorAnim)}>
                  {error && (
                    <p
                      role="alert"
                      className="text-sm text-[color:var(--severity-err)]"
                    >
                      {error}
                    </p>
                  )}
                </div>
                <Field>
                  <SubmitButton
                    loading={loading}
                    disabled={
                      useRecoveryCode
                        ? totpCode.trim().length < 8
                        : totpCode.length !== 6
                    }
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
      size="lg"
      className="w-full"
      disabled={loading || disabled}
    >
      {loading && <Loader2 className="size-4 animate-spin" aria-hidden />}
      <span>{loading ? loadingLabel : label}</span>
    </Button>
  );
}

/**
 * Method picker button — identical visual treatment for every option so
 * nothing on the screen implies hierarchy the user didn't choose.
 */
function MethodCard({
  title,
  description,
  onClick,
  icon,
}: {
  title: string;
  description: string;
  onClick: () => void;
  icon?: React.ReactNode;
}) {
  return (
    <Button
      type="button"
      variant="secondary"
      onClick={onClick}
      className="group h-auto w-full justify-start gap-3 px-4 py-3 text-left whitespace-normal"
    >
      {icon && <div className="shrink-0">{icon}</div>}
      <div className="flex min-w-0 flex-col gap-0.5">
        <span className="font-medium">{title}</span>
        <span className="text-xs text-muted-foreground">{description}</span>
      </div>
    </Button>
  );
}
