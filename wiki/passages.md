---
machine: build-host / Android
subsystem: passage editing and recovery
last-verified: 2026-09-15
status: implemented
---
# Passage editing and rejected speech

Long-press a paragraph and choose **Edit passage**. Save updates the imported copy inside PageCast; it does not edit the original document. Associated analysis, rejection records and prepared-audio validity are updated so stale speech is not silently reused. Cancel leaves the saved passage unchanged.

For a recorded provider rejection, the reader and long-press menu expose:

- **Retry generation:** explicitly try synthesis again using the unchanged passage. This can help with intermittent provider failures and can incur another API charge.
- **Rewrite rejected passage:** open the editor, then choose **Suggest milder wording**. The configured Google text model returns a draft. Review/edit it and choose Save to apply it. Rewriting itself does not guarantee acceptance by the speech provider.

Rewriting is user-triggered and available only after a recorded rejection. A rejection never silently starts an LLM rewrite or saves altered prose. Normal non-blocked responses with no audio have a separate bounded retry path; see [the recovery implementation](next-milestone.md#tts-text-only-incident).

Punctuation-only scene breaks such as `***` and `* * *` are skipped during narration. They do not need milder wording; old rejection markers for these separators are hidden and prepared audio containing them is invalidated.

Rewrite usage and new cloud generation responses count toward the affected book's [spending estimate](spending.md). Local Kokoro generation itself has no API cost. The rewrite request still goes to Google even when Kokoro is selected.
