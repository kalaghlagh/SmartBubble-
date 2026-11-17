# Smart Bubble — Open-Source Document Intelligence Audit

Date: 2026-11-17. Audit only: no application source, Gradle, schema or UI change; nothing committed.

Legend: **VERIFIED** (seen in source or measured) · **PARTIALLY VERIFIED** (source seen, behaviour not measured, or second-hand provenance) · **NOT VERIFIED** · **RECOMMENDATION**.

## 0. What was inspected

| Repository | Commit inspected | Last commit | Language | Tests |
|---|---|---|---|---|
| kalaghlagh/SmartBubble- | branch `feature/advanced-document-extraction`, HEAD `a26652d` (main = `c06d02e`) | 2026-11-17 | Kotlin, Compose | 57 test files, 483 `@Test` |
| egdels/makeacopy | `13b4423` | 2026-11-13 | Java (456 files), Python tooling (53) | 255 test files |
| Azyrn/Scanly | `8da3cf5` | 2026-08-05 | Kotlin (197 files), Compose | 49 test files |
| alexeygrigorev/openscan | `0ebc806` | 2026-10-23 | Kotlin (70 files), Compose | 28 test files |

All three external repositories exist and contain the code they describe (shallow clones, read only, nothing executed).
Hugging Face (model cards) was blocked by the session's network proxy: model-card licenses could not be read directly.

Smart Bubble working tree also holds uncommitted fixes from the interrupted run-#71 task (5 files under `extraction/`); they were not touched by this audit.

---

## 1. Current Smart Bubble architecture (VERIFIED from source)

```
             Chat (SmartBubbleViewModel)        Thesis Builder (ThesisController.startExtraction)
                     |                                         |
          DocumentOcrEngine.processPages              DocumentExtractionEngine.extract
          (ScannedPage, OcrReviewDialog)                       |
                     \___________________ shared _____________/
                                         |
       +---------------------------------+------------------------------------+
       | PDF? PdfDocumentReader + PdfContentScanner (pure Kotlin)              |
       |      PdfTextExtraction.classify -> TEXT page: words/lines/rulings     |
       |                                 -> SCANNED page: render -> OCR        |
       | Image: PageImageLoader -> OcrPageReader                               |
       |   prepare (OcrPreprocessor: grey, contrast) + Deskew + rotation       |
       |   OcrEngineRegistry (lazy TesseractOcrEngine per OcrConfig)           |
       |   ScriptDetector -> OcrRouting.plan -> CandidateEvaluator             |
       |   missed-band recovery (OcrDecisions)                                 |
       +---------------------------------+------------------------------------+
                                         |
               LayoutAnalyzer (headings, lists, captions, paragraphs, columns*)
               TableReconstructor (rulings / aligned columns, per-cell re-read)
                                         |
               ExtractedDocument / ExtractedPage / ExtractedBlock (+RegionMeta)
                                         |
       Review UI (ThesisExtraction.kt, OcrReviewDialog, ExtractionReport)
       ExtractionToThesis (AT_CURSOR, APPEND, AS_PARAGRAPHS, HEADING_AND_CONTENT, TABLES_ONLY)

  My Templates: TemplateAnalysisEngine -> PdfTemplateAnalyzer / DocxTemplateAnalyzer (separate path, not the extraction engine)
  Library: DocumentEntity in Room v7 (no extraction results stored)
  (* column splitting is in the uncommitted working tree only)
```

