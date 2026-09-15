# Virgil

An Android app that tells you about where you are standing. MIT, `voitta-ai`,
default branch `master`.

Read this with the issues, which hold the requirements: #1 MRD, #2 PRD, #3 ERD.

## What this project is for

v0.1 is an instrument, not a product. It exists to produce a rated log of
blurbs that answers one question: can a retrieval-plus-LLM pipeline say
something worth hearing about an arbitrary, boring location?

Two consequences that should shape most decisions here:

- **The evaluation log is the deliverable.** Anything that makes the log more
  honest or more complete is worth doing. Anything that makes the app nicer
  without changing what the log can prove is probably not.
- **Fabrication is the one unrecoverable failure.** A confident, plausible,
  wrong blurb invalidates the experiment, because the log can no longer tell a
  good pipeline from a persuasive one. Prefer "I have nothing" over a guess,
  in the prompt and in the code.

## Current state

Build order from #3 is done. Working: location fix, reverse geocode, Wikipedia
geosearch, narration through a provider waterfall, speech, notification, and
the evaluation log with ratings and export. Verified end to end on a Pixel 10
Pro XL running Android 16 and on an API 31 emulator.

Not built: passive triggering (v0.2), and CI (#5).

## Conventions

- **The next feature starts using branches, worktrees and PRs.** The gate was
  "works on real hardware", and that is now met. Everything up to and including
  step 6 landed directly on `master`, which was deliberate; from the next
  feature onward, use the normal flow.
- `minSdk 26` is **load-bearing**, not a default. It is exactly where
  `java.time` arrives, which is what let the JVM-shaped dependencies work
  without core library desugaring. Lowering it means turning desugaring on.
- **No secrets anywhere near the repo or the APK.** Keys are user-supplied at
  runtime and live only in `EncryptedSharedPreferences`. They are never
  logged, never written to the evaluation log, and never committed.
- No tests exist yet and none are expected unless asked for.
- Return a named variable rather than an expression, for easier debugging.
- No Unicode emoji in code. Markdown is fine.

## The vendor waterfall

Providers are ordered rungs, tried top to bottom, chosen by the user. Nothing
is enabled by default. The doctrine is shmobster's, ported rather than shared,
since LiteLLM does not exist on Android.

Three rules learned the hard way:

- **Never put a vendor-specific field in the shared request body.**
  "OpenAI-compatible" is not uniform: `usage: {include: true}` is an OpenRouter
  extension and Gemini rejects the whole request over the unknown field.
  Vendor-specific things belong on the `Vendor` record.
- **A failed rung must be parked, not re-dialled.** Without a cooldown every
  run pays the dead vendor's latency before anything answers. Budget
  exhaustion arrives as 400 or 402 rather than 429, so status alone is not
  enough to classify it.
- **Record the whole walk, including on total failure.** A waterfall's failure
  mode is that it works: a dead rung reads as "the app is flaky", not as an
  error. The walk and the vendor's error message both go in the log.

### Web search is capability-bearing

`webSearch` on a `Vendor` is not cosmetic. Web search is what carries ordinary
locations, where Wikipedia returns nothing useful. A rung without it does not
answer worse - it answers a *different question*, successfully, and nothing
looks wrong.

So: the prompt's search clause is chosen per vendor (telling a model to search
when it cannot is an instruction to invent), and every run records
`web_search_available`. **Never pool grounded and ungrounded runs when
judging results.**

## Testing changes

There is no test suite. Verify on the emulator, and verify the **release**
build, not just debug - R8 has broken things here before.

```bash
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb emu geo fix <longitude> <latitude>     # note: longitude first
adb shell run-as ai.voitta.virgil cat files/virgil-eval.jsonl
```

`run-as` works only on the debug build. Three locations worth keeping as a
ladder, because they exercise genuinely different cases:

| Location | `lon lat` | Nearest article | Tests |
|---|---|---|---|
| Chicago Loop | `-87.6298 41.8781` | 23 m | rich retrieval |
| Lenexa KS | `-94.7500 38.9517` | 2,123 m | the real case: candidates exist but are irrelevant |
| Rush County KS | `-99.5000 38.5000` | 5,733 m | near-empty, no street in the geocode |

Reading a screen dump: uiautomator switches to **single-quoted** attributes
when a value contains double quotes, so a `text="..."` regex silently misses
error messages that embed JSON.

## Gotchas worth not rediscovering

- Gemini's OpenAI-compatible layer **cannot do web search** - it accepts only
  OpenAI-shaped tools and rejects `google_search`. Grounding needs the native
  `generateContent` API, which is why two protocols exist.
- Gemini 3.x spends most of its token budget on internal reasoning. Uncapped,
  narration ran to a 41.8 s worst case; `thinkingBudget` of 2048 gives a 4.6 s
  median while keeping most of the depth. 512 is past the knee and guts the
  content.
- Grounded answers carry inline citation markers like `[1.4.7]`. They must be
  stripped: this text is meant to be spoken, where that becomes "one point
  four point seven". A marker clipped at the end of a response leaves an
  unterminated `[1` that a closed-bracket pattern will not match.
- `TextToSpeech` has **no pause**. It has stop, and it has a queue. Pause is
  therefore faked: the blurb is split into sentences and queued as separate
  utterances, and resuming re-speaks from the interrupted one. A resume repeats
  a sentence rather than continuing mid-word, and that is the ceiling, not a
  bug. iOS `AVSpeechSynthesizer` has real pause and resume - see #7.
- Streaming is **not** a latency fix. Thinking precedes any content token, so
  streaming cannot hide it.
- Android 11 hides other packages unless queried. The `TTS_SERVICE` intent must
  be declared in `<queries>` or no speech engine is visible at all.
- Android 13's `POST_NOTIFICATIONS` is a runtime permission, and without it
  `notify()` is **dropped silently** rather than throwing.
- Ask for a permission when its value is apparent, not at launch. The
  notification prompt originally fired on startup and was dismissed at random
  by a tester who had not yet seen what the app does.
- The five ProGuard rules that `com.anthropic:anthropic-java` needs under R8
  are recorded on #4. The SDK is no longer used, but do not re-derive them if
  it returns - the obvious broad Jackson keep rule makes things worse.
