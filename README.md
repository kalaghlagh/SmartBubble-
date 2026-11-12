# SmartBubble

SmartBubble is an Android app for AI-assisted writing and editable Office documents. You talk to
an AI provider you configure yourself, and the app turns the answers into real DOCX, PPTX, XLSX
and PDF files. The interface is available in English, Arabic (right-to-left) and French.

The app has four sections: **Chat**, **My Templates**, **Library** and **Settings**.

> **Verification status.** The features below are implemented in code. The Android build, the
> automated tests and behavior on real devices have **not** been verified yet. Treat this
> document as a description of what the code does, not as a test report.

## What the app does

### AI providers (Settings)

- Add, edit, test, set as default and delete providers: OpenAI, Anthropic Claude, Google Gemini,
  DeepSeek, Mistral AI, Groq, xAI (Grok), OpenRouter, and other OpenAI-compatible services
  (custom base URL).
- For OpenAI, DeepSeek, Mistral AI, Groq, xAI and OpenRouter, "Load models" fetches the
  provider's real model list with your key and lets you pick a model. Capabilities are shown only
  when the provider reports them (Mistral: vision, tool calling, context; Groq: context; xAI:
  vision; OpenRouter: vision, JSON output, tool calling, reasoning, context). Anthropic, Gemini
  and generic compatible services: type the model ID.
- JSON mode is requested only where the provider supports it and the model is not known to lack
  it; otherwise the same model is asked for JSON through instructions. Reasoning text of
  thinking models (DeepSeek reasoner, xAI, OpenRouter, Mistral) is never shown as the answer.
- You supply your own API key and model ID. The app ships with no keys and no built-in provider.
- **The default provider handles every AI task.** The app never switches provider on its own.
  If the default provider fails, the error is shown and you decide what to do.
- "Test connection" makes a real authenticated request that also checks the model ID (for the
  newer providers: the model must appear in the provider's model list; OpenRouter's key is
  checked separately because its catalog is public).

### Chat

- Your message and the recent conversation are sent to the default provider; the reply shown is
  the provider's actual answer. Failed requests show an error and are never saved as replies.
- Replies are streamed: the text appears while the provider sends it (OpenAI and compatible
  services, Anthropic Claude and Google Gemini all support streaming; a compatible service that
  ignores the streaming request is shown when its complete answer arrives). While a request
  runs, the progress line shows the real stage: connecting, processing, generating content,
  validating the result, creating the document.
- **Stop:** while the AI is working, the send button becomes a stop button. Stopping cancels the
  network request, starts no further step, deletes an unfinished document file and stores
  nothing from the stopped request ("Generation stopped."). A document that was already saved
  to the Library before you pressed stop stays there.