| Area | Finding | Status |
|---|---|---|
| OCR runtime | Tesseract4Android 4.9.0, LSTM, `tessdata_fast` ara 1.43 MB, eng 4.11 MB, fra 1.13 MB in `assets/tessdata` | VERIFIED |
| OCR abstraction | `extraction/OcrEngine.kt`: `interface OcrEngine`, `TesseractOcrEngine`, `OcrEngineRegistry` (lazy, load times) | VERIFIED |
| Digital PDF | own pure-Kotlin reader `template/pdf/PdfDocumentReader.kt`, `PdfContentScanner.kt`; `extraction/PdfTextExtraction.kt` | VERIFIED |
| Scan capture | `play-services-mlkit-document-scanner` 16.0.0-beta1 (Google ML Kit terms, proprietary) | VERIFIED |
| Preprocessing | `ocr/OcrPreprocessor.kt`, `extraction/Deskew.kt` | VERIFIED |
| Data model | `extraction/ExtractionModel.kt`: Box, TextWord, TextLine, TableCell, RegionMeta, ExtractedBlock (Heading/Paragraph/ListItem/Table/Caption/PageNumber), ExtractedPage, ExtractedDocument | VERIFIED |
| Room | `SmartBubbleDatabase` version 7, 13 entities, no extraction entity | VERIFIED |
| Background | coroutines (`Dispatchers.Default`), cancellation → `OcrEngineRegistry.stopAll()`; no WorkManager | VERIFIED |
| Tests | JVM extraction tests (36 local), `ExtractionDeviceTest` (9), `OcrBenchmarkDeviceTest` (6) | VERIFIED |
| Code size | extraction 13 files / 2 122 lines; ocr 7 / 801; template 11 / 3 433; thesis 26 / 8 552 | VERIFIED |

Duplicates / redundancy found:
- Two OCR entry points (`DocumentOcrEngine.processPages` for Chat, `DocumentExtractionEngine.extract` for Thesis) share the same reader but return different result types (`ScannedPage` vs `ExtractedDocument`). RECOMMENDATION: Chat should consume `ExtractedDocument` too.
- Template analysis has its own PDF analysis path; it does not use `ExtractedDocument`. Not a defect (templates need styles, not text), but structure detection is duplicated.
- No redundant third-party libraries found.

### Measured accuracy of the current engine (CI emulator, generated clean prints, not real photos)

Run #71 (`a26652d`), `OcrBenchmarkDeviceTest`:

| Case | CER | WER | Cells | Numeric cells | Lists | ms/page | Peak MB |
|---|---|---|---|---|---|---|---|
| english-doc | 0.000 | 0.000 | 9/9 | 4/4 | 2/2 | 1 186 | 78 |
| french-doc | 0.011 | 0.138 | 9/9 | 6/6 | – | 1 164 | 55 |
| mixed AR/FR/EN | 0.040 | 0.103 | – | – | – | 1 170 | 60 |
| arabic-doc | 0.158 | 0.191 | 8/9 | 6/6 | **1/3** | 2 483 | 90 |
| columns + merged cell | 0.444 | 0.524 | 7/8 | 2/2 | – | 1 132 | 73 |
| pdf mixed (text + scan) | 0.000 | 0.000 | – | – | – | 660 | 98 |

Device tests: 50 run, 5 failed. Open failure classes: Arabic list lines dropped by Tesseract layout (even in uniform-block mode), Arabic table labels read as digits, "P1"→"p1", Arabic word read as digits in a mixed line, two-column order. ML Kit comparison could not run (its components are not registered in the app process).

---

## 2. External projects — what they actually contain

### A. MakeACopy (egdels/makeacopy) — VERIFIED

- Apache-2.0 (`LICENSE`, `NOTICE`, per-file headers "Copyright 2026 Christian Kierdorf").
- OCR = PaddleOCR PP-OCRv5 **mobile** on ONNX Runtime, CPU. Tesseract, OpenCV, Noto fonts all removed (NOTICE items 1, 3–7).
- Models shipped as reduced-operator **ORT** files with SHA-256 manifest:

| File (`app/src/main/assets/paddleocr/v5/`) | Bytes |
|---|---|
| `det.ort` (PP-OCRv5_mobile_det) | 4 898 208 |
| `arabic_PP-OCRv5_mobile_rec.ort` + dict (749 entries) | 8 106 064 |
| `latin_PP-OCRv5_mobile_rec.ort` + dict (838) | 8 149 136 |
| `en_PP-OCRv5_mobile_rec.ort` + dict (438) | 7 955 536 |
| `PP-OCRv5_mobile_rec.ort` (multilingual/zh) | 16 641 888 |
| 5 other scripts (cyrillic, eslav, devanagari, th, el) | ~8 MB each |

