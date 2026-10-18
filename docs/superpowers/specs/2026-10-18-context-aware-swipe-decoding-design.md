# Context-aware swipe sequence decoding

## Goal

Use language context to choose among noisy swipe candidates across a short phrase, while allowing the active swipe window to revise words already shown in the editor.

## Design

Extend `InferenceSegmenter` with a one-word-per-gesture policy for normal swipe modes. It ranks the existing recognizer candidates jointly across at most six gesture units. Generate only whole-word groups before candidate pruning, and retain the best prefix for each last-two-word state so trigram-relevant paths survive. Normalize case at the n-gram lookup boundary because displayed sentence capitalization is not part of lexical context. Keep the current split/join policy for inferred spacing.

`SwipeTypingCoordinator` owns the rolling transaction in all spacing modes. Every accepted swipe re-decodes the active units and replaces the owned editor span. On a full window, freeze the oldest word from the current best sequence, then decode the retained units plus the new swipe with the frozen word as context. Manual mode renders adjacent words without separators; after-swipe mode preserves its separator and trailing-space behavior; inferred spacing retains its current rendering.

The transaction records the exact text it owns and supplies that expected text to the editor adapter on every replacement. The adapter requires the caret to remain at the owned span end and verifies the source text before editing. Transaction state advances only after a successful replacement. Cursor movement or ordinary input finalizes the current window; an external edit or failed replacement abandons it without writing through stale offsets.

## Evaluation

Keep candidate generation, candidate limits, path scores, NKG2 assets, and six-gesture window fixed. Compare greedy per-swipe ranking with rolling sequence decoding on held-out English and Hebrew phrase lattices. Track positional top-1 word accuracy, phrase exact match, candidate recall, harmful revisions, and changes to previously correct high-confidence words. The offline synthetic benchmark reports these measures; its variable-boundary segmentation benchmark continues to use text alignment. App tests cover stale-span and replacement-failure behavior. Real `InputConnection` replacement latency needs a device-backed measurement and must not be inferred from the synthetic core benchmark.
