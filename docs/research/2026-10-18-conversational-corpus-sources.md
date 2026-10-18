# Conversational corpus sources for EN/HE n-grams

Research snapshot: 2026-10-18. Counts and sizes below come from the live [OPUS API](https://opus.nlpl.eu/opusapi), using its documented `corpus`, `source`, `target`, `preprocessing`, and `version` parameters. OPUS reports `size` in KiB (cross-checked against the displayed size of another OPUS Moses download).

## Recommended sources

| Source | Why it fits | EN/HE Moses pair | Approx. archive size |
|---|---|---:|---:|
| [OpenSubtitles v2018](https://opus.nlpl.eu/opusapi?corpus=OpenSubtitles&source=en&target=he&preprocessing=moses&version=v2018) | Movie and TV subtitle dialogue; a substantial conversational addition with lower download/processing cost than v2024. | 29,887,386 aligned lines; 195,976,297 EN tokens; 154,252,027 HE tokens | 927,978 KiB (~906 MiB) |
| [QED v2.0a](https://opus.nlpl.eu/opusapi?corpus=QED&source=en&target=he&preprocessing=moses&version=latest) | Subtitles for educational videos and lectures, collaboratively transcribed and translated through AMARA; adds spoken explanatory language in a small file. | 464,349 aligned lines; 6,367,820 EN tokens; 4,483,713 HE tokens | 26,442 KiB (~25.8 MiB) |

OpenSubtitles v2024 is also available from the [OPUS API](https://opus.nlpl.eu/opusapi?corpus=OpenSubtitles&source=en&target=he&preprocessing=moses&version=v2024): 58,814,909 aligned lines, 395,243,118 EN tokens, 307,667,215 HE tokens, and 1,550,001 KiB (~1.48 GiB). The OPUS release list records both v2018 and v2024. For the first measurement pass, v2018 plus QED is a practical choice; v2024 can be tried if the added dialogue improves held-out results enough to justify its larger download and processing pass.

## Ingestion and measurement notes

OPUS describes Moses downloads as two aligned plain-text files with one sentence per line; empty alignments are excluded. The archives are therefore suitable for extracting each language side as separate sentence streams. Keep the existing Leipzig news sentences and aggregate the new streams into the same generator input. Before training, reserve a deterministic subset of conversational lines for evaluation, or evaluate on another disjoint phrase source, so the benchmark does not measure n-grams against examples already seen during asset generation. Tune weights on a separate tuning set and report held-out results.

OPUS's [OpenSubtitles release page](https://opus.nlpl.eu/legacy/OpenSubtitles-v2024.php) identifies it as subtitle corpus data; its [release index](https://github.com/Helsinki-NLP/OPUS/blob/main/info/RELEASES.md) lists the versions. The [QED corpus page](https://opus.nlpl.eu/datasets/QED) describes its educational-video/lecture subtitle source and marks it research-purpose-only. Licensing is recorded as metadata here, not used to choose whether to benchmark the data.

## Reproducible source queries

- OpenSubtitles v2018: `https://opus.nlpl.eu/opusapi?corpus=OpenSubtitles&source=en&target=he&preprocessing=moses&version=v2018`
- OpenSubtitles v2024: `https://opus.nlpl.eu/opusapi?corpus=OpenSubtitles&source=en&target=he&preprocessing=moses&version=v2024`
- QED latest (currently v2.0a): `https://opus.nlpl.eu/opusapi?corpus=QED&source=en&target=he&preprocessing=moses&version=latest`
- OPUS [API parameter documentation](https://opus.nlpl.eu/opusapi) and [download format documentation](https://opus.nlpl.eu/download-formats).

## Retrieval and measurement results

The direct OPUS object host was not usable from this environment, so the
OpenSubtitles English-Hebrew shard was retrieved from the Hugging Face dataset
[`sentence-transformers/parallel-sentences-opensubtitles`](https://huggingface.co/datasets/sentence-transformers/parallel-sentences-opensubtitles).
This is an alternate host/repack of OPUS-derived subtitles, not a new source
of conversation. The Parquet file is pinned to dataset commit
`d86a387587ab6f2fd9ec7453b2765cec68111c87`, last changed at
`f35743d5d25b564da46eb13db1ba9dd99cd23476`; size is 249,157,443 bytes and
SHA-256 is `b4558b11b37532c068bb92b8f65572d1a182e8d9c01602e00e4aae7bd98a3427`.

The conversion kept rows with at least three lexical and three dictionary
words on both language sides and at least 50% dictionary coverage. Of
4,454,835 rows, 3,203,514 passed. A deterministic hash split yielded 1,601,048
training pairs and 1,602,466 evaluation pairs; 1,418 excess evaluation rows
were omitted from the balanced interleaved archive. Training used only the
even/interleaved training rows. The filtered archive is 109,703,351 bytes,
SHA-256 `fb636dbe5ef496c78c996fc8d5609271f6c5054cc31fa5bbf0200ee4f2f3155c`.
The HF QED dataset entry is a loader that still fetches from the OPUS object
host, so it was not an independent accessible fallback.

The measured assets combine Leipzig 2020 news, Tatoeba linked sentences, and
the OpenSubtitles training half. They were compared with the Tatoeba-plus-news
asset, the checked-in asset before promotion, and no context. The benchmark
replays synthetic noisy swipes over sampled natural phrases; it is not a test
on recorded user swipes.

| Evaluation set | Language | Combined asset | Tatoeba + news | Previous checked-in asset | No context |
|---|---|---:|---:|---:|---:|
| OpenSubtitles held-out phrases | English | 83.00% | 81.69% | 81.82% | 78.48% |
| OpenSubtitles held-out phrases | Hebrew | 73.89% | 72.28% | 72.15% | 68.81% |
| Tatoeba held-out phrases, using OpenSubtitles-tuned weights | English | 82.72% | 82.65% | 81.65% | 77.63% |
| Tatoeba held-out phrases, using OpenSubtitles-tuned weights | Hebrew | 73.49% | 72.07% | 70.51% | 65.98% |

The English targeted set includes “today of all days”; the combined model
scored 87.50% over the full 18-phrase targeted set with the existing shared
weights, versus 86.46% for the previous asset. This aggregate does not establish
the result of that one phrase by itself.

Coordinate tuning selected different settings by language. English selected
bigram/trigram weights `0.5/0.3`, margin `0.25`, floor `-1`; Hebrew selected
`0.15/0.3`, margin `0.5`, floor `-2`. Their held-out OpenSubtitles scores were
83.19% and 74.15%, respectively, versus 83.00% and 73.89% with the current
shared `0.3/0.3` settings. English targeted-set accuracy was 86.67% with its
tuned setting, below the shared-weight result. Given the small held-out lift,
language-specific optima, and English targeted-set tradeoff, the shared
production weights were retained. Both NKG2 assets were regenerated with the
combined corpus and their hashes are recorded in the checked-in manifest.

## Hugging Face LLM datasets versus keyboard language data

Hugging Face has strong instruction-tuning datasets, but prompt/answer pairs
are a different training target from the natural next-word distribution a
keyboard needs:

- [OpenAssistant OASST1](https://huggingface.co/datasets/OpenAssistant/oasst1)
  is a human-generated, annotated, assistant-style corpus with 161,443 messages
  across 35 languages, but only 255 Hebrew messages. It is useful for LLM
  supervised fine-tuning, too small for a Hebrew keyboard language model.
- [Aya Dataset](https://huggingface.co/datasets/CohereLabs/aya_dataset) has
  204,114 human-annotated multilingual instruction pairs over 65 languages;
  the current language list does not include Hebrew. The larger
  [Aya Collection Hebrew subset](https://huggingface.co/datasets/CohereLabs/aya_collection_language_split/viewer/hebrew)
  has about 4.22 million rows, but is assembled from task templates and
  translated instruction datasets, so its text distribution is unlike casual
  typed Hebrew.
- [UltraChat 200k](https://huggingface.co/datasets/HuggingFaceH4/ultrachat_200k)
  is a useful English supervised-fine-tuning set (about 208k SFT train rows),
  but its dialogues were generated by ChatGPT and it has no Hebrew subset.
- [Basis Conversations 1500](https://huggingface.co/datasets/basis-ai/basis-conversations-1500)
  is closer to natural spontaneous English/Hebrew conversation, with 174.4
  English hours and 25 Hebrew hours. It currently requires a reviewed access
  request, and the authors warn that the provided ASR transcripts can be noisy
  and are not recommended as direct speech-supervision targets. Its text could
  be worth evaluating if access is available.

For this Android keyboard, these SFT corpora should not be mixed into the n-gram
model without filtering and held-out measurement. The OpenSubtitles mirror is
the accessible conversational corpus that improved both languages in the
current benchmark.
