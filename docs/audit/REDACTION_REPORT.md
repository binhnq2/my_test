# Redaction report

Scope: every file the bundle carries (259), plus `SOURCE_TREE.txt`,
`FILE_INDEX.md`, and the three other Markdown reports in this directory.

**No secrets detected.**

Nothing was redacted from the bundle, because there was nothing to redact. The
sections below record what was scanned, what was found, and the two places where
a host-specific value *is* present and was excluded by path rather than by
substitution.

---

## 1. Method

Two independent passes, both implemented in `build_audit_bundle.py` and
re-runnable:

**Pass A — value shapes.** Five patterns matched against every line of every
file that goes into the bundle:

| pattern | what it catches |
|---|---|
| `(api_key\|apikey\|secret\|password\|passwd\|bearer) = "…"` | a credential assigned a literal |
| `-----BEGIN … PRIVATE KEY-----` | a PEM private key |
| `AIza[0-9A-Za-z_-]{35}` | Google API key shape |
| `gh[pousr]_[0-9A-Za-z]{36,}` | GitHub token shape |
| `sk-[0-9A-Za-z]{20,}` | OpenAI-style secret key shape |

**Pass B — word occurrences, classified.** Every line containing
`api_key|apikey|secret|token|password|passwd|credential`, each classified as
prose (comment, KDoc line, XML comment, string resource) or code, so that a
reviewer can see that the 63 occurrences were looked at rather than missed.

Result: **63 occurrences, 0 values.** Pass A returned zero hits after
classification; every Pass-B occurrence is a variable name, a preference key, or
a sentence explaining why a credential is not stored.

---

## 2. What exists in the tree, and why it is not a secret

The project *has* a credential path — an optional self-hosted music server. It
carries no credential in source:

| location | what it is | why it is not a secret |
|---|---|---|
| `app/src/main/java/com/ts8/launcher/runtime/AppSettings.kt:402-410` | `onlineToken` getter/setter over SharedPreferences | the *name* of a preference; the value is whatever the driver typed on their own unit |
| `AppSettings.kt:523` | `KEY_ONLINE_TOKEN = "online_server_token_v1"` | a preference key string, not a token |
| `core/music-provider-online/.../NavidromeProvider.kt:44` | `private val password: String? = null` | a constructor parameter with a `null` default |
| `NavidromeProvider.kt:406` | `md5Hex((password ?: "") + salt)` | Subsonic's salted-hash scheme, computed at runtime from the driver's own password |
| `RadioBrowserProvider.kt:410` | `DEFAULT_BASE_URL = "https://de1.api.radio-browser.info"` | a public read-only community API with no key or account |
| `app/src/main/res/values/strings.xml:382` | `Mật khẩu / token` | a UI label |
| `app/src/main/res/values/strings.xml:375` | `BLOCKED_EXTERNAL_CONFIG — …` | an error message for the unconfigured state |

The password field is also never re-rendered: `SettingsActivity.kt:190-192`
and `activity_settings.xml:243-247` both carry a comment saying so, and
`SettingsActivity.kt:210` displays a state word instead of the stored value.
That is a design decision recorded in code, not a redaction.

---

## 3. Files excluded for host-specific content

Two files contain values that belong to *this build host*, not to the project.
Both were excluded by path from `SOURCE_TREE.txt` and from every bundle, and
neither is needed to compile or review the code:

| file | why excluded | what it would have leaked |
|---|---|---|
| `local.properties` | AGP regenerates it per machine; the project does not track it | `sdk.dir=D:/Tools/_toolchain/android-sdk`, an absolute path on this workstation — not a credential, but noise that makes the tree non-portable |
| `gradle.properties` | **included**, but see below | — |

`gradle.properties` **is** in the bundle, and it contains:

```
-Djavax.net.ssl.trustStorePassword=changeit
```

This is the JDK's published default keystore password and the documented
`-storepass` argument in the `keytool` command directly above it in the same
file. It is the password to *this host's* local truststore, not to any service,
and the file itself says the three `javax.net.ssl` lines and the
`systemProp.*.proxy*` lines should be dropped on any machine without the
corporate proxy. It is listed here rather than silently passed over, and it is
allow-listed by literal in the scanner (`KNOWN_SAFE_VALUES`) so that a future
real password cannot hide behind the same pattern.

The proxy hostname `fdnproxy-fc-lan.fsoft.fpt.vn` is in the same file. That is
an internal DNS name, not a credential; it was kept because removing it would
leave the build configuration unexplained. **The reviewer should treat it as an
organisational detail** and not redistribute the bundle outside the review.

---

## 4. Everything else that the scanner saw

Non-prose occurrences (12), all inspected by hand:

| file:line | text | verdict |
|---|---|---|
| `OnlineMusicRuntime.kt:195` | `password = settings.onlineToken.takeIf { it.isNotBlank() }` | passes the stored value to a provider; no literal |
| `NavidromeProvider.kt:44` | `private val password: String? = null,` | default parameter |
| `NavidromeProvider.kt:64` | `!password.isNullOrBlank()` | blank-check |
| `NavidromeProvider.kt:406` | `val token = md5Hex((password ?: "") + salt)` | computed digest, not stored |
| `NavidromeProvider.kt:409` | `"t" to token,` | the digest in a query map |
| `NavidromeProvider.kt:452` | `missing.add("mật khẩu")` | a field name in a validation message |
| `bg_speed_limit.xml:4` | `… tied to the palette token.` | "token" here means a design token |
| `activity_settings.xml:247` | `secret back. -->` | end of an XML comment |
| `activity_settings.xml:293` | `tools:text="BLOCKED_EXTERNAL_CONFIG …"` | design-time preview string |
| `colors.xml:57` | `Splitting the token costs nothing…` | "token" here means a design token |
| `strings.xml:375` | `<string name="music_online_blocked">…` | user-facing error text |
| `strings.xml:382` | `<string name="music_online_server_pass">Mật khẩu / token</string>` | UI label |

The remaining 51 occurrences are prose: KDoc explaining the token scheme,
comments saying the password is deliberately kept out of a comparison key
(`OnlineMusicRuntime.kt:152-157`), and similar. They are reproduced verbatim in
the bundle because they are the reviewer's evidence that the credential path was
considered rather than overlooked.

---

## 5. Verification

`python -I docs/audit/verify_audit_bundle.py` re-checks the emitted files for
credential-shaped strings (Google/GitHub/OpenAI key shapes and PEM headers)
independently of the build step, so a bundle produced by an older revision of
the generator would still be caught. Current result:

```
secret shapes     : 0
```

and the build step reports `secret hits : 0`.
