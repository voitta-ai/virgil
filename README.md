# Virgil

A guide. It checks where you are and tells you something about the place.

Android first. Passive by design - you are not meant to talk to it.

## Status

v0.1 is an experiment, not a product. It exists to answer one question:

> For an arbitrary location - including a dull residential street - can a
> retrieval-plus-LLM pipeline produce a short spoken blurb worth having heard?

If fewer than 30% of blurbs generated in a boring neighbourhood are rated
"worth reading", the premise is wrong and this repository stops.

Early evidence is encouraging. On a suburban road whose nearest Wikipedia
article was 2.1 km away, the pipeline found the BNSF line beside it, the 1869
platting by Octave Chanute, and the farmer who deeded the right-of-way for a
dollar on condition trains never stop serving the depot. Without web search,
the same location produced a confident and wrong "quiet fabric of the suburbs".

## Requirements

Requirements live as issues, not documents:

- [#1 MRD](https://github.com/voitta-ai/virgil/issues/1) - premise, hypothesis, kill criterion, test ladder
- [#2 PRD](https://github.com/voitta-ai/virgil/issues/2) - scope, flow, content spec, failure states
- [#3 ERD](https://github.com/voitta-ai/virgil/issues/3) - stack, pipeline, build order
- [#4 Spike R-1](https://github.com/voitta-ai/virgil/issues/4) - closed: Anthropic SDK on Android
- [#5 CI/CD](https://github.com/voitta-ai/virgil/issues/5) - not started

## How it works

Three stages, on demand:

1. **Retrieve** - reverse geocode the point (Nominatim) and find nearby notable
   entities (Wikipedia geosearch), concurrently. Both keyless.
2. **Narrate** - one model call turns the candidates into a blurb. Where the
   provider supports web search, that is what carries ordinary places, since
   geosearch returns nothing useful there.
3. **Deliver** - on-screen text. Notification and speech are not built yet.

Every run appends its inputs, output, cost, timings and your rating to a local
JSONL log. **That log is the actual deliverable of v0.1** - the app is the
instrument, not the product.

## Providers and keys

Virgil ships **no API key and no default provider**. You choose the providers
and supply the keys. They are stored in `EncryptedSharedPreferences` on the
device and are never written to the log, the repository, or the APK.

Providers are tried in the order you turn them on. If one is rate-limited,
out of credit, or misconfigured, it is skipped for a cooldown window rather
than re-dialled on every run.

| Provider | Model | Web search | Where to get a key |
|---|---|---|---|
| `openrouter` | `anthropic/claude-opus-5` | yes | <https://openrouter.ai/keys> |
| `gemini` | `gemini-3.8-flash` | yes | <https://aistudio.google.com/apikey> |
| `gemini-compat` | `gemini-3.8-flash` | no | same key as `gemini` |
| `groq` | `llama-3.3-70b-versatile` | no | <https://console.groq.com/keys> |
| `deepseek` | `deepseek-chat` | no | <https://platform.deepseek.com> |

Only `openrouter` and `gemini` have been verified end to end.

**Web search is not a nicety here.** A provider without it still answers, and
the answer still looks fine, but it is answering from memory about a street it
has never heard of. Runs are tagged `web_search_available` in the log for
exactly this reason - do not pool the two populations when judging results.

## Building

You need **JDK 17 or newer** and the **Android SDK** (platform 34, build-tools
34.0.0). Android Studio provides both; the command line tools are enough.

```bash
git clone https://github.com/voitta-ai/virgil.git
cd virgil
```

Point the build at your SDK. Create `local.properties` (it is gitignored):

```properties
sdk.dir=/path/to/your/android/sdk
```

Common locations: `~/Library/Android/sdk` (Android Studio on macOS),
`/opt/homebrew/share/android-commandlinetools` (Homebrew), `~/Android/Sdk`
(Linux). An exported `ANDROID_HOME` works instead if you prefer.

Then build:

```bash
./gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Installing on a phone

Android 8.0 (API 26) or newer.

1. **Enable developer options.** Settings -> About phone -> tap *Build number*
   seven times.
2. **Enable USB debugging.** Settings -> System -> Developer options -> USB
   debugging.
3. **Connect the phone by USB** and accept the *Allow USB debugging* prompt on
   the phone.
4. **Confirm the phone is visible:**

   ```bash
   adb devices
   ```

   It should list your device as `device`. If it says `unauthorized`, look at
   the phone for the prompt in step 3.

5. **Install:**

   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

If you have no `adb`, copy the APK to the phone and open it - you will need to
allow installation from unknown sources.

## Using it

1. Open Virgil. Under **Providers**, turn on the one you have a key for.
2. Paste the API key and tap **Save key**.
3. Tap **Where am I?** and grant location access when asked.
4. Read the blurb, then rate it: *interesting*, *meh*, or *wrong*.
5. Tap **what it used** to see exactly what the model was given. **Judge
   hallucination from here** - a blurb cannot be assessed without knowing what
   was retrieved.
6. **Export log** shares the JSONL file. That file is what the experiment is
   collecting.

Rate honestly, including your own location. A blurb that is accurate but
boring is *meh*, not *interesting* - the hypothesis is about whether the thing
is worth hearing, not whether it is true.

## Known gaps

- Trigger is a button. Passive triggering is v0.2.
- No notification, no speech yet.
- Latency is 8-21 s end to end and the slowest runs still exceed the 20 s
  target in [#2](https://github.com/voitta-ai/virgil/issues/2).
- No tests, no CI. See [#5](https://github.com/voitta-ai/virgil/issues/5).

## License

MIT
