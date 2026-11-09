# Smart Bubble — AI Office & Content Creation Assistant

**Smart Bubble** is a full native Android productivity workspace that synthesizes enterprise Word documents (.docx), PowerPoint presentations (.pptx), Excel spreadsheets (.xlsx), and PDF documents, driven by a non-negotiable **Central AI Orchestrator** architecture and personal design reference system (**My Templates**).

---

## 🌟 Core Architecture & Capabilities

### 1. Non-Negotiable AI Orchestration
- **Default AI Provider = Central AI Orchestrator:** The user-selected primary provider (e.g. Anthropic Claude 3.5 Sonnet, Google Gemini 1.5 Pro, OpenAI GPT-4o, DeepSeek R1) acts as the single central orchestrator.
- **Specialized Provider Coordination:** Specialized sub-models (e.g. GPT-4o for complex financial formulas, Gemini for multimodal spatial parsing, DeepSeek for deep logical reasoning) operate strictly when coordinated and validated by the Central Orchestrator.
- **No Fake Office Files:** AI providers generate validated structured schemas; native document engines compile real, 100% compliant OpenXML (OOXML) files and PDFs.
- **Android Keystore Encryption:** API keys are protected using hardware-backed AES-256-GCM encryption with zero plaintext logging.

### 2. Genuine Native Document Engines
- **Word Document Engine (`DocxEngine`):** Generates valid OOXML `.docx` files with paragraph styling, heading hierarchy, tables with custom cell fills and borders, bullet lists, signature blocks, and page margins.
- **PowerPoint Engine (`PptxEngine`):** Generates valid OOXML `.pptx` presentation decks (16:9 widescreen) with master slide layouts, formatted cards, title slides, bullet items, and speaker notes.
- **Excel Engine (`XlsxEngine`):** Generates valid OOXML `.xlsx` workbooks with multiple worksheets, styled header rows, formatted numbers, formulas (`SUM`, `AVERAGE`), and custom column widths.
- **PDF Engine (`PdfEngine`):** Generates multi-page, print-ready PDF files using Android's native `android.graphics.pdf.PdfDocument` with pagination, headers, dividing rules, tables, and footers.

### 3. "BOTH" Delivery System
- Real Office File + Clipboard Content!
- After generating any document:
  1. Compiles the real binary Office or PDF file.
  2. Verifies file existence, size, and OpenXML package structure.
  3. Saves metadata to local Room Database.
  4. Copies textual content directly to the Android Clipboard.
  5. Provides instant "Open in Word/PPT/Excel" via `FileProvider` (`ACTION_VIEW`).
  6. Supports native Android Share Sheet (`ACTION_SEND`).

### 4. My Templates — Personal Design & Formatting System
- **File Upload:** Upload existing DOCX, PPTX, XLSX, and PDF reference files via Android Storage Access Framework (SAF).
- **Template Analysis Engine:** Inspects OOXML packages, extracts fonts, colors, margins, table styles, and slide master layouts into a persistent `TemplateProfile`.
- **Application Modes:**
  - `Smart Adaptation` (Default): Preserves brand identity while adapting layout to generated content.
  - `Strict Match`: Preserves source template structure and formatting as closely as possible.
  - `Style Only`: Applies colors, fonts, and general design language.

### 5. Knowledge Center & Google NotebookLM Integration
- Official Google NotebookLM API integration schema (`https://notebook.googleapis.com/v1`).
- Source synchronization (PDF, DOCX, PPTX, XLSX, TXT, Web URLs, Notes).
- Grounding: Generate documents directly from selected knowledge sources.

### 6. Social Media Content Reading & Explanation
- Receives shared posts and URLs from X, Facebook, and Web browsers via Android `ACTION_SEND`.
- Automatically detects language, summarizes key arguments, provides executive explanations, translates to preferred language, and speaks aloud via TTS.

### 7. Voice Assistant
- Android `SpeechRecognizer` with push-to-talk.
- Android `TextToSpeech` with multilingual synthesis (Arabic, English, French).
- State flow: `IDLE` → `LISTENING` → `RECOGNIZED` → `PROCESSING` → `RESPONDING` → `COMPLETED`.
- Optional wake word ("Hey Bubble", disabled by default).

### 8. Full Multilingual & Arabic RTL Support
- Native Arabic RTL support with right-to-left layout mirrors and localized typography.
- English and French language strings.

---

## 🛠️ Technology Stack
- **Language:** 100% Kotlin
- **UI Framework:** Jetpack Compose with Material 3
- **Architecture:** Clean Architecture + MVVM (`SmartBubbleViewModel`)
- **Database:** Room Database (Documents, Templates, Knowledge Sources, Chat Sessions)
- **Preferences:** Jetpack DataStore Preferences
- **Security:** Android Keystore (AES-256-GCM)
- **Document Engines:** Pure Kotlin OOXML ZIP Packaging Engine (`java.util.zip`) & Android `PdfDocument`
- **Voice:** Android SpeechRecognizer & TextToSpeech

---

## 📦 Release Artifacts
- **Release APK:** Generated at `app/build/outputs/apk/release/app-release.apk`
- **Release AAB:** Generated at `app/build/outputs/bundle/release/app-release.aab`
