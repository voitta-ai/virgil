# Virgil

A guide. It checks where you are and tells you something about the place.

Android first. Passive by design - you are not meant to talk to it.

## Status

v0.1 is an experiment, not a product. It exists to answer one question:

> For an arbitrary location - including a dull residential street - can a
> retrieval-plus-LLM pipeline produce a short spoken blurb worth having heard?

If fewer than 30% of blurbs generated in a boring neighborhood are rated
"worth reading," the premise is wrong and this repository stops.

## Requirements

Requirements live as issues, not documents:

- [#1 MRD](https://github.com/voitta-ai/virgil/issues/1) - premise, hypothesis, kill criterion, test ladder
- [#2 PRD](https://github.com/voitta-ai/virgil/issues/2) - scope, flow, content spec, failure states
- [#3 ERD](https://github.com/voitta-ai/virgil/issues/3) - stack, pipeline, build order
- [#4 Spike R-1](https://github.com/voitta-ai/virgil/issues/4) - blocking: Anthropic SDK on Android

## How it works

Three stages, on demand:

1. **Retrieve** - reverse geocode the point (Nominatim) and find nearby notable
   entities (Wikipedia geosearch). Both keyless.
2. **Narrate** - one Claude call turns the candidates into a blurb, with web
   search available for places the structured sources know nothing about. This
   is the part that has to work in an ordinary neighborhood, where geosearch
   returns nothing.
3. **Deliver** - notification, text-to-speech, on-screen text.

Every run appends its inputs, output, cost, and your rating to a local log.
That log is the actual deliverable of v0.1.

## API key

Virgil calls the Anthropic API with **your own key**, which you paste into
Settings. It is stored in `EncryptedSharedPreferences` and never leaves the
device except in the API call itself. This repository ships no key.

A key held on a device is only as protected as the device. That is acceptable
for single-user testing and would need a backend proxy before any wider
distribution.

## License

MIT