- Arabic dictionary content (VERIFIED by counting): 242 Arabic letters/marks, Latin A–Z/a–z, Western digits, Arabic-Indic digits, 14 accented Latin letters. The Arabic model can emit mixed lines; its Latin quality is NOT VERIFIED.
- ORT runtime is **built from source** (`scripts/build_onnxruntime_android.sh`, submodule `external/onnxruntime`, reduced ops config `paddleocr_v5.required_operators.config`); the AAR/so are not in the repo.
- Language routing: `utils/ocr/paddle/PaddleLanguageRouter.java` maps Tesseract-style specs to **one** recognition model per run, priority order `en, latin, …, arabic`. "ara+eng" therefore selects the English model only. No per-line script routing (VERIFIED in `resolveRecModel`).
- Tables: none (no table code found). Layout: geometric only.

### B. Scanly (Azyrn/Scanly) — VERIFIED with caveats

- Apache-2.0 + NOTICE (Section 4(d) notice must be kept). No per-file headers.
- OCR = PaddleOCR on `com.microsoft.onnxruntime:onnxruntime-android` 1.27.0 (official Maven AAR), CPU only ("XNNPACK and NNAPI both abort natively on these graphs", `PaddleOcrEngine.kt:46`), 1–4 threads.
- Bundled `.onnx` assets (`app/src/main/assets/ocr/`): `det_v6_small.onnx` 9.9 MB, `rec_v6_small.onnx` 21.2 MB, `rec_v5_arabic.onnx` 8.0 MB, `layout_s.onnx` (PP-DocLayout-S) 4.9 MB, `cls_docori.onnx` 6.8 MB, `cls_textline.onnx` 1.0 MB. "PP-OCRv6" naming: files exist; upstream identity NOT VERIFIED (Hugging Face blocked).
- Downloads on demand from `https://huggingface.co/PaddlePaddle/...` with SHA-256 check against the LFS pointer (`PaddleModelStore.kt`): other scripts, `SLANet_plus` (tables), `UVDoc` (unwarp).
- Mixed script: `ScriptMerge.kt` merges the script model and the universal (Latin) model readings of the **same line crop** by CTC character x-position; no image cutting. `RtlText.kt` visual→logical and word-space recovery. Directly addresses Smart Bubble's "Arabic word ↔ Latin term" failures. Accuracy NOT MEASURED.
- Tables: `TableDecoder.kt` decodes SLANet HTML tokens with row/col spans. Layout: `DocLayout.kt` (23 PP-DocLayout-S labels), `DocumentStructure.kt`, `ReadingOrder.kt`.
- PDF: renders pages with `PdfRenderer` and OCRs them; **no digital text extraction**. DOCX export = page images only (`ScanExporter.kt:85`).
- Not reusable as a product base: Google Mobile Ads (`play-services-ads`), ML Kit, 11 cloud AI providers, obfuscated "free-tier" API keys compiled into BuildConfig from `local.properties`.

### C. OpenScan (alexeygrigorev/openscan) — VERIFIED

- Apache-2.0. Capture/crop pipeline (`scan/ScanPipeline.kt` 1 850 lines, `PageCorners.kt`, `QuadPlausibility.kt`, `ScanFilters.kt`) on OpenCV 4.10.0; PDF via `pdfbox-android` 2.0.27.0; Room library.
- OCR = ML Kit Latin `TextRecognition` (`scan/PageOcr.kt`): **no Arabic**, plain text only, no layout or tables.
- `FeedbackUploader.kt` uploads debug payloads (opt-in).
- Nothing in it improves Smart Bubble's extraction; Smart Bubble already captures with the ML Kit document scanner.

---

## 3. Technical compatibility matrix

