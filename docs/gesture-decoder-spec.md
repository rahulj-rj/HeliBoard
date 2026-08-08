# Own Gesture Decoder — Design Spec (v0.1, 2026-08-08)

Goal: replace the proprietary `libjni_latinime.so` gesture decoding with our own
implementation so swipe typing can be legally bundled and distributed (incl. Play
Store), and so decoding quality can be tuned to Swype-like behavior.

## Legal basis

Implement from **expired patents and published papers only** — never from
decompiled proprietary code:

- **US 7,098,896** (Kushler & Marsden, filed 2003-01-16) — "System and method for
  continuous stroke word-based text input". The core Swype algorithm.
  **Expired 2024-04-30.**
- **US 7,453,439** (continuation) — speed-adaptive matching, inflection-point
  classification, penalty system, language-model ranking. **Expired 2025-02-14**
  (fee-related lapse).
- **SHARK² paper** (Kristensson & Zhai, UIST 2004) — two-channel (shape +
  location) scoring, template pruning, channel fusion.
- The Swype APK is used **only as a black-box behavioral benchmark** (candidate
  ordering, sloppiness tolerance, loop gestures). No decompilation.

## Decoder variants (user-selectable)

Three scorers behind one shared pipeline (capture → preprocess → prune → score →
rank). Selectable via pref `gesture_decoder_scorer` = `hybrid` | `kushler` |
`shark2` (surfaced in debug settings first; promote to a normal setting if the
choice proves worth keeping long-term):

- **KushlerScorer** — pure patent method: classified inflection points matched to
  key centers, weighted x/y distances, speed-adaptive thresholds, penalty system.
  Forgiving of sloppy path middles; sensitive to corner quality.
- **Shark2Scorer** — pure SHARK²: normalized whole-path shape channel + absolute
  location channel, Gaussian channel fusion. Holistic trajectory matching; less
  dependent on crisp corners.
- **HybridScorer** — the blend below (v1 default). Bakeoff data from the two pure
  scorers feeds its weights.

All three share the ranking formula and the frequency/user-history integration.

## Algorithm (v1 — patent/SHARK² hybrid)

### Input
- `InputPointers` per stroke: `x[]`, `y[]`, `time[]` (already captured by
  `PointerTracker` / `GestureStrokeRecognitionPoints` — the fork already tuned
  detection thresholds).
- Keyboard geometry: key centers + bounds from `Keyboard.sortedKeys`.

### Preprocessing
1. Resample path to uniform arc-length spacing; light smoothing.
2. Detect **inflection points**, classified (per US7453439) as:
   `PEN_DOWN`, `PEN_UP`, `ANGLE_THRESHOLD` (direction change above threshold),
   `PAUSE` (dwell), `ROW_CHANGE`, `DOUBLE_LETTER` (small loop / back-and-forth).
   Each class carries its own confidence weight.

### Candidate pruning (cheap → expensive)
1. First/last letter must be within a neighborhood of PEN_DOWN / PEN_UP keys.
2. Path length must fall within a ratio band of the candidate's ideal polyline
   ("sokgraph") length.
3. Word must have ≥ letters than detected inflection points require.
4. Trie walk over the dictionary restricted to key-neighborhood transitions —
   avoids scoring the full vocabulary.

### Scoring (two channels + penalties)
- **Location channel** (patent): weighted sum of distances from each inflection
  point to its matched key center. Separate x/y weights — **y weighted heavier**
  (row position is more reliable than column). Thresholds scale with stroke
  speed: faster ⇒ more tolerant (US7453439).
- **Shape channel** (SHARK²): normalize (translate/scale) both drawn path and
  sokgraph template, resample both to N points, mean point-wise distance.
- **Penalties**: skipped inflection points, letters far off-path, transpositions.
- **Ranking** (patent formula):
  `score = Weighted_Sum_of_Distances * (log(MAX_FREQ / word_frequency) + 1)`
  — lower is better. User-history words use their (fork-boosted) frequencies;
  ngram context integration is a later phase.
- **Double letters**: a `DOUBLE_LETTER` inflection matches two consecutive
  identical letters (Swype's loop gesture).

## Architecture

- New pure-Kotlin package `helium314.keyboard.gesture` — no Android deps in the
  core so it's unit-testable on JVM.
  - `GestureDecoder`: `decode(pointers, keyboard, vocab): List<ScoredWord>` —
    owns the shared pipeline, delegates scoring to a `Scorer`.
  - `Scorer` interface with `KushlerScorer`, `Shark2Scorer`, `HybridScorer`
    implementations (see "Decoder variants"). Pure functions of
    (preprocessed path, candidate sokgraph) → score, so all three are
    unit-testable and benchmarkable on the same corpus.
  - `Vocabulary`: trie of (word, frequency), built per-locale at dictionary load.
    Source: iterate the binary dictionary via existing word-property/dump APIs
    (same mechanism the personal-dict export uses); merge user history.
  - Sokgraph templates computed lazily during trie walk (never precompute the
    full vocabulary).

## Integration points (verified against code, 2026-08-08)

- `SettingsValues.java:245` — `mGestureInputEnabled = JniUtils.sHaveGestureLib && pref`.
  Change to also enable when our decoder is available; add a debug pref to force
  ours even when the proprietary lib is loaded (A/B on device).
- `Suggest.kt:265` — `getSuggestedWordsForBatchInput(...)`: the swap point.
  Branch: proprietary lib present *and not overridden* → native path (unchanged);
  else → `GestureDecoder` → build `SuggestionResults` the same way.
- `WordComposer.setBatchInputPointers` / `InputLogicHandler.updateBatchInput`
  already deliver `InputPointers` — no changes needed upstream of Suggest.
- Existing upstream `GestureDataGathering` screens can record real swipes +
  chosen words → regression corpus for tuning.

## Milestones

1. **M1 — offline prototype**: shared pipeline + all three scorers + JVM unit
   tests with synthetic paths (ideal polylines + noise) against a small word
   list; scorer-vs-scorer accuracy harness from day one.
2. **M2 — on device**: wired behind debug prefs (decoder on/off + scorer
   picker), top-10k vocab, correctness over speed.
3. **M3 — full vocab + perf**: pruning quality, allocation discipline; move hot
   loops to native only if profiling demands it.
4. **M4 — tuning/bakeoff**: three-way scorer bakeoff + A/B vs proprietary lib
   and vs real Swype; loops, speed adaptation, short-word disambiguation.
   Corpus via GestureDataGathering. Winner (or tuned hybrid) becomes default;
   keep the picker as long as it stays useful.
5. **M5 — cutover**: proprietary lib optional; `nouserlib`-style Play-ready
   build with swipe included.
