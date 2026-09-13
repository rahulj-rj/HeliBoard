# Dictionary Plan for the Play Release (v0.1, 2026-09-07)

Companion to `gesture-decoder-spec.md`. Covers which word lists ship, under what
license, and how more languages get added after launch.

## Current state

- `app/src/main/assets/dicts/` bundles 18 upstream dictionaries (~35 MB of assets).
- In-app "download dictionary" is a browser link to
  https://codeberg.org/Helium314/aosp-dictionaries; the user imports the `.dict`
  file by hand. No manifest, no versioning, no license display.
- Own gesture decoder builds its vocabulary from the *primary* locale's `.dict`
  only (top 50k words + user history), Latin script only.
- Personal dictionaries on the phones: en-US (AOSP, clean) and Hinglish `hi_ZZ`
  (no stated license, NOT shippable).

## License classes (from the upstream README + `.source` notes)

| Class | Licenses | Shipping rule |
| --- | --- | --- |
| A | Apache-2.0 (AOSP), CC BY 4.0 (Leipzig), CC BY-SA 4.0, MIT, Unicode | Bundle or download. Show attribution in an "Open source licenses" screen; CC BY-SA also needs the share-alike note. |
| B | GPLv2, LGPL-3.0, AGPL-3.0 (Signal emoji lists) | Download-only, kept as a separate data file, license shown at download time. Do not bundle GPLv2-only data into the GPL-3 APK. |
| C | No license stated (e.g. `hi_zz` Hinglish mix) | Never ship. Rebuild from licensed sources instead. |

## Phase 0 — first launch: English only

1. **Ship `main_en-US.dict` (regular, AOSP 2014, 160,715 words, next-word data).**
   Source chain: aosp-dictionaries ← OpenBoard v1.4.5 `en_wordlist.combined.gz` ←
   AOSP LatinIME, Apache-2.0. Class A, no attribution text strictly required beyond
   the Apache notice already covered by the app's LICENSE-Apache-2.0 file.
   - Not the experimental 280k Leipzig list for launch: CC BY attribution work,
     no names (hunspell-filtered), and M4 gesture tuning was done against the
     AOSP list. Revisit after M4 lands.
2. **Remove the other 17 bundled dictionaries from the Play flavor.** Move assets
   to a flavor-specific source set so `lab`/`debug` keep them if wanted; Play AAB
   ships en-US only. Expected APK shrink ≈ 30 MB.
3. **Attribution screen.** Add an "Open source licenses" entry listing each shipped
   dictionary: name, word count, source URL, license. Required before Class A
   CC BY lists arrive; cheap to do now.
4. **Keep the user-import path** (pick a `.dict` from storage). Data files are
   allowed by Play policy; only executable code is not.

## Phase 1 — in-app download of additional languages

Replace the browser link with a real downloader.

- Host our own copy of the `.dict` files (GitHub release assets or a small CDN),
  pinned per app version. Do not hit Codeberg from production: no SLA, rate
  limits, and files change without notice.
- `dictionaries.json` manifest: `{locale, type(main|emoji|symbols), url, bytes,
  sha256, words, updated, license, attribution, sourceUrl}`. Verify sha256 after
  download, then hand the file to the existing import code.
- Download screen: list by language, show license class and size, one tap to
  install, delete, or update. Class B entries show the license text before
  download.
- Play Asset Delivery (on-demand packs) is the alternative if we later want
  Google to host; defer — it ties dictionaries to the AAB release cycle.

## Phase 2 — language rollout order

Driven by (a) the user's own needs, (b) license class, (c) decoder readiness.

| Priority | Language | Dictionary | Class | Notes |
| --- | --- | --- | --- | --- |
| 1 | en-US | regular AOSP | A | Phase 0. |
| 2 | en-GB, en-AU, en-CA | regular AOSP / experimental CC BY | A | Same script and layout; decoder works unchanged. First download-only set. |
| 3 | hi-Latn (Hinglish) | **must build our own** | — | `hi_zz` is Class C. Options: (i) transliterate the GPLv2 indicproject Hindi list → derivative stays GPLv2 (Class B, download-only); (ii) build from CC-licensed code-mixed corpora (Hugging Face Hindi-English datasets under CC BY) → Class A, bundlable. Prefer (ii); (i) as stopgap. Keep the personal `hi_zz` for tuning only. |
| 4 | European Latin-script (de, es, fr, it, nl, pt, sv, pl, tr, hu, ro) | regular AOSP where present, else experimental CC BY | A | Already bundled today; become downloads. Decoder needs per-locale vocab + layout geometry (currently primary locale only). |
| 5 | hi (Devanagari), bn, ru, el, bg | main_hi GPLv2; bn CC BY; others AOSP | A/B mix | Blocked on non-Latin decoder support (see below). |
| 6 | emoji dictionaries | Unicode + AGPL-3.0 | A/B | AGPL data is combinable with GPL-3 per GPLv3 §13; ship download-only anyway. |

## Phase 3 — decoder work the rollout depends on

- Multi-locale vocabulary: build/caches per enabled subtype, not just primary;
  decode against the active keyboard's locale and geometry.
- Non-Latin scripts: Devanagari/Cyrillic/Greek layouts have more keys and
  different key density; the shape/location channels need per-layout
  normalisation. Transliteration input (Hinglish → Devanagari) is a separate
  feature, out of scope for dictionaries.
- Apostrophe/hyphen words still don't decode (M2 gap) — matters for en-GB too.

## Open questions

- Ship en-GB in Phase 0 as well (1 MB, Class A)? Cheap, but "English only" was
  the stated launch scope.
- Where to host: GitHub releases under the fork vs a separate `dictionaries`
  repo mirroring aosp-dictionaries with our manifest.
- Whether to sign the manifest (prevents a hijacked host from serving bad data;
  sha256 in the manifest alone doesn't cover that).