- Ask for a document ("write a report about…", "make a presentation…", "create a budget
  spreadsheet…") and the provider returns structured content that the app renders into a file.
- Conversations are stored on the device and can be renamed and deleted.
- Progress and streamed text are shown only in the conversation the request belongs to; the
  answer is always saved to that conversation, even if you switch to another one meanwhile.

### Built-in commands (Chat)

Type `/` in the message box to see the commands; keep typing to filter, tap one (or use the
arrow keys and Tab/Enter on a hardware keyboard) to insert it, then add your instructions and
send. Choosing a command never sends anything by itself. Command names and descriptions are
English in every app language.

| Command | What it does |
|---|---|
| `/docx`, `/pptx`, `/xlsx`, `/pdf` | Creates that file from the text after the command (same document engines and templates as a normal request). |
| `/summarize`, `/translate`, `/correct`, `/rewrite`, `/analyze` | Processes the text after the command; with no text, the most recent document in the conversation. |
| `/clear` | Opens "Clear Conversations?": *Clear Current Chat* empties the open conversation; *Delete All Conversations* (asks a second time) deletes every conversation and message. |

All AI commands go through the default provider and the central orchestrator, with streaming,
the stop button and progress like any other request. If a command needs content that is missing,
the app asks for it and keeps your text. `/clear` never deletes Library documents, templates,
providers or API keys.

### Offline Formatting Agent (Chat)

Organizes long pasted text into a document **on the device**. Phase 1 uses local, deterministic
rules only: no AI model is loaded or downloaded, no internet connection, AI provider or API key is
needed, and nothing is sent anywhere.

**When it appears.** When you paste at least 500 characters into the Chat message box at once, a
small card appears above the box: *Organize this content?* with **Format Offline** and
**Dismiss**. The app only compares the message box text before and after each edit; it never
reads the clipboard. Typing, voice input and short messages never show it, the same pasted text
is suggested only once, and nothing is analyzed until you tap Format Offline. Dismiss (or sending
the message) leaves your text exactly as it is.

**What it does.** Format Offline analyzes the pasted text and opens a preview:

- **Detected structure:** headings (Markdown `#`, underlined titles, numbered sections such as
  `2.1 Scope`, ALL CAPS lines, short `Title:` lines, short standalone Title Case or Arabic lines),
  numbered and bulleted lists (`-`, `*`, `•`, `1.`, `1)`, `(a)`, `١-`, indented continuation
  lines), and tables (Markdown pipe tables, tab-separated rows, or three or more rows aligned in
  columns). Paragraph breaks are kept; lines wrapped in the middle of a sentence are joined.
  Headings guessed by the rules are highlighted in the preview with their confidence.
- **Document type:** report, letter, proposal, meeting notes, structured data or general
  document, guessed from English, French and Arabic keywords and the layout. This is pattern
  matching, not AI understanding; when unsure it says "General document". It does not change
  the content.
- **Your words are kept.** The agent organizes and formats; it never summarizes, translates,
  corrects, reorders or adds content. Only whitespace and structure markers (bullet characters,
  `#`, table pipes) are changed. Every result is checked word by word against your text
  (numbers and dates included); if the structure cannot be verified, your original paragraphs are
  kept instead.

**Formats.** Word (default for prose) and PDF are always available. Excel is offered only when a
table was detected (each table becomes a sheet, values are kept exactly as written; other text
goes to a "Text" sheet). PowerPoint is created only when you choose it (each heading starts a
slide). One file is created per confirmation.

**Templates** come from My Templates: the template attached to the chat (or picked in the
preview) first, otherwise your default template for that format, otherwise a built-in clean
formatting that adds no header or footer text. A template of the wrong type is never used
silently: the preview says why it was not applied. The template file is only read, never
changed. Limits are reported instead of hidden: PDF uses the template's colors and margins only;
a missing template file means only its saved colors and fonts apply; a template whose layout the
engine cannot use is reported after generation.

**Confirm and save.** *Apply Formatting* creates the file with the existing document engines
(nothing is in the Library yet). *Save to Library* stores it in the Library and adds a note to the
conversation; *Cancel* at any step stops the work and deletes the unsaved file. Offline documents
are kept out of the context sent to your AI provider unless you run an AI action on them.

**Current limitations.** All section headings use one heading style (levels are shown in the
preview but not reproduced in the file); numbered list items keep their own numbers as text;
nested lists are flattened; PowerPoint slides with long paragraphs may need resizing; the PDF
footer is the app's standard footer; texts over 200,000 characters are not formatted offline.

### Voice conversation

- Tap the microphone to start: the app listens, sends what you said to the default provider,
  shows the reply and reads it aloud, then listens again until you end the conversation.
- Works in Arabic, English and French (Settings > Voice conversation language). Replies are read
  with a voice that matches the language of the reply.
- Any assistant message can be read aloud with play / pause / resume / stop.
- **Device requirements.** Speech recognition and text-to-speech are provided by services
  installed on the device (on most phones, Google's). Recognition usually needs a network
  connection, and Arabic or French need the matching language/voice data installed.
- **Not available:** wake word ("Hey Bubble"), background or always-on listening. The
  microphone is only open while the app is on screen during a voice conversation, and it closes
  when the app goes to the background.

### Scan a document (inside Chat)

1. Attach > **Scan Document** opens the Google document scanner (capture, crop, rotate,
   perspective correction). If the scanner is unavailable you can pick photos from the gallery
   instead; those are used as they are.
2. Text is recognized **on the device** with Tesseract, using bundled models for Arabic, English
   and French. Page images are not uploaded anywhere.
3. You review and correct the text page by page, can change the text language, add or remove
   pages, and choose a template.
4. The app generates an editable DOCX (real text, not page images) and saves it in the Library.

Structure that is kept where it can be detected: page order, paragraphs, headings (short lines
ending with ":" or in capitals), bulleted / numbered lists, and simple tables with clear columns.

Limits to expect: recognition quality depends on the scan; handwriting, stamps, complex
multi-column layouts and merged table cells are not reconstructed; fonts, colors and images of
the original page are not reproduced.

Optional: "Clean up text with AI" sends the recognized **text** (never the images) to the default
provider, and only after you confirm.

### My Templates

- Import DOCX, PPTX, XLSX and PDF files (up to 50 MB). The file type is checked from the file's
  content. The original template file is never modified; generated files are separate.
- Preview, duplicate, delete, and set one default template per file type (PDF included).
- **Generation settings** per template:
  - *Use the template file's design* – keeps the template's own styles and structure. Turned
    off, only its colors and fonts are applied to a newly built file.
  - *Keep the template's existing content* – adds the new content after what is already in the
    template (text, slides or sheets) instead of replacing it.

| | What is kept from the template |
|---|---|
| DOCX | Styles, headers, footers, page size and margins. `{{TITLE}}`, `{{SUBTITLE}}`, `{{DATE}}` and `{{BODY}}` placeholders are filled. |
| PPTX | Slide masters, layouts and theme. New slides use the template's title and content layouts. |
| XLSX | Cell styles and theme. New sheets use SmartBubble's header and cell styles added to the template's style sheet. |
| PDF output from a Word template | Colors and margins only (PDF output is drawn by the app, not built from a template file). |
| PDF template | Its measured layout and styles (see below). |

Formatting is preserved where the engines support it; it is not guaranteed to be identical.
Charts, images inside slides, pivot tables, macros and PowerPoint speaker notes are not generated.

#### PDF templates

A PDF can be imported as a template like an Office file. The original PDF is copied into the
app's private storage, never changed, and stays the visual reference: the preview shows its first
page (rendered on the device) and *Open original PDF* opens a read-only copy in a PDF viewer.

At import the PDF is measured **on the device** (no AI provider, no OCR service, nothing
uploaded): page size and orientation, margins, header and footer text and positions, page
numbers, fonts and sizes of body text and headings, text and heading colors, alignment, line and
paragraph spacing, list markers and indent, ruled tables (border color, header-row fill, cell
alignment), page background and colored shapes. Only the first three pages are analyzed. The
preview lists every measured value; values that were not found are shown as "Not found".

| Output | What a PDF template gives |
|---|---|
| DOCX | Page size, orientation, margins, fonts, sizes, colors, alignment, spacing, list indent, table borders and header fill, page background, and a real Word header/footer with the PDF's own header/footer text and a page-number field. Colored shapes are not reproduced. |
| PDF | Page size, margins, background, colored shapes, header/footer text and page numbers at their measured positions, fonts mapped to the closest Android family (serif, sans-serif or monospace), sizes, colors, alignment and spacing. |
| XLSX | Only when the PDF contains a table: its font and table header color. Otherwise the preview explains why it is not used. |
| PPTX | Not supported: a PDF template is never used for presentations. |

PDF templates follow the usual priority (selected template, then default, then the built-in
formatting). For Word output the default Word template comes first and the default PDF template
is used only when there is none; for PDF output it is the other way round. A default PDF template
that does not fit the output (for example PowerPoint) is reported and the built-in formatting is
used.

Limits: the result is close to the PDF, not an exact copy. Fonts that are not on the device are
replaced in PDF output; text inside images, gradients and vector artwork are not reproduced.
Scanned (image-only) PDFs are kept and previewed, but only their page size can be used; the app
says so instead of treating them as empty. Encrypted or password-protected PDFs and damaged PDFs
are refused with a clear message. Only Flate-compressed PDF content can be measured; other
compressions are reported as partially read.

### Library

Generated and scanned documents with open, share, rename, delete and "export as PDF" (a text
export, not a visual copy of the Office file).

### App icon

The launcher icon is an adaptive icon built from vector drawables (`ic_launcher_background`,
`ic_launcher_foreground`, `ic_launcher_monochrome` for themed icons) with PNG fallbacks for
Android 7.x. The in-app logo (`smart_bubble_logo`) uses the same artwork.

## Privacy and security

- **API keys** are encrypted with the Android Keystore and stored only on the device. They are
  excluded from backup and device transfer, so they must be entered again after a restore.
- **What leaves the device:** only requests to the AI provider you configured – your messages,
  recent conversation context, the text of AI-generated documents in that conversation, and
  text you explicitly send with an AI action. Scanned page images never leave the device, and
  the text of scanned documents is sent only when you choose an AI action on it. The Offline
  Formatting Agent sends nothing: it runs entirely on the device.
- All network traffic uses HTTPS. There are no analytics or advertising SDKs.
- Generated text is also copied to the clipboard after a document is created.
- Speech recognition and text-to-speech are performed by the device's own services under their
  privacy terms.

## Building

Requirements: JDK 17 or newer and the Android SDK (compile SDK 36). The Gradle wrapper
downloads Gradle 9.3.1.

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # unit tests
```

A debug build needs no signing setup: without `debug.keystore` in the project root the standard
Android debug key is used.

The first build writes the Room database schema to `app/schemas/`; commit that file so future
database migrations can be checked against it.

### Signed release

The release build reads the upload keystore from the environment:

| Variable | Meaning |
|---|---|
| `KEYSTORE_PATH` | Path to the keystore (default: `my-upload-key.jks` in the project root) |
| `STORE_PASSWORD` | Keystore password |
| `KEY_PASSWORD` | Password of the signing key |
| `KEY_ALIAS` | Alias of the signing key in the keystore (default: `smartbubble`) |
| `VERSION_CODE`, `VERSION_NAME` | Optional overrides (defaults: `1`, `1.0`) |

```bash
./gradlew assembleRelease bundleRelease
# app/build/outputs/apk/release/app-release.apk
# app/build/outputs/bundle/release/app-release.aab
```

Code shrinking (R8) is currently off; keep rules are prepared in `app/proguard-rules.pro`.

### GitHub Actions

`.github/workflows/Release-apk.yml` runs when started manually (Actions > Release APK > Run
workflow) or when a tag `v*` is pushed. It always builds the debug APK. It builds the signed
release APK and AAB only when these repository secrets exist (Settings > Secrets and variables >
Actions):

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 <your keystore>.jks` |
| `STORE_PASSWORD` | Keystore password |
| `KEY_PASSWORD` | Key password |

The workflow signs with the key alias `smartbubble`, set once as `KEY_ALIAS` at the top of the
workflow file and used both for the keystore check and by Gradle. Before building, the workflow
checks the keystore, the alias and both passwords and reports which secret is wrong, without
printing any secret; after building, it confirms the APK is signed with that key.

Unit tests run when the manual input `run_tests` is ticked or the repository variable
`RUN_UNIT_TESTS` is `true`. Outputs are workflow artifacts; the workflow does not create a
GitHub release and does not upload to Google Play. Never commit a keystore or passwords.

To create a keystore (only if you do not have one yet; never replace an existing release key):

```bash
keytool -genkeypair -v -keystore release.jks -alias smartbubble -keyalg RSA -keysize 2048 -validity 10000
```

Keep the keystore and its passwords safe: updates must be signed with the same key.

## Technology

Kotlin, Jetpack Compose (Material 3), a single `SmartBubbleViewModel`, Room (explicit
migrations, no destructive fallback), DataStore, OkHttp, Android Keystore, document engines
written in Kotlin on `java.util.zip` and Android `PdfDocument`, Google ML Kit document scanner
(capture only), Tesseract 5 through Tesseract4Android with `tessdata_fast` models (Apache 2.0),
Android `SpeechRecognizer` and `TextToSpeech`.
