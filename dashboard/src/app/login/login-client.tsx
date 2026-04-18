"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { toast } from "sonner";
import { LoginForm } from "@/components/login-form";
import {
  getApiUrl,
  setUserSessionCredentials,
  type UserInfo,
} from "@/lib/api";
import { controllerFetch } from "@/lib/controller-url";

interface ConsumeChallengeResponse {
  token?: string;
  expiresAt?: number;
  user?: UserInfo;
  totpRequired?: boolean;
  challengeId?: string;
}

/**
 * Client-side wrapper around <LoginForm/> that also handles the
 * `?link=<token>` deep-link delivered by the in-game magic-link chat
 * component.
 *
 * Behaviour:
 *  - No stored controller URL in localStorage → fall through to the form
 *    so the user can enter the address manually.
 *  - Token present → POST `/api/auth/consume-challenge`. On success store
 *    the session token, strip the query string (so refresh doesn't retry),
 *    and navigate to the dashboard.
 *  - TOTP required → for now we surface a toast and fall through to the
 *    form; future work (Phase 6) can pre-fill a dedicated TOTP entry.
 *  - Any error → toast + fall through.
 */
export function LoginPageClient() {
  const router = useRouter();
  const params = useSearchParams();
  const linkToken = params.get("link");
  const [consuming, setConsuming] = useState(Boolean(linkToken));
  const didRun = useRef(false);

  useEffect(() => {
    if (!linkToken) return;
    if (didRun.current) return;
    didRun.current = true;

    const controllerUrl = getApiUrl();
    if (!controllerUrl) {
      // Without a known controller we can't consume the token; show the form.
      setConsuming(false);
      toast.info("Enter your controller address to finish signing in.");
      return;
    }

    (async () => {
      try {
        const res = await controllerFetch(
          controllerUrl,
          "/api/auth/consume-challenge",
          {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ challenge: linkToken }),
          }
        );

        if (!res.ok) {
          const body = await res.json().catch(() => ({}));
          toast.error(
            body.message || body.error || "Magic link is no longer valid."
          );
          setConsuming(false);
          return;
        }

        const body: ConsumeChallengeResponse = await res.json();

        if (body.totpRequired && body.challengeId) {
          // TOTP UI lives inside <LoginForm/>; drop through and let the user
          // retry manually. (Future: hoist the step into a shared store.)
          toast.info(
            "Two-factor authentication is enabled — please finish signing in below."
          );
          window.history.replaceState({}, "", "/login");
          setConsuming(false);
          return;
        }

        if (body.token) {
          setUserSessionCredentials(controllerUrl, body.token);
          window.history.replaceState({}, "", "/login");
          router.push("/");
          return;
        }

        toast.error("Unexpected response from controller.");
        setConsuming(false);
      } catch {
        toast.error("Could not reach the Nimbus controller.");
        setConsuming(false);
      }
    })();
  }, [linkToken, router]);

  if (consuming) {
    return (
      <div className="text-center text-sm text-muted-foreground">
        Signing you in…
      </div>
    );
  }

  return <LoginForm />;
}
