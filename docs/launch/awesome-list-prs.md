# Awesome-list PRs

The cheapest, highest-durability way to compound launch traffic. Awesome lists rank in Google for years; one merged PR sends a slow trickle of stars + visitors forever.

## What got submitted

| List | Stars | PR | Status |
|---|---:|---|---|
| [e2b-dev/awesome-ai-agents](https://github.com/e2b-dev/awesome-ai-agents) | 28k | [PR #1022](https://github.com/e2b-dev/awesome-ai-agents/pull/1022) | ⏳ pending review |

## Tier-1 targets that turned out NOT to fit (audit results)

I (well, your agent) ruled these out after reading their contribution rules so a closed-with-explanation PR didn't burn credibility:

| List | Stars | Verdict |
|---|---:|---|
| **[Shubhamsaboo/awesome-llm-apps](https://github.com/Shubhamsaboo/awesome-llm-apps)** | 111k | Not a curated list of external projects — it's a cookbook of hand-built templates that all live inside the repo. PR rejected unless we contributed code to *their* repo. |
| **[awesome-selfhosted/awesome-selfhosted](https://github.com/awesome-selfhosted/awesome-selfhosted)** | 295k | Strictly self-hosted **web services and web applications**. Android apps are explicitly out of scope. The `non-web` variant doesn't exist. |
| **[wasabeef/awesome-android-ui](https://github.com/wasabeef/awesome-android-ui)** | 56k | A curated list of **Android UI libraries** for developers to use in their own projects — not apps. Mythara is an app, not a library. |
| **[cloudflare/awesome-agents](https://github.com/cloudflare/awesome-agents)** | 0.2k | Specifically for projects **using the Cloudflare Agents SDK**. Mythara doesn't. |
| **[nibzard/awesome-agentic-patterns](https://github.com/nibzard/awesome-agentic-patterns)** | 4.6k | A list of agentic **patterns** (techniques + workflows), not products. |

## Defer to month 4+ (gated by repo age)

| List | Stars | Gate |
|---|---:|---|
| **[Lissy93/awesome-privacy](https://github.com/Lissy93/awesome-privacy)** | 9.4k | Contribution guidelines require **"Repositories must not be newly created, and the first stable release older than 4 months"**. Set a calendar reminder for ~4 months after the first public release. The Voice Assistants section currently has `services: []` (empty) — first entry there would be high-visibility. |

## Other places to PR over time

These are smaller lists or general directories with looser criteria. Send PRs after the launch wave settles — 2-3 per week so you stay below review-fatigue thresholds.

| Target | Notes |
|---|---|
| [pronzzz/awesome-android-foss](https://github.com/pronzzz/awesome-android-foss) | Tiny list (5★) but exactly Mythara's category. Quick win. |
| [vince-lam/awesome-local-llm](https://github.com/vince-lam/awesome-local-llm) | Defer until you have a Gemma-Nano adapter shipping — that's the angle. |
| [aishwaryanr/awesome-generative-ai-guide](https://github.com/aishwaryanr/awesome-generative-ai-guide) | Submit when you have a long-form blog post live (dev.to / Medium). |
| [tensorchord/awesome-llmops](https://github.com/tensorchord/awesome-llmops) | Out of scope for v1 — the BYO-model layer + sync repo angle isn't quite LLMOps. |
| [Heapy/awesome-kotlin](https://github.com/Heapy/awesome-kotlin) | Submit under `Awesome > Applications > Android`. Pure-Kotlin Android project, clean fit. |

## PR template (re-use for any future submission)

### PR Title

```
Add Project M.Y.T.H.A.R.A — open-source agentic-AI Android OS layer
```

### PR Body

```markdown
## What this adds

A new entry under `<section>`:

- **[Mythara](https://github.com/ankurCES/project_mythara)** — open-source, local-first, agentic-AI OS layer for Android. 65+ built-in tools, on-device personality analysis, multi-skin Compose theme engine. MIT. `Kotlin` `Android` `MIT`

## Why it fits

`<one-sentence justification matching the list's curation criteria>`

## Compliance

- [x] License: MIT
- [x] Actively maintained (commits within the last 30 days)
- [x] Working / installable (`./gradlew :app:assembleDebug` from a clean clone)
- [x] Has a README + docs (wiki at https://github.com/ankurCES/project_mythara/wiki)
- [x] Format matches existing entries in the list
```

## What to do after merging

- ⭐ **Star the list's repo.** Polite reciprocation.
- 🧵 If a maintainer requests changes, address within 24 hours.
- 📅 Wait 2 weeks before pinging a stale PR.

## How to grep more candidate lists yourself

```bash
gh search repos "awesome <topic>" --limit 20 \
  --json fullName,stargazersCount,description \
  | python3 -c "import sys,json; [print(f'{r[\"fullName\"]:50} {r[\"stargazersCount\"]:>6}  {r[\"description\"][:80]}') for r in json.load(sys.stdin)]"
```

Good topic seeds: `agentic-ai`, `local-llm`, `private-llm`, `personal-assistant`, `android-foss`, `open-source-ai`, `mobile-ai`.

When you find one, read its README + CONTRIBUTING + sample existing entries before opening any PR. Awesome lists vary wildly in style — alphabetical vs categorical, markdown vs YAML, deeply-nested vs flat. Matching the existing style is what gets PRs merged.
