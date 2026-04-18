/**
 * Thin WebAuthn helpers built on the browser's native JSON API
 * (`PublicKeyCredential.parseCreationOptionsFromJSON` et al.), which all
 * modern browsers support as of 2024. No external dependency needed.
 *
 * Flow mirrors the backend in `modules/auth/.../routes/PasskeyRoutes.kt`.
 */

import { apiFetch } from "@/lib/api";
import { buildControllerUrl, controllerFetch } from "@/lib/controller-url";
import type { UserInfo } from "@/lib/api";

// Minimal shape — browser typings don't export these as JSON.
type JsonOptions = Record<string, unknown>;

interface StartResponse {
  ceremonyId: string;
  publicKeyOptionsJson: JsonOptions;
}

interface LoginFinishResponse {
  token: string;
  expiresAt: number;
  user: UserInfo;
}

interface CredentialDto {
  credentialId: string;
  label: string;
  createdAt: number;
  lastUsedAt: number | null;
  aaguid: string | null;
}

export interface PasskeyEnrollResult extends CredentialDto {}

function unwrapPublicKey(obj: unknown): unknown {
  if (obj && typeof obj === "object" && "publicKey" in (obj as object)) {
    return (obj as { publicKey: unknown }).publicKey;
  }
  return obj;
}

export function isPasskeySupported(): boolean {
  if (typeof window === "undefined") return false;
  return typeof window.PublicKeyCredential === "function" &&
    typeof (
      window.PublicKeyCredential as unknown as {
        parseCreationOptionsFromJSON?: unknown;
      }
    ).parseCreationOptionsFromJSON === "function";
}

/**
 * Register a new passkey. Requires the user to already hold a valid
 * dashboard session (enrollment is bearer-authed).
 */
export async function enrollPasskey(label: string): Promise<PasskeyEnrollResult> {
  const start = await apiFetch<StartResponse>("/api/auth/passkey/register/start", {
    method: "POST",
    body: JSON.stringify({ label }),
  });

  const PK = window.PublicKeyCredential as unknown as {
    parseCreationOptionsFromJSON(o: unknown): PublicKeyCredentialCreationOptions;
  };
  // Yubico's toCredentialsCreateJson wraps the options in {publicKey: {...}} —
  // the native parser expects the inner object directly.
  const inner = unwrapPublicKey(start.publicKeyOptionsJson);
  const createOptions = PK.parseCreationOptionsFromJSON(inner);
  const credential = (await navigator.credentials.create({
    publicKey: createOptions,
  })) as PublicKeyCredential | null;
  if (!credential) throw new Error("Passkey creation was cancelled");

  const responseJson = JSON.stringify((credential as unknown as { toJSON(): unknown }).toJSON());
  const finish = await apiFetch<PasskeyEnrollResult>(
    "/api/auth/passkey/register/finish",
    {
      method: "POST",
      body: JSON.stringify({ ceremonyId: start.ceremonyId, responseJson, label }),
    }
  );
  return finish;
}

/**
 * List the current user's registered passkeys. Bearer-authed.
 */
export async function listPasskeys(): Promise<CredentialDto[]> {
  const res = await apiFetch<{ credentials: CredentialDto[] }>("/api/auth/passkey/credentials");
  return res.credentials;
}

export async function deletePasskey(credentialId: string): Promise<void> {
  await apiFetch<{ success: boolean }>(
    `/api/auth/passkey/credentials/${encodeURIComponent(credentialId)}`,
    { method: "DELETE" }
  );
}

/**
 * Passkey-based login. Does NOT require an existing session — callers are
 * pre-login, so we post directly to the known controller host.
 *
 * `host` is the controller URL the user connected to on the first screen.
 */
export async function loginWithPasskey(host: string): Promise<LoginFinishResponse> {
  if (!isPasskeySupported()) {
    throw new Error("Your browser does not support passkeys");
  }
  const base = buildControllerUrl(host);

  const startRes = await controllerFetch(base, "/api/auth/passkey/login/start", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({}),
  });
  if (!startRes.ok) {
    const body = await startRes.json().catch(() => ({}));
    throw new Error(
      (body as { message?: string }).message ||
        `Failed to start passkey login (${startRes.status})`
    );
  }
  const start = (await startRes.json()) as StartResponse;

  const PK = window.PublicKeyCredential as unknown as {
    parseRequestOptionsFromJSON(o: unknown): PublicKeyCredentialRequestOptions;
  };
  const inner = unwrapPublicKey(start.publicKeyOptionsJson);
  const reqOptions = PK.parseRequestOptionsFromJSON(inner);
  const assertion = (await navigator.credentials.get({
    publicKey: reqOptions,
    mediation: "optional",
  })) as PublicKeyCredential | null;
  if (!assertion) throw new Error("Passkey login was cancelled");

  const responseJson = JSON.stringify((assertion as unknown as { toJSON(): unknown }).toJSON());
  const finishRes = await controllerFetch(base, "/api/auth/passkey/login/finish", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ ceremonyId: start.ceremonyId, responseJson }),
  });
  if (!finishRes.ok) {
    const body = await finishRes.json().catch(() => ({}));
    throw new Error(
      (body as { message?: string }).message ||
        `Passkey login failed (${finishRes.status})`
    );
  }
  return (await finishRes.json()) as LoginFinishResponse;
}
