# ZhanQu JSON Profile v1

Profiles are local, bounded data. They are not JavaScript, shell, ADB, network request, or arbitrary instruction scripts. Imported profiles must be shown to the user before use. A profile applies to exactly one `packageName`; switching packages is a stop/pause condition in the capture runtime.

## Current supported schema

The complete built-in example is `BuiltInProfiles.XIAOHONGSHU_JSON` in `app/src/main/java/dev/zhanqu/app/core/BuiltInProfiles.kt`.

- `schemaVersion`: exactly `1`.
- `id`: 1–128 ASCII letters, digits, dot, underscore or dash.
- `packageName`: one explicit Android package.
- `activityPatterns`: up to 20 constrained regular expressions. A known Activity must match; if the service cannot observe an Activity, matching uses package and node anchors instead. This is weaker evidence and is not version certification.
- `versionRange`: optional `min` / `max` numeric dotted versions. If bounded, an unknown installed version does not count as a match.
- `priority`: clamped to −100…100.
- `recognition.all` and `recognition.any`: arrays of `{ "exists": <selector> }`; at least one nonempty set is required. At most 20 rules per set.
- `locators`: 1–64 named ordered candidate arrays, each 1–16 selectors. The first candidate yielding matches wins; candidates are fallbacks, not a union. Repeated IDs remain multiple matches and must be disambiguated by ancestry and viewport.
- `data.pageCounterRegex`: exactly two capture groups, current and total. Parsed counters require `1 <= current <= total <= maxPages`.
- `flows.images`: fixed strategy `horizontalCarousel`, start policy `rewindToFirstOrAsk`, named `screenshotRegion` and `pageCounter`; `advance` supports only `swipeLeft` and a named region. `waitFor` is `pageCounterChanged`, `extract` is `ocr`, and `stopWhen` is `currentPageEqualsTotal`.
- `flows.comments`: fixed `nestedCommentScroll`, named `scrollContainer`, `extractParents`, `extractChildren`, `expand`, and `stopWhen: endOfListOrStable`. `replyRelation` is `ancestor` by default, or the explicit opt-in `ancestorOrPrecedingSiblingGroup` described below.
- `limits`: `maxPages` 1–100 (default 50); `maxScrolls` 1–500 (300); `actionTimeoutMs` 500–30000 (5000); `maxConsecutiveNoNewContent` 1–10 (3); `maxExpandFailures` 1–10 (3).
- `output`: only `commentDeduplication: parentAuthorTextTime` and `pageOrder: numeric` currently supported.

Unknown keys, unsupported strategies, excessive limits, empty selectors, and unsupported regex features are rejected. Profiles are limited to 128 KiB, registry to 32 profiles, regexes to 512 characters, and inspected regex input to 8192 characters. Lookarounds, backreferences, and repeatedly quantified groups are rejected. The interpreter does not expose I/O capabilities.

## Selectors

Supported keys: `viewId` / `resourceId`, `className` / `class`, exact `text`, `textRegex`, `descRegex` / `contentDescriptionRegex`, `ancestorViewId`, `parentViewId`, optional `clickable`, `scrollable`, and `requireVisibleBounds`.

A short view ID matches the suffix after `/`; a qualified resource ID must match exactly. A selector must identify a node by ID/class/text/description or require scrollability. Password-node text is never selected. Visible password fields prevent page-profile matching and comment extraction.

`find(snapshot, locator, visibleOnly = true)` filters both visibility and intersection with the display. Capture and gesture actions must use this form and a newly read snapshot. Reading text already exposed by the selected page tree may use `visibleOnly = false`. This does not authorize screenshots or actions on offscreen nodes. Inverted bounds remain empty, never a valid screenshot region.

Named metadata locators are `title`, `author`, `body`, `displayedCommentCount`. Comment fields are `commentText`, `commentAuthor`, `commentTime`, `commentRegion`, `commentLikes`; optional `commentEnd` locates explicit end markers.

## Reply parent relationships

The synthetic XHS 9.49.2 regression fixture contains 2 parent containers and 2 reply containers, but replies are separate rows under `noteDetailRV`, not descendants of `parentCommentLayout`. It therefore uses `ancestorOrPrecedingSiblingGroup`:

1. A real parent-container ancestor always wins.
2. Otherwise, only within the same declared list, a row containing exactly one parent starts a group.
3. Following recognized reply rows can attach to that parent. A recognized expand-replies row preserves the group.
4. An unrelated row, an ambiguous row with multiple parents, or a missing parent prevents inference. No parent is carried from a previous snapshot or unrelated list.

Each parsed comment carries `parentRelation`: `ancestor`, `siblingGroup`, `unresolved`, or `none`. `siblingGroup` is an adapter-declared inference from row structure, not a remote stable comment ID. Orphans retain `isReply = true` and `parentUnresolved = true`; they must not silently become main comments. Pure snapshot tests cover both nested and sibling representations, plus unknown-row interruptions.

Deduplication fingerprints include parent, author, raw body, time, region and level, using length-prefixed fields. Repeated identical rows in one snapshot have occurrence suffixes. Author/time differences do not collapse. Without stable source IDs, cross-scroll identity remains heuristic; loading/format changes or an offscreen missing parent can preserve duplicates rather than justify an unsupported merge.

## Completion is evidence, not a displayed number

Page collection must rewind to page 1 or ask the user. Total changes, page jumps, and identical hashes on different page indices fail validation. A complete run requires every index 1…N and no errors. Unknown counters do not become an invented total.

The displayed comment count is independent of extracted parent/reply counts. An explicit end marker can confirm the list end. Three no-new-content scrolls only imply a suspected end when the viewport fingerprint also stops changing. That state never sets `endReached = true`.

## GKD offline import and privacy

`GkdSnapshotImporter` accepts flat GKD nodes (`id`, `pid`, `attr`) and nested node trees. It copies immutable data, handles qualified IDs and `vid`, preserves text including whitespace, and redacts password fields. Duplicate JSON metadata keys are accepted with a warning and last-value semantics; truncated data, duplicate node IDs, cycles, unsupported dimensions and excessive depth are rejected.

ZIP import allows at most 64 entries, 8 MiB for each JSON, 40 MiB decompressed total, and exactly one node snapshot. Paths with traversal components are rejected. Files are read in memory; images and original paths are not extracted or retained. Imports do not run GKD or copy its source code.

Committed fixture `app/src/test/resources/fixtures/xhs-192-synthetic.json` preserves a 192-node structural shape, bounds, IDs and common test page/count labels. All other text and descriptions are synthetic; no original author, body, time, PNG, ZIP or snapshot device identifiers are included. The optional original fixture test reads `ZHANQU_REAL_SNAPSHOT_ZIP` (or system property `zhanqu.realSnapshotZip`); the source remains outside the repository.

## Current validation scope

Core JVM tests cover JSON profiles/selectors, alternate-package configuration, page parsing/rewind/completion, jump/repeated frame failures, comment grouping/dedup/completeness, ZIP and malformed input. Synthetic fixtures run by default; an optional local ZIP regression is skipped unless explicitly configured. These tests do not establish on-device gestures, screenshot availability, OCR quality, or compatibility with other app versions. Those require Android integration and device tests.
