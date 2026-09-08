import { useEffect, useState } from "react";
import { auth, detailOf, type Enrolment, type InvitationInfo } from "../api";

// Plate A3 — single-use invitation link.
//
// The three states share one skeleton and differ in exactly two places: the
// square glyph, and which box on the ISSUED–OPEN–CONSUMED line is filled.
// Nothing depends on tone, which is what lets the same plate carry all three.

const STATES = ["issued", "open", "consumed"] as const;

const GLYPH: Record<string, string> = {
  issued: "□", open: "◧", consumed: "■", expired: "⊠",
};

const HEADLINE: Record<string, string> = {
  issued: "This link is valid and has not been opened before",
  open: "This link is valid",
  consumed: "This link has already been used",
  expired: "This link is no longer valid",
};

function StateLine({ state }: { state: string }) {
  return (
    <div>
      <div className="row" style={{ gap: 10, alignItems: "center" }}>
        <span style={{ fontFamily: "var(--mono)", fontSize: 22 }}>{GLYPH[state] ?? "⊠"}</span>
        <span>{HEADLINE[state] ?? HEADLINE.expired}</span>
      </div>
      <div className="row" style={{ gap: 0, marginTop: 14 }}>
        {STATES.map((s, i) => (
          <div key={s} className="row" style={{ gap: 0, alignItems: "center" }}>
            <span style={{
              fontFamily: "var(--mono)", fontSize: 13,
              color: s === state ? "var(--ink)" : "var(--ink-3)",
            }}>
              {s === state ? "■" : "□"} {s.toUpperCase()}
            </span>
            {i < STATES.length - 1 && (
              <span style={{ color: "var(--rule)", margin: "0 10px" }}>———</span>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}

export function Invitation({ token, onEnrolled }: { token: string; onEnrolled: () => void }) {
  const [info, setInfo] = useState<InvitationInfo | null>(null);
  const [password, setPassword] = useState("");
  const [repeat, setRepeat] = useState("");
  const [enrolment, setEnrolment] = useState<Enrolment | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => { auth.invitation(token).then(setInfo).catch(() => setInfo({ state: "expired", identifier: null })); },
            [token]);

  const longEnough = password.length >= 12;
  const matches = password.length > 0 && password === repeat;
  const usable = info && (info.state === "issued" || info.state === "open");

  const submit = async () => {
    setError(null);
    try {
      setEnrolment(await auth.accept(token, password));
    } catch (err) {
      const d = detailOf(err);
      setError(d.error === "consumed" ? "This link was used while you were filling the form."
                                      : "The link could not be used.");
      auth.invitation(token).then(setInfo).catch(() => undefined);
    }
  };

  if (enrolment) {
    return (
      <div className="sheet sheet-narrow" style={{ maxWidth: 720 }}>
        <span className="label" style={{ borderBottom: 0 }}>Environmental station · node 01</span>
        <h1 style={{ fontFamily: "var(--mono)", fontSize: 24, fontWeight: 500, margin: "4px 0 18px" }}>
          Account created
        </h1>
        <hr className="rule" />
        <span className="label">Authenticator secret</span>
        <p className="num" style={{ fontSize: 18, letterSpacing: "0.08em" }}>{enrolment.totp_secret}</p>
        <p className="mono-note">Add it to an authenticator app now.</p>

        <span className="label" style={{ marginTop: 24 }}>Recovery codes</span>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(5, 1fr)", gap: 6 }}>
          {enrolment.recovery_codes.map((c) => (
            <span key={c} className="num" style={{ fontSize: 13 }}>{c}</span>
          ))}
        </div>
        {/* Shown once, here. They exist in this response and in the reader's
            hands, and nowhere else — the database only holds their hashes. */}
        <p className="mono-note" style={{ marginTop: 12 }}>
          Written down now or lost: only their hashes are stored.
        </p>
        <div style={{ marginTop: 26 }}>
          <button onClick={onEnrolled}>Continue to second factor →</button>
        </div>
      </div>
    );
  }

  return (
    <div className="sheet sheet-narrow" style={{ maxWidth: 940 }}>
      <span className="label" style={{ borderBottom: 0 }}>
        Environmental station · node 01 · enrolment by invitation
      </span>
      <div className="row" style={{ justifyContent: "space-between", alignItems: "baseline" }}>
        <h1 style={{ fontFamily: "var(--mono)", fontSize: 26, fontWeight: 500, margin: "4px 0 0" }}>
          Single-use link
        </h1>
        <span className="mono-note">Enrolment by invitation</span>
      </div>

      <hr className="rule" />
      {info ? <StateLine state={info.state} /> : <p className="mono-note">Checking the link…</p>}

      {info && usable && (
        <>
          <hr className="rule-faint" style={{ marginTop: 22 }} />
          <div className="cols cols-2">
            <div>
              <span className="label">Account</span>
              <p className="num">{info.identifier}</p>

              <span className="label" style={{ marginTop: 22 }}>New password</span>
              <input type="password" value={password} autoFocus
                     onChange={(e) => setPassword(e.target.value)} />
              <input type="password" value={repeat} placeholder="repeat"
                     style={{ marginTop: 12 }}
                     onChange={(e) => setRepeat(e.target.value)} />

              <div style={{ marginTop: 26 }}>
                <button onClick={submit} disabled={!longEnough || !matches}>Create account →</button>
              </div>
              {error && <p className="mono-note accent" style={{ marginTop: 16 }}>{error}</p>}
            </div>

            <div>
              <span className="label">Requirements</span>
              {/* Checks are marked by glyph, not by turning green: the same
                  information reaches a reader who cannot distinguish the hues. */}
              <table>
                <tbody>
                  <tr><td>{longEnough ? "■" : "□"} 12 characters or more</td>
                      <td className="v">{password.length}</td></tr>
                  <tr><td>{matches ? "■" : "□"} both entries match</td><td className="v" /></tr>
                </tbody>
              </table>
              <p className="mono-note" style={{ marginTop: 14 }}>
                An authenticator secret and ten one-time recovery codes are issued
                once the account is created, and shown only then.
              </p>
            </div>
          </div>
        </>
      )}

      {info && !usable && (
        <p className="mono-note" style={{ marginTop: 20 }}>
          Ask the owner of the panel for a new link.
        </p>
      )}
    </div>
  );
}
