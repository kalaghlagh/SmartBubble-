# Adaptive UI: audit and architecture

Scope: every Compose screen, dialog, sheet and action area of the app (20 files with
`@Composable`). Method: source inspection for the patterns below, then the rendered bounds in
tests (Robolectric at given window sizes, emulator with real insets). Commit before the change:
`4af7edf`.

## 1. Audit

| # | Screen / component | Source | Problem | Severity | Correction |
|---|---|---|---|---|---|
| 1 | PDF extraction review (in the PDF flow) | `thesis/ui/ThesisPdfViewer.kt` (`PdfExtractionFlow`), `thesis/ui/ThesisExtraction.kt` (`ExtractionReviewContent`) | Full-screen `Dialog` with no inset handling. Android 15+ draws windows of apps targeting SDK 35+ edge to edge (this app targets 36), so the bottom of the review (the action grid) can lie under the navigation bar; the keyboard (block correction) covers it too. The API 34 CI emulator fits dialogs inside the bars and cannot show it. Actions were a fixed 3-row grid whatever the width or font scale. | Critical | `FullScreenDialog` (edge to edge on every version, content inside `safeDrawing`), `ContentWithActions` + `AdaptiveActionGrid` |
| 2 | PDF page viewer (Next / Close bar) | `ThesisPdfViewer.kt` (`PdfPageViewer`, `PdfPagesPane`) | Same dialog problem: the bottom bar with Next can lie under the navigation bar. | High | `FullScreenDialog` |
| 3 | Extraction review for images | `ThesisExtraction.kt` (`ExtractionReviewDialog`) | Same as 1. | Critical | `FullScreenDialog` |
| 4 | Thesis proposal review | `thesis/ui/ThesisWorkspace.kt` (`ThesisReviewDialog`) | Status + navigation bar padding only; keyboard and cutout not handled; layout depends on the Android version (decor fitting). | Medium | `fullScreenDialogProperties()` + `safeDrawing` padding |
| 5 | Offline formatting preview | `ui/components/OfflineFormattingUi.kt` | Same as 4. | Medium | same as 4 |
| 6 | Template preview | `ui/components/TemplatePreviewDialog.kt` | Scaffold dialog; bottom actions padded for the navigation bar only (keyboard not handled), version-dependent decor fitting. | Medium | `fullScreenDialogProperties()`; bottom bar padded with `safeDrawing` (bottom + horizontal) |
| 7 | OCR review (Chat scan) | `ui/components/OcrReviewDialog.kt` | Same as 6: the Copy / Insert / Generate bar can be covered by the keyboard while the text is edited. OCR language chips in a sideways-scrolling row. | Medium | same as 6; chips in a `FlowRow` |
| 8 | Thesis editor formatting toolbar | `thesis/ui/ThesisBuilderScreens.kt` | 14 tools in one row that scrolls sideways: the alignment tools are off screen on a phone, with nothing showing them. Field height while typing reserved a fixed 96 dp for a one-row toolbar. | High | Toolbar in a `FlowRow` (wraps); field height uses the measured toolbar height |
| 9 | Chat: template choice after a clarification | `ui/screens/ChatScreen.kt` | Choice buttons in a sideways-scrolling row: choices off screen. | High | `FlowRow` |
| 10 | My Templates: classification card | `ui/screens/SmartTemplatesUi.kt` (`ClassificationCard`) | Category and sub-category chips in sideways-scrolling rows. | Medium | `FlowRow` |
| 11 | Chat drawer (conversation history) | `ChatScreen.kt` | Fixed 310 dp drawer: on a 320 dp window almost no room is left to tap outside to close it. | Low | `fillMaxWidth(0.85f).widthIn(max = 310.dp)` |
| 12 | Extraction review: page chips | `ThesisExtraction.kt` | Chips in a sideways row. Kept: shortcuts only, every page is also in the list under its heading. | Info | none |
| 13 | Tables (editor grid, review tables, proposal tables, template preview), media strip, preview zoom | various | Sideways scrolling of wide *content*: intended (cells keep a readable width). | Info | none |
| 14 | Activity screens (Home, Chat, Thesis Builder/editor, Library, My Templates, Settings, language/theme) | `MainActivity.kt`, screens | Already inset-correct: the activity is edge to edge, the Scaffold padding (bottom bar, system bars) is consumed once, screens apply `imePadding()` / `statusBarsPadding()`; lists are lazy and weight-sized; editor fields bounded (no Constraints overflow). | OK | none (covered by existing tests: KeyboardDeviceTest, ResponsiveUiTest, AppSmoke screenshots in EN/FR/AR) |
| 15 | Confirmation / error / input dialogs | `AlertDialog`s across screens | Platform-width dialogs: sized and inset by the platform. | OK | none |