| Capability | MakeACopy | Scanly | OpenScan | Smart Bubble (today) |
|---|---|---|---|---|
| Android integration | VERIFIED (Java, Views, minSdk 29) | VERIFIED (Kotlin, Compose, minSdk 24) | VERIFIED (Kotlin, Compose, minSdk 26) | VERIFIED (Kotlin, Compose, minSdk 24) |
| Offline OCR | VERIFIED | VERIFIED | VERIFIED (ML Kit) | VERIFIED |
| Arabic recognition | VERIFIED (model present), accuracy NOT TESTED | VERIFIED (model present), NOT TESTED | NOT AVAILABLE | VERIFIED, measured (CER 0.158 synthetic) |
| French | VERIFIED (latin model), NOT TESTED | PARTIALLY VERIFIED (universal) | VERIFIED (ML Kit Latin), NOT TESTED | VERIFIED, CER 0.011 |
| English | VERIFIED, NOT TESTED | PARTIALLY VERIFIED | VERIFIED, NOT TESTED | VERIFIED, CER 0.000 |
| Mixed-script | NOT AVAILABLE (one model per run) | VERIFIED code (`ScriptMerge`), NOT TESTED | NOT AVAILABLE | VERIFIED code (routing + evaluator), CER 0.040 |
| Digital PDF text | NOT AVAILABLE (OCR on renders) | NOT AVAILABLE | NOT AVAILABLE | VERIFIED |
| Scanned PDF OCR | VERIFIED | VERIFIED | PARTIALLY VERIFIED | VERIFIED |
| Image preprocessing | VERIFIED (pure Java raster, DocQuad ML corners) | VERIFIED (`ImageOps`, UVDoc optional) | VERIFIED (OpenCV) | VERIFIED (grey/contrast/deskew/rotation) |
| Layout detection | PARTIALLY (geometric multi-column policy) | VERIFIED (PP-DocLayout-S) | NOT AVAILABLE | VERIFIED (rule-based) |
| Table reconstruction | NOT AVAILABLE | VERIFIED code (SLANet, download) | NOT AVAILABLE | VERIFIED (rulings + alignment), measured |
| Borderless tables | NOT AVAILABLE | PARTIALLY (SLANet), NOT TESTED | NOT AVAILABLE | VERIFIED, 9/9 cells synthetic |
| Reading order | VERIFIED (`OcrDocReadingOrder`, `MultiColumnLayoutPolicy`) | VERIFIED (`ReadingOrder.kt`) | NOT AVAILABLE | PARTIALLY (columns failing in run #71) |
| RTL support | VERIFIED (`BidiWordOrder`) | VERIFIED (`RtlText`) | NOT AVAILABLE | VERIFIED |
| Multi-page | VERIFIED | VERIFIED | VERIFIED | VERIFIED |
| Searchable PDF | VERIFIED (`PdfTextLayer`, `GlyphlessFont`) | NOT VERIFIED | NOT AVAILABLE | NOT AVAILABLE |
| DOCX export | NOT AVAILABLE | PARTIALLY (images only) | NOT AVAILABLE | VERIFIED (Thesis DOCX writer, real text) |
| Structured JSON | VERIFIED (`WordsJson`, `OcrJsonStore`) | VERIFIED (JSON export) | NOT AVAILABLE | PARTIALLY (in-memory model, no serialization) |
| Model management | VERIFIED (bundled, SHA-256 gate at build) | VERIFIED (bundled + download + SHA-256) | NOT APPLICABLE | PARTIALLY (bundled tessdata, no versioning) |
| Low-memory operation | PARTIALLY (lazy rec sessions, light edition) | PARTIALLY (session cache, thread cap) | NOT TESTED | PARTIALLY (lazy engines, 55–98 MB peak measured) |

---

## 4. Source reuse matrix

| # | Repo / path | Main class | Responsibility | License | Reuse mode | Complexity |
|---|---|---|---|---|---|---|
| 1 | makeacopy `assets/paddleocr/v5/det.ort`, `arabic_…rec.ort`, `latin_…rec.ort` (+dicts, SHA256SUMS) | – | PP-OCRv5 mobile det + Arabic + Latin rec, ORT format | Apache-2.0 (PaddleOCR), PARTIALLY VERIFIED | **Model weights, reuse** (or regenerate with their scripts) | Low |
| 2 | makeacopy `utils/ocr/paddle/PaddleDetOrtRunner.java`, `DbPostProcessor.java`, `Letterbox.java`, `Quad.java`, `PaddleQuadCropper.java` | det runner | Detection pre/post-processing (DB) | Apache-2.0 | **Adapt (port to Kotlin)** | Medium |
| 3 | makeacopy `utils/ocr/paddle/PaddleRecOrtRunner.java`, `CtcDecoder.java`, `RecDictLoader.java` | rec runner | Recognition + CTC decoding | Apache-2.0 | **Adapt**; must expose per-char x positions (as Scanly does) | Medium |
| 4 | makeacopy `utils/ocr/BidiWordOrder.java` | BidiWordOrder | word-level bidi order | Apache-2.0 | Algorithm reference; SB has its own RTL handling | Low |
| 5 | makeacopy `utils/ocr/MultiColumnLayoutPolicy.java` (795 lines) | policy | column clustering by line geometry | Apache-2.0 | **Algorithm reference** for SB column fix | Medium |
| 6 | makeacopy `scripts/convert_paddleocr_v5_*.{sh,py}`, `ort/config/*` | – | Paddle → ONNX → reduced ORT pipeline | Apache-2.0 | Build tooling (offline, not in app) | Medium |
| 7 | makeacopy `utils/export/PdfTextLayer.java`, `GlyphlessFont.java` | – | invisible text layer for searchable PDF | Apache-2.0 | Later, optional | Medium |
| 8 | Scanly `core/ocr/paddle/ScriptMerge.kt`, `RtlText.kt` | ScriptMerge | Arabic + Latin reading merge by CTC position; visual→logical | Apache-2.0 + NOTICE | **Adapt (Kotlin, near-direct)** | Low–Medium |
| 9 | Scanly `core/ocr/paddle/TableDecoder.kt` + SLANet_plus model | TableDecoder | table structure with spans | Apache-2.0 (code); model license NOT VERIFIED | Optional pack, phase 3 | Medium |
| 10 | Scanly `core/ocr/paddle/DocLayout.kt`, `DocumentStructure.kt` + `layout_s.onnx` | – | PP-DocLayout-S regions | Apache-2.0; model NOT VERIFIED | Optional, after benchmark | Medium |
| 11 | Scanly `core/ocr/paddle/PaddleModelStore.kt` | model store | on-demand packs, SHA-256 verification, states | Apache-2.0 | **Adapt pattern** (no silent download) | Low |
| 12 | OpenScan `scan/*` | – | OpenCV capture/crop | Apache-2.0 | **Do not integrate** (ML Kit scanner already present; OpenCV size) | – |

Must not be reused: Scanly ads, AI providers, `core/security/Secrets.kt` key scheme; ML Kit text recognition (no Arabic, proprietary); OpenCV; MakeACopy View-based review UI (SB is Compose and already has one); OpenScan `FeedbackUploader`.

Keep untouched in Smart Bubble: PDF reader and `PdfTextExtraction` (no external project extracts digital PDF text); `LayoutAnalyzer`, `TableReconstructor` (rulings), `ExtractionModel`, `ExtractionToThesis`, review UI, `CandidateEvaluator`, `OcrEngine`/`OcrEngineRegistry` seams.

---

## 5. License compliance

| Component | License | Commercial | Modify | Redistribute | Obligations | Status |
|---|---|---|---|---|---|---|
| MakeACopy code | Apache-2.0 | yes | yes | yes | keep LICENSE, NOTICE, headers; mark changes | VERIFIED |
| Scanly code | Apache-2.0 | yes | yes | yes | keep LICENSE + NOTICE (4(d) notice) | VERIFIED |
| OpenScan code | Apache-2.0 | yes | yes | yes | keep LICENSE | VERIFIED |
| PaddleOCR (code; models distributed by the project) | Apache-2.0 | yes | yes | yes | attribution, NOTICE | Repo license VERIFIED; per-model cards NOT VERIFIED (HF blocked); MakeACopy provenance doc states Apache-2.0 |
| PP-OCRv5 det/rec weights | Apache-2.0 (per PaddleOCR/PaddleX) | yes | yes | yes | attribution | PARTIALLY VERIFIED |
| SLANet_plus, PP-DocLayout-S, UVDoc weights | expected Apache-2.0 | – | – | – | – | **UNVERIFIED** — do not ship until model card read |
| Scanly "v6" det/rec files | unknown provenance | – | – | – | – | **UNVERIFIED** |
| ONNX Runtime | MIT | yes | yes | yes | include MIT notice | VERIFIED (MakeACopy third_party copy) |
| Tesseract / Tesseract4Android / tessdata_fast | Apache-2.0 | yes | yes | yes | attribution | VERIFIED |
| PdfBox-Android | Apache-2.0 | yes | yes | yes | attribution | VERIFIED (not recommended) |
| OpenCV 4.x | Apache-2.0 | yes | yes | yes | attribution | VERIFIED (not recommended) |
| Google ML Kit (scanner, text recognition) | Google APIs / ML Kit terms (proprietary) | yes, under terms | no | binary only | Google terms | already used by SB for scanning |

No copyleft component found in the recommended set. Smart Bubble currently ships no third-party notices screen: RECOMMENDATION add one (Apache NOTICE files, MIT text) before shipping any of the above.

---

## 6. Recommended architecture (RECOMMENDATION)

Keep the existing structure; add one OCR backend behind the existing interface.

```
DocumentExtractionEngine (existing; becomes the single API for Chat, Thesis, Templates, Library)
 ├─ DocumentRouter            = existing per-page plan in DocumentExtractionEngine (PDF text vs scan)
 ├─ PdfProcessor              = existing PdfDocumentReader / PdfContentScanner / PdfTextExtraction
 ├─ ImagePreprocessor         = existing OcrPreprocessor + Deskew
 ├─ OcrRuntime                = OcrEngine interface (existing)
 │    ├─ TesseractOcrEngine   (existing; fallback until benchmark says otherwise)
 │    └─ PaddleOrtOcrEngine   (NEW: det + rec on ONNX Runtime; port of MakeACopy runners)
 ├─ ModelManager              = OcrEngineRegistry (existing) + model manifest/SHA-256 (pattern from Scanly)
 ├─ ScriptDetector            (existing)
 ├─ Script merge              (NEW: port of Scanly ScriptMerge for Arabic lines with Latin terms)
 ├─ LayoutAnalyzer / ReadingOrder (existing; column policy informed by MakeACopy MultiColumnLayoutPolicy)
 ├─ TableReconstructor        (existing; SLANet optional pack later)
 ├─ QualityEvaluator          = CandidateEvaluator (existing)
 └─ DocumentRepository        (NEW, phase 4: Room table for Library results + corrections)
```

Processing for a scanned page with Paddle: preprocess → det (quads) → line crops → rec with the model of the line's script (Arabic or Latin) → for Arabic lines with suspected Latin, second Latin pass + ScriptMerge → words with boxes → existing LayoutAnalyzer / TableReconstructor / review.

Paddle's detector returns every text line as a box: it replaces Tesseract's layout pass, which is the source of the "dropped short Arabic list lines" failures (expected benefit, NOT MEASURED).

Technology stack: ONNX Runtime Android (official AAR first; reduced-ops build only if size measured too high), CPU EP, ≤4 threads. No OpenCV, no Paddle Lite, no TFLite, no Python.

### Unified data model — extend, do not replace

Existing `ExtractedDocument` covers page index, source, blocks with type, text, box (RegionMeta), script, direction (`rtl`), confidence, review flag, table rows/cols/spans, list markers. Missing:

| Needed | Proposal |
|---|---|
| documentId, source file hash, provider, extraction date, engine/model versions | add to `ExtractedDocument` |
| readingOrder per element | index in `blocks` (implicit) → make explicit `RegionMeta.order` |
| original vs user-corrected text | `RegionMeta.originalText` set on first edit; edits never overwrite it |
| language (fr/en/ar), provenance per element | `RegionMeta.language`, `RegionMeta.engines` (exists) |
| Footnote, Image, TableRow/TableCell as elements | add `Footnote`, `Image` block types; keep table as one block |
| persistence | JSON (Moshi, already a dependency) in a new Room table keyed by file hash — only for Library (v8 migration, phase 4) |

```json
{
  "documentId": "sha256:4f1c…",
  "sourceType": "pdf",
  "sourceFile": "content://…/report.pdf",
  "provider": "local:paddle-v5+tesseract-4.9.0",
  "extractedAt": "2026-11-17T21:30:00Z",
  "warnings": ["p2:possible_missing_lines:1"],
  "pages": [{
    "page": 1, "width": 1240, "height": 1754, "direction": "rtl",
    "elements": [
      {"id": "p1-r0", "type": "heading", "order": 0, "text": "1. منطقة الدراسة",
       "box": [652, 118, 1119, 167], "script": "arabic", "language": "ar", "direction": "rtl",
       "confidence": 0.92, "needsReview": false, "engines": ["paddle-det", "arabic_rec"]},
      {"id": "p1-t5", "type": "table", "order": 5, "rtl": true,
       "rows": [[{"text": "البئر", "originalText": "البثر", "corrected": true, "confidence": 0.88}]]}
    ]
  }]
}
```

### Integration interfaces

- Chat: switch from `processPages`→`ScannedPage` to `ExtractedDocument`; review → select → insert into the input field (not sent). Existing OCR review dialog stays.
- Thesis Builder: already integrated (`ThesisController.startExtraction`, `ExtractionToThesis` insert modes); no change beyond the data-model fields.
- My Templates: keep `TemplateAnalysisEngine` for styles; optionally feed `ExtractedDocument` headings/tables for scanned references. OCR text alone is not a template.
- Library: phase 4 — store result + corrections by file hash; skip re-extraction when the hash is unchanged.

---

## 7. Dependency impact (estimates unless marked measured)

| Item | Size | Basis |
|---|---|---|
| PP-OCRv5 det.ort | 4.9 MB | measured (file) |
| arabic rec.ort | 8.1 MB | measured |
| latin rec.ort (covers fr + en) | 8.1 MB | measured |
| Core model set (det + arabic + latin) | **~21 MB** | sum of measured files |
| en rec.ort (optional, latin covers English) | 8.0 MB | measured |
| PP-DocLayout-S | 4.9 MB | measured (Scanly asset) |
| SLANet_plus | NOT MEASURED | download pack |
| ONNX Runtime Android official AAR, native lib | ~15–20 MB per ABI | ESTIMATE, not measured |
| ONNX Runtime reduced-ops build (MakeACopy approach) | a few MB per ABI | ESTIMATE |
| Tesseract4Android native + tessdata (removable later) | tessdata 6.7 MB measured; native NOT MEASURED | – |

Smart Bubble packs 3 ABIs (`arm64-v8a, armeabi-v7a, x86_64`) in one APK: a universal APK would carry ORT three times. RECOMMENDATION: ship as AAB (per-ABI splits by Play) or ABI splits; measure APK size in CI before and after.

New dependencies: `com.microsoft.onnxruntime:onnxruntime-android` only. Reused: Moshi (JSON), Room, coroutines, existing PDF reader. Remove: nothing until the benchmark proves Paddle better; then Tesseract + tessdata can go (≈ 6.7 MB + native).

---

## 8. Arabic and mixed-language risks

1. PP-OCRv5 Arabic model on Smart Bubble's failure classes is NOT MEASURED; the only evidence is file presence and dictionary coverage.
2. Mixed lines: MakeACopy cannot do them (one model per run); Scanly's merge is unmeasured. Script detection alone does not solve them.
3. CTC recognition outputs visual order: RTL conversion (`RtlText.visualToLogical`) must be correct for digits, Latin runs and punctuation; errors here reorder text silently.
4. Arabic-Indic vs Western digits: both in the Arabic dictionary; keep the digits as read, no normalisation in stored text.
5. Tables: SLANet trained mainly on Chinese/English tables; RTL tables NOT VERIFIED. Keep the existing ruling-based reconstructor as primary.
6. Diacritics: rec dictionaries include harakat; whether they are emitted reliably is NOT TESTED.
7. No automatic correction: any merge result is marked (`engines`, `needsReview`) and the original reading kept.

## 9. Performance

- ORT CPU is the baseline; Scanly reports XNNPACK/NNAPI crash on these graphs (comment, not reproduced). Do not enable either without a device benchmark.
- Lazy sessions per model (both projects do this); close on idle; cap threads at 4.
- Per-page time and memory on mid-range devices: NOT MEASURED for Paddle in Smart Bubble. Current Tesseract pipeline: 0.7–2.5 s/page, 55–98 MB peak on the CI emulator.
- Model strategy: core pack bundled (det + arabic + latin, ~21 MB); layout/table/other scripts as explicit user-approved downloads with SHA-256 + version manifest, kept in `filesDir`, previous version retained for rollback. No silent downloads.

## 10. Security / privacy

Local path uploads nothing (VERIFIED for current SB extraction code: no network use). Keep: no document text in logs (current `OcrPageReader.trace` is null in the app, test-only), temp PDF copies deleted in `finally` (VERIFIED in `DocumentExtractionEngine`), size/page limits (`maxPages = 200`). Do not adopt Scanly's embedded-key scheme.

## 11. Testing strategy

Keep all existing tests and thresholds. Add a backend parameter to `OcrBenchmarkDeviceTest` so the same corpus runs on Tesseract and Paddle and reports CER, WER, cell accuracy, numeric cells, list recall, reading order, ms/page, peak MB. Separate recognition (CER on line crops) from layout (cells, order, lists). Add real scanned documents with hand-verified ground truth (Arabic thesis pages, French reports, mixed pages, low-resolution photos); the current corpus is synthetic only.

## 12. Integration roadmap (ordered by risk)

1. **Spike (no UI change):** add ORT dependency + core models; `PaddleOrtOcrEngine` implementing `OcrEngine` (port of MakeACopy det/rec/CTC, Kotlin). Flag off by default. Measure APK/AAB delta in CI.
2. **Benchmark:** run the existing benchmark on both backends; go/no-go per metric (Arabic list recall, Arabic cells, CER per script, time, memory).
3. **Mixed script:** port `ScriptMerge` + `RtlText`; measure on mixed corpus.
4. **Switch default** to Paddle only if step 2–3 beat Tesseract; keep Tesseract as fallback for one release, then remove.
5. **Data model fields** (originalText, order, provenance, ids) — additive, no migration.
6. **Chat on ExtractedDocument**; Library persistence (Room v8, hash-keyed) with migration test.
7. Optional packs: PP-DocLayout-S, SLANet (after license verification and RTL tests); searchable PDF from MakeACopy.

## 13. Risk register

| Risk | Impact | Mitigation |
|---|---|---|
| Model licenses for SLANet/DocLayout/UVDoc unverified | legal | do not ship until model cards read |
| ORT native size × 3 ABIs | APK +45–60 MB (estimate) | AAB / ABI splits; reduced-ops build |
| Arabic accuracy of PP-OCRv5 unknown on SB failures | quality | benchmark before switching; keep Tesseract fallback |
| XNNPACK/NNAPI native aborts | crash | CPU EP only |
| Memory with 3 sessions on low-RAM devices | OOM | lazy load, close unused rec sessions |
| RTL visual→logical bugs | silent text reordering | unit tests on mixed strings; review UI |
| Room v8 migration for Library | data loss | additive table, migration test |
| Chat API change (ScannedPage → ExtractedDocument) | UI regression | keep adapter, device tests |
| Uncommitted run-#71 fixes in working tree | lost work / confusion | decide: commit on feature branch or discard |

## 14. Go / No-Go

**GO (conditional)** for an engine spike, **NO-GO** for replacing anything before measurement.

- Primary integration reference: **MakeACopy** (Apache-2.0, mature, 255 tests, verified PP-OCRv5 ORT assets with provenance and SHA-256 gate, CPU ORT runner code).
- Secondary algorithm reference: **Scanly** for `ScriptMerge`/`RtlText` (mixed Arabic-Latin), `TableDecoder` (SLANet), model-pack store pattern.
- **OpenScan: do not integrate** (no Arabic OCR, ML Kit Latin only, OpenCV size, capture already covered).
- Reuse: PP-OCRv5 det + arabic + latin models; det/rec/CTC runner logic (ported); ScriptMerge.
- Adapt: model store pattern, multi-column policy, SLANet decoder (later).
- Keep and develop internally: digital PDF extraction, layout and table reconstruction, evaluator, data model, Thesis/Chat/Library integration, review UI.
- Do not integrate: ML Kit text recognition, OpenCV, Paddle Lite, TFLite, Scanly ads/AI/keys, MakeACopy View UI, a second capture pipeline.
