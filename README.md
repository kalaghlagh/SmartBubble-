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
  and OpenAI-compatible services (custom base URL).
- You supply your own API key and model ID. The app ships with no keys and no built-in provider.
- **The default provider handles every AI task.** The app never switches provider on its own.
  If the default provider fails, the error is shown and you decide what to do.
- "Test connection" makes a real authenticated request that also checks the model ID.

### Chat

- Your message and the recent conversation are sent to the default provider; the reply shown is
  the provider's actual answer. Failed requests show an error and are never saved as replies.
- Ask for a document ("write a report about…", "make a presentation…", "create a budget
  spreadsheet…") and the provider returns structured content that the app renders into a file.
- Conversations are stored on the device and can be renamed and deleted.

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

- Import DOCX, PPTX and XLSX files (up to 50 MB). The file type is checked from the file's
  content. The original template file is never modified; generated files are separate.
- Preview, duplicate, delete, and set one default template per file type.
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
| PDF | Colors and margins only (PDF output is drawn by the app, not built from a template file). |

Formatting is preserved where the engines support it; it is not guaranteed to be identical.
Charts, images inside slides, pivot tables, macros and PowerPoint speaker notes are not generated.

### Library

Generated and scanned documents with open, share, rename, delete and "export as PDF" (a text
export, not a visual copy of the Office file).

## Privacy and security

- **API keys** are encrypted with the Android Keystore and stored only on the device. They are
  excluded from backup and device transfer, so they must be entered again after a restore.
- **What leaves the device:** only requests to the AI provider you configured – your messages,
  recent conversation context, the text of AI-generated documents in that conversation, and
  text you explicitly send with an AI action. Scanned page images never leave the device, and
  the text of scanned documents is sent only when you choose an AI action on it.
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