Not found: device-specific conditions, fixed screen sizes, unbounded text fields (fixed earlier),
nested scrolls in the same direction without bounds.

## 2. Architecture (`app/src/main/java/com/example/ui/adaptive/AdaptiveLayout.kt`)

- **`AdaptiveRules`** (pure, JVM-tested): width/height classes on Material breakpoints (600 / 840 dp
  wide, 480 / 900 dp high); action-grid columns from the width actually available and the font
  scale (cells of at least 90 dp x font scale, up to 2x); "actions beside the content" when the
  space is short (< 480 dp) and wide (>= 560 dp); the content keeps a third of the height, at
  least 120 dp. Never a device model.
- **`AdaptiveActionGrid`**: equal-width cells, as many per row as fit, labels wrapping (up to three
  lines), 48 dp minimum touch height, never a sideways scroll.
- **`ContentWithActions`**: the content (which scrolls itself) takes what is left and keeps its
  minimum; the actions sit under it, or beside it in a short wide window, in an area that scrolls
  vertically only when it needs more room (very large text) so every action stays reachable.
- **`FullScreenDialog` / `fullScreenDialogProperties()`**: full-screen dialogs are edge to edge on
  every Android version (`decorFitsSystemWindows = false`, what Android 15+ enforces for SDK 35+
  apps); the content is kept inside `WindowInsets.safeDrawing` (status bar, navigation bar,
  cutout, keyboard); the status bar area is painted with the top bar colour.
- Activity screens keep their existing scheme (edge-to-edge activity, consumed Scaffold padding,
  `imePadding`).

Insets and keyboard: one rule, `safeDrawing`, applied once at the container (dialog) level;
screens inside do not add bar padding again. Layouts use the constraints they receive
(`BoxWithConstraints`), so split screen, freeform windows, tablets and rotation follow the same
rules.

## 3. Verification

- `AdaptiveRulesTest` (JVM): classes, columns, side-by-side rule, content minimum.
- `AdaptiveLayoutTest` (Robolectric, native text measurement): 320x470, 360x640, 393x873,
  412x915, 600x960, 840x1200 dp, landscape 640x360 and 915x412, font scale 1.3 / 1.5 / 2.0, in
  English, French and Arabic, 400 lines of mixed Arabic/Latin content: each of the seven
  actions inside the window and its visible action area, not over the content or another
  action, >= 48 dp high, reached (scrolling the action area where the text is very large) and
  clicked; reading order follows the language.
- `ThesisAndroidTest`: the real review shows its seven actions on screen at 320x470 in English and Arabic.
- `PdfExtractionFlowDeviceTest` (emulator): logs display, density, font scale and the measured
  navigation-bar / keyboard insets; each action must be inside the window **above the navigation
  bar and the keyboard**, also with the keyboard open while correcting a block (each reached by
  scrolling the action area); Next above the navigation bar; every action executed.
- Screenshots: `thesis-device/pdf-review-*.png` (device-tests artifact), main screens in EN/FR/AR
  from `AppSmokeDeviceTest` (`app-device/*.png`).
