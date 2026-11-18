# Smart Bubble Agent

Chat is now **Agent**. The tab, the conversation history, voice, attachments, templates and the
document pipeline are the same; requests now go through the Agent Core and sequential provider
routing. Thesis Builder, My Templates, Library, Settings, OCR/PDF extraction and the Word, Excel,
PowerPoint and PDF engines are unchanged.

## Layers

```
Agent screen (ChatScreen + AgentPanel)          ui/screens/ChatScreen.kt, AgentPanel.kt
        │
SmartBubbleViewModel.executePrompt              Quick Mode / Agent Mode decision
        │
Agent Core (pure Kotlin)                        agent/core/*
  IntentAnalyzer → TaskPlanner → AgentEngine → ToolRegistry → ResultValidator
        │                                   checkpoints: AgentTaskStore (Room agent_tasks, v8)
AgentService (Android adapters)                 agent/AgentService.kt
  library.read     → Room documents; Library PDFs without text → DocumentExtractionEngine
                      (PDF text layer, PaddleOCR on ONNX Runtime for scanned pages)
  content.generate → CentralAiOrchestrator.handleChatTurn (Smart Templates, My Templates,
                      DOCX/XLSX/PPTX/PDF engines, Library save)
  result.validate  → file on disk + Library record checks
  document.share   → DeliveryManager share sheet (needs approval)
        │
Provider routing (pure Kotlin)                  agent/routing/*
  PriorityList (user order) → RoutingSession → RoutedAiClient → existing provider clients
```

ONNX Runtime and PaddleOCR are not touched: the Agent reaches them only through
`DocumentExtractionEngine.extract`, the same entry point Thesis Builder uses.

## Quick Mode and Agent Mode

`IntentAnalyzer` (local, no AI request) decides:

- **Quick Mode**: everything that one orchestrator turn handles (write a letter, translate,
  answer, a table, a Word/PDF/Excel/PowerPoint document from the request). No plan is shown.
- **Agent Mode**: the request names Library documents to read (by title), or asks to share a
  document it creates. A plan of at most 8 steps is shown with progress and controls.

The model never chooses or calls tools. Steps come only from `TaskPlanner`; a model only writes
content inside the `content.generate` step. `ToolRegistry` is a strict allow-list: unknown tools,
missing or unexpected inputs, oversized inputs, missing permissions and missing approvals are
refused before anything runs.

## Sequential provider routing

Settings > **Agent model order** lists every configured provider. The user moves models up/down,
switches them on/off and chooses 1–3 passes over the list (default 2). Before the user arranges
anything, the default provider is first, then the others in the order they were added.

For each request:

1. Models that cannot do what the request needs are skipped, with the reason shown
   (structured output, image input, tool calling, context window — when the provider reported it).
2. The first enabled model with a key is called.
3. On failure the policy decides (`FailurePolicy`):

| Error | Decision |
| --- | --- |
| network unavailable, DNS/connection, timeout, HTTP 5xx, rate limit (429), empty or malformed answer | next model; may be tried again in the next pass |
| invalid/missing key, permission (401/403), quota/billing, model not found, configuration, context too large | next model; **not** used again in this task |
| bad request (400/422), blocked content, truncated output, unknown | stop; the real error is shown |
| user cancellation | stop at once; no other model starts |

4. After the last model, if passes remain, the Agent waits (2 s, or the provider's Retry-After,
   at most 30 s) and starts again from the first model. When the passes are used up, or every
   model is excluded, the last real error is shown, naming the model and the priority list.

There is no random choice and no unbounded retry. AiHttp still makes its single bounded retry
for 429/5xx inside one model call. Within one task, a later model call continues with the model
that last answered (the earlier ones already failed in this task).

Failover keeps the task context: the same request (system prompt, conversation, source document
texts, plan results, language and format) is sent to the next model. Each model gets only its own
credentials. If a model fails after part of its answer was streamed, the partial text is dropped
before the next model answers.

Model calls have no side effects: the orchestrator writes a document only after a complete,
validated answer, so a failover can never create a duplicate file.

## Tasks: approval, pause, resume, cancel, restart

- Each step has an `executionId` and is checkpointed before and after its tool runs (Room table
  `agent_tasks`, JSON snapshot without credentials).
- **Pause** takes effect after the running step; **Resume** continues from the checkpoint; a
  completed step never runs again.
- **Cancel** stops the running step at once (the orchestrator deletes an unsaved file) and records
  the task as cancelled.
- **Approval** is required for sharing a document with another app. Reject skips the step and the
  task ends "completed with warnings".
- **App restart**: an interrupted task becomes Paused. A read step is simply run again. For the
  document step the Agent looks for the document that run saved (same request, created after the
  step started); if found it is used, otherwise the step is marked "result not confirmed" and runs
  again only after the user approves. Completion is never claimed when it is unknown.

## Result validation

`ResultValidator` checks that the requested output exists, has the requested format, the file is
on disk and not empty, the content is not empty, and a requested template was applied. Outcomes:
completed, completed with warnings, incomplete, failed. This is structural validation only; it
does not make AI-written content factually verified.

Figures from tables in source documents (sum, mean, min, max per numeric column) are computed by
`TableStats` and given to the model as exact values, so report numbers come from app code.

## Database

v7 → v8 adds only `agent_tasks` (`id`, `sessionId`, `status`, `snapshotJson`, `createdAt`,
`updatedAt`, index on `sessionId`). Conversations and messages are not migrated or rewritten.
Clearing or deleting a conversation also deletes its task records.

## Known limitations

- Agent Mode reads Library documents named in the request; it does not yet take arbitrary files
  picked from storage (Chat's image/scan attachment still goes through OCR review first).
- Capabilities are known only for providers that report them in their model list (OpenAI-style
  lists, Mistral, Groq, xAI, OpenRouter); others are tried and a real "unsupported" answer moves on.
- Thesis Builder, slash content commands and "Enhance with AI" still use the default provider.
- No tool/function calling is sent to providers: tools are run by the app, not by the model.
- Long-running tasks run while the app process lives (no WorkManager); after the app is killed
  they are resumable, not continued automatically.
