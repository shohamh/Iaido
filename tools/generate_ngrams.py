#!/usr/bin/env python3
"""Build compact bilingual bigram/trigram assets for Iaido."""

from __future__ import annotations

import argparse
import csv
import gzip
import hashlib
import json
import math
import re
import sqlite3
import struct
import tarfile
import tempfile
import unicodedata
import urllib.request
import zipfile
from collections import Counter
from pathlib import Path
from typing import Sequence

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT = ROOT / "app/src/main/assets/context-ngrams"
WORD_FREQ_VERSION = "v3.0.2"
WORD_FREQ = {
    "en": {
        "url": "https://raw.githubusercontent.com/rspeer/wordfreq/v3.0.2/wordfreq/data/large_en.msgpack.gz",
        "sha256": "DFFAE8066B78DCE0A6667CF5F58E567054F902674667090A7AC8A8A44628B05C",
        "size": 1494836,
    },
    "he": {
        "url": "https://raw.githubusercontent.com/rspeer/wordfreq/v3.0.2/wordfreq/data/large_he.msgpack.gz",
        "sha256": "4B35EF0A8E6226E4F062D3220C9B2982F4C70A5C90F60864E16E7ED98BBFB3D9",
        "size": 2891674,
    },
}
CORPORA = {
    "en": {
        "url": "https://downloads.wortschatz-leipzig.de/corpora/eng_news_2020_1M.tar.gz",
        "size": 276283393,
        "sha256": "BE782EB82690415241D623FD2448DFD3FC68102AC1CE971107CB130420ABBB41",
    },
    "he": {
        "url": "https://downloads.wortschatz-leipzig.de/corpora/heb_news_2020_1M.tar.gz",
        "size": 238638097,
        "sha256": "E81BB0B4F2F13BC343DA8B732768582075257634600C359E55283774690B3213",
    },
}
CONVERSATIONAL_CORPORA = [
    {
        "name": "OpenSubtitles-v2018",
        "url": "https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2018/moses/en-he.txt.zip",
        "size_kib": 927_978,  # OPUS API reports whole KiB, so verify at that precision.
        "sha256": None,  # The API provides archive size but no digest; each generated manifest records the observed hash.
    },
    {
        "name": "QED-v2.0a",
        "url": "https://object.pouta.csc.fi/OPUS-QED/v2.0a/moses/en-he.txt.zip",
        "size_kib": 26_442,
        "sha256": None,
    },
]

HEADER = struct.Struct("<4sBBHIIIIId")  # same 36-byte layout as NKG1; the double slot holds the discount D
BIGRAM_INDEX = struct.Struct("<IIIHHI")  # context, offset, length, edges, distinct, total
TRIGRAM_INDEX = struct.Struct("<IIIIHHI")  # first, second, offset, length, edges, distinct, total
MAGIC = b"NKG2"
VERSION = 2
MAX_SUCCESSORS = 128
TRIGRAM_SUCCESSORS = 32
TRIGRAM_CONTEXT_LIMIT = 50_000
TRIGRAM_CONTEXT_VOCABULARY = 2_000
DISCOUNT = 0.75
MIN_COUNT = 2
MAX_VOCABULARY = 10_000
MAX_CONTEXT_VOCABULARY = 10_000
FLUSH_ROWS = 100_000
TOKEN_RE = {
    "en": re.compile(r"[A-Za-z]+"),
    "he": re.compile(r"[\u05D0-\u05EA]+"),
}


def normalize(word: str, language: str) -> str:
    form = "NFC" if language == "en" else "NFKC"
    normalized = unicodedata.normalize(form, word).casefold()
    if language == "he":
        normalized = "".join(char for char in normalized if not unicodedata.category(char).startswith("M"))
    return normalized


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest().upper()


def fetch_or_verify(path: Path, metadata: dict[str, object], label: str) -> dict[str, object]:
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        print(f"downloading {label}: {metadata['url']}")
        urllib.request.urlretrieve(str(metadata["url"]), path)
    actual_size = path.stat().st_size
    expected_size = metadata.get("size")
    if expected_size is not None and actual_size != expected_size:
        raise ValueError(f"{label} size mismatch: expected {expected_size}, got {actual_size}")
    expected_size_kib = metadata.get("size_kib")
    if expected_size_kib is not None and actual_size // 1024 != expected_size_kib:
        raise ValueError(f"{label} size mismatch: expected {expected_size_kib} KiB, got {actual_size // 1024} KiB")
    actual_hash = sha256(path)
    expected_hash = metadata.get("sha256")
    if expected_hash is not None and actual_hash != expected_hash:
        raise ValueError(f"{label} SHA-256 mismatch: expected {expected_hash}, got {actual_hash}")
    return {"url": metadata["url"], "bytes": actual_size, "sha256": actual_hash}


def read_dictionary(path: Path) -> list[str]:
    with path.open(encoding="utf-8", newline="") as handle:
        return [row["word"] for row in csv.DictReader(handle) if row.get("word")]


def read_wordfreq_vocabulary(path: Path, language: str) -> set[str]:
    try:
        import msgpack
    except ImportError as error:
        raise RuntimeError("generate_ngrams.py requires the Python 'msgpack' package") from error

    with gzip.open(path, "rb") as handle:
        packed = msgpack.unpack(handle, raw=False)
    if packed[0] != {"format": "cB", "version": 1}:
        raise ValueError(f"Unexpected cBpack header in {path}")
    words: set[str] = set()
    for bucket in packed[1:]:
        for source_word in bucket:
            word = normalize(source_word, language)
            if TOKEN_RE[language].fullmatch(word):
                words.add(word)
    return words


def iter_sentences(path: Path, language: str):
    """Read Leipzig sentence tables, OPUS Moses ZIP sides, or plain one-sentence-per-line text."""
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            suffix = f".{language}"
            members = [name for name in archive.namelist() if Path(name).name.endswith(suffix)]
            if len(members) != 1:
                raise ValueError(f"Expected one OPUS Moses .{language} member in {path}, found {len(members)}")
            with archive.open(members[0]) as extracted:
                for raw_line in extracted:
                    yield raw_line.decode("utf-8", errors="replace").rstrip("\r\n")
        return

    if tarfile.is_tarfile(path):
        with tarfile.open(path, mode="r:gz") as archive:
            members = [member for member in archive.getmembers() if Path(member.name).name.endswith("sentences.txt")]
            if len(members) != 1:
                raise ValueError(f"Expected one *_sentences.txt member in {path}, found {len(members)}")
            extracted = archive.extractfile(members[0])
            if extracted is None:
                raise ValueError(f"Could not extract {members[0].name} from {path}")
            with extracted:
                for raw_line in extracted:
                    line = raw_line.decode("utf-8", errors="replace").rstrip("\r\n")
                    _, separator, sentence = line.partition("\t")
                    if separator:
                        yield sentence
        return

    with path.open("rb") as source:
        for raw_line in source:
            yield raw_line.decode("utf-8", errors="replace").rstrip("\r\n")


def encode_varint(value: int) -> bytes:
    if value < 0:
        raise ValueError("varint cannot encode a negative value")
    encoded = bytearray()
    while value >= 0x80:
        encoded.append((value & 0x7F) | 0x80)
        value >>= 7
    encoded.append(value)
    return bytes(encoded)


def aggregate(
    paths: Path | Sequence[Path],
    language: str,
    dictionary_words: list[str],
    allowed: set[str],
    db_path: Path,
    context_allowed: set[str] | None = None,
    trigram_allowed: set[str] | None = None,
    max_sentences_per_source: int = 0,
    conversation_paths: Sequence[Path] = (),
    conversation_train_parity: int | None = None,
) -> dict[str, int]:
    context_allowed = allowed if context_allowed is None else context_allowed
    trigram_allowed = context_allowed if trigram_allowed is None else trigram_allowed
    word_ids = {word: index for index, word in enumerate(dictionary_words)}
    connection = sqlite3.connect(db_path)
    connection.execute("PRAGMA journal_mode=OFF")
    connection.execute("PRAGMA synchronous=OFF")
    connection.execute(
        "CREATE TABLE ngrams (kind INTEGER NOT NULL, first INTEGER NOT NULL, second INTEGER NOT NULL, "
        "next_id INTEGER NOT NULL, count INTEGER NOT NULL, "
        "PRIMARY KEY(kind, first, second, next_id))"
    )
    pending: Counter[tuple[int, int, int, int]] = Counter()
    sentence_count = token_count = 0

    def flush() -> None:
        if not pending:
            return
        connection.executemany(
            "INSERT INTO ngrams(kind, first, second, next_id, count) VALUES (?, ?, ?, ?, ?) "
            "ON CONFLICT(kind, first, second, next_id) DO UPDATE SET count = count + excluded.count",
            ((kind, first, second, next_id, count) for (kind, first, second, next_id), count in pending.items()),
        )
        connection.commit()
        pending.clear()

    try:
        corpus_paths = [paths] if isinstance(paths, Path) else list(paths)
        conversation_source_paths = {path.resolve() for path in conversation_paths}
        for path in corpus_paths:
            source_sentences = 0
            is_conversation = path.resolve() in conversation_source_paths
            for source_index, sentence in enumerate(iter_sentences(path, language)):
                if max_sentences_per_source > 0 and source_sentences >= max_sentences_per_source:
                    break
                source_sentences += 1
                if is_conversation and conversation_train_parity is not None and source_index % 2 != conversation_train_parity:
                    continue
                tokens = [word_ids[token] for raw_token in TOKEN_RE[language].findall(sentence)
                          if (token := normalize(raw_token, language)) in allowed]
                sentence_count += 1
                token_count += len(tokens)
                for index in range(1, len(tokens)):
                    if dictionary_words[tokens[index - 1]] in context_allowed:
                        pending[(2, tokens[index - 1], -1, tokens[index])] += 1
                for index in range(2, len(tokens)):
                    if (dictionary_words[tokens[index - 2]] in trigram_allowed and
                            dictionary_words[tokens[index - 1]] in trigram_allowed):
                        pending[(3, tokens[index - 2], tokens[index - 1], tokens[index])] += 1
                if len(pending) >= FLUSH_ROWS:
                    flush()
                if sentence_count % 100_000 == 0:
                    print(f"{language}: {sentence_count:,} sentences, {token_count:,} retained tokens")
        flush()
    finally:
        connection.close()
    return {"sentences": sentence_count, "retained_tokens": token_count, "vocabulary": len(word_ids)}


def selected_query(kind: int, minimum: int, top_k: int, context_limit: int = -1) -> str:
    """Top-k successors per context joined with the context's full totals.

    Columns: first, second, next_id, count, total (sum of ALL successor counts of the context, including
    pruned ones), distinct (number of distinct successor types, including pruned ones). `context_limit`
    keeps only the contexts with the largest totals (ties broken by id); -1 keeps all.
    """
    return (
        "SELECT s.first, s.second, s.next_id, s.count, t.total, t.distinct_types FROM ("
        "SELECT first, second, next_id, count, "
        "ROW_NUMBER() OVER (PARTITION BY first, second ORDER BY count DESC, next_id ASC) AS rank "
        "FROM ngrams WHERE kind = :kind AND count >= :minimum"
        ") s JOIN ("
        "SELECT first, second, SUM(count) AS total, COUNT(*) AS distinct_types FROM ngrams "
        "WHERE kind = :kind GROUP BY first, second "
        "ORDER BY total DESC, first ASC, second ASC LIMIT :context_limit"
        ") t ON t.first = s.first AND t.second = s.second "
        "WHERE s.rank <= :top_k ORDER BY s.first, s.second, s.next_id"
    )


def query_params(kind: int, minimum: int, top_k: int, context_limit: int = -1) -> dict[str, int]:
    return {"kind": kind, "minimum": minimum, "top_k": top_k, "context_limit": context_limit}


def pack_index(kind: int, context: tuple[int, int], offset: int, length: int, edges: int, distinct: int, total: int) -> bytes:
    distinct = min(distinct, 65535)
    total = min(total, 0xFFFFFFFF)
    if kind == 2:
        return BIGRAM_INDEX.pack(context[0], offset, length, edges, distinct, total)
    return TRIGRAM_INDEX.pack(context[0], context[1], offset, length, edges, distinct, total)


def write_edges(
    connection: sqlite3.Connection,
    kind: int,
    minimum: int,
    top_k: int,
    context_limit: int,
    edge_path: Path,
    index_path: Path,
) -> tuple[int, int]:
    """Write one order's index + edges. Edge = varint(id delta) + varint(exact count)."""
    context_count = edge_bytes = 0
    current_context: tuple[int, int] | None = None
    current_offset = current_edges = current_total = current_distinct = 0
    previous_id = 0
    edge_handle = edge_path.open("wb")
    index_handle = index_path.open("wb")

    def close_context() -> None:
        index_handle.write(pack_index(
            kind, current_context, current_offset, edge_bytes - current_offset,
            current_edges, current_distinct, current_total,
        ))

    try:
        for first, second, next_id, count, total, distinct in connection.execute(
            selected_query(kind, minimum, top_k, context_limit), query_params(kind, minimum, top_k, context_limit),
        ):
            context = (first, second)
            if context != current_context:
                if current_context is not None:
                    close_context()
                    context_count += 1
                current_context = context
                current_offset = edge_bytes
                current_edges = 0
                current_total = total
                current_distinct = distinct
                previous_id = 0
            encoded = encode_varint(next_id - previous_id) + encode_varint(count)
            edge_handle.write(encoded)
            edge_bytes += len(encoded)
            current_edges += 1
            previous_id = next_id
        if current_context is not None:
            close_context()
            context_count += 1
    finally:
        edge_handle.close()
        index_handle.close()
    return context_count, edge_bytes


def build_binary(
    db_path: Path,
    output: Path,
    language: str,
    word_count: int,
    minimum: int,
    top_k: int,
    trigram_top_k: int | None = None,
    trigram_context_limit: int = -1,
    discount: float = DISCOUNT,
) -> dict[str, int | str | float]:
    trigram_top_k = top_k if trigram_top_k is None else trigram_top_k
    connection = sqlite3.connect(db_path)
    with tempfile.TemporaryDirectory(prefix="iaido-edges-") as temporary:
        temporary_path = Path(temporary)
        bg_edges = temporary_path / "bigram.edges"
        bg_index = temporary_path / "bigram.index"
        tri_edges = temporary_path / "trigram.edges"
        tri_index = temporary_path / "trigram.index"
        bg_contexts, bg_bytes = write_edges(connection, 2, minimum, top_k, -1, bg_edges, bg_index)
        tri_contexts, tri_bytes = write_edges(
            connection, 3, minimum, trigram_top_k, trigram_context_limit, tri_edges, tri_index,
        )
        connection.close()
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open("wb") as destination:
            destination.write(
                HEADER.pack(
                    MAGIC,
                    VERSION,
                    0 if language == "en" else 1,
                    0,
                    word_count,
                    bg_contexts,
                    tri_contexts,
                    bg_bytes,
                    tri_bytes,
                    discount,
                )
            )
            for path in (bg_index, tri_index, bg_edges, tri_edges):
                with path.open("rb") as source:
                    destination.write(source.read())
    return {
        "bytes": output.stat().st_size,
        "sha256": sha256(output),
        "word_count": word_count,
        "bigram_contexts": bg_contexts,
        "trigram_contexts": tri_contexts,
        "bigram_edges_bytes": bg_bytes,
        "trigram_edges_bytes": tri_bytes,
        "discount": discount,
    }


def build_language(
    language: str,
    wordfreq_path: Path,
    corpus_paths: Sequence[Path],
    dictionary_path: Path,
    output: Path,
    minimum: int,
    top_k: int,
    vocabulary_limit: int,
    context_vocabulary_limit: int,
    trigram_top_k: int = TRIGRAM_SUCCESSORS,
    trigram_context_vocabulary_limit: int = TRIGRAM_CONTEXT_VOCABULARY,
    trigram_context_limit: int = TRIGRAM_CONTEXT_LIMIT,
    discount: float = DISCOUNT,
    max_sentences_per_source: int = 0,
    conversation_paths: Sequence[Path] = (),
    conversation_train_parity: int | None = None,
    db_dir: Path | None = None,
) -> dict[str, object]:
    dictionary_words = list(dict.fromkeys(normalize(word, language) for word in read_dictionary(dictionary_path)))
    # With --dictionary-vocabulary the dictionary itself is the vocabulary (no wordfreq filtering).
    large_vocabulary = set(dictionary_words) if wordfreq_path is None else read_wordfreq_vocabulary(wordfreq_path, language)
    allowed = set(dictionary_words[:vocabulary_limit]) & large_vocabulary
    context_allowed = set(dictionary_words[:context_vocabulary_limit]) & allowed
    trigram_allowed = set(dictionary_words[:trigram_context_vocabulary_limit]) & context_allowed
    if not allowed:
        raise ValueError(f"No dictionary words survived {language} vocabulary filtering")
    with tempfile.TemporaryDirectory(prefix=f"iaido-{language}-") as temporary:
        # --db-dir keeps the aggregated counts so asset variants (top-k, context limits, discount) can be built
        # without re-reading the corpus. A kept database is only reused when its aggregation settings match.
        settings = {
            "max_sentences_per_source": max_sentences_per_source,
            "corpus_sources": [
                {"path": str(path.resolve()), "bytes": path.stat().st_size, "mtime_ns": path.stat().st_mtime_ns}
                for path in corpus_paths
            ],
            "conversation_sources": [
                {"path": str(path.resolve()), "bytes": path.stat().st_size, "mtime_ns": path.stat().st_mtime_ns}
                for path in conversation_paths
            ],
            "conversation_train_parity": conversation_train_parity,
            "vocabulary": len(allowed), "context_vocabulary": len(context_allowed),
            "trigram_context_vocabulary": len(trigram_allowed), "dictionary_words": len(dictionary_words),
            "dictionary_words_sha256": hashlib.sha256("\0".join(dictionary_words).encode("utf-8")).hexdigest(),
            "allowed_words_sha256": hashlib.sha256("\0".join(sorted(allowed)).encode("utf-8")).hexdigest(),
            "context_words_sha256": hashlib.sha256("\0".join(sorted(context_allowed)).encode("utf-8")).hexdigest(),
            "trigram_words_sha256": hashlib.sha256("\0".join(sorted(trigram_allowed)).encode("utf-8")).hexdigest(),
        }
        if db_dir is not None:
            db_dir.mkdir(parents=True, exist_ok=True)
            db_path = db_dir / f"{language}.sqlite3"
            sidecar = db_dir / f"{language}.counts.json"
        else:
            db_path = Path(temporary) / "ngrams.sqlite3"
            sidecar = None
        if sidecar is not None and sidecar.exists() and db_path.exists():
            saved = json.loads(sidecar.read_text(encoding="utf-8"))
            if saved["settings"] != settings:
                raise SystemExit(f"{db_path} was aggregated with different settings: {saved['settings']} != {settings}")
            counts = saved["counts"]
        else:
            if db_path.exists():
                db_path.unlink()
            counts = aggregate(
                corpus_paths, language, dictionary_words, allowed, db_path, context_allowed, trigram_allowed,
                max_sentences_per_source, conversation_paths, conversation_train_parity,
            )
            counts["context_vocabulary"] = len(context_allowed)
            counts["trigram_context_vocabulary"] = len(trigram_allowed)
            if sidecar is not None:
                sidecar.write_text(json.dumps({"settings": settings, "counts": counts}), encoding="utf-8")
        result = build_binary(
            db_path, output, language, len(dictionary_words), minimum, top_k,
            trigram_top_k, trigram_context_limit, discount,
        )
    return {"language": language, "counts": counts, "asset": result}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument(
        "--dictionary-vocabulary",
        action="store_true",
        help="Use the dictionary as the vocabulary and skip the wordfreq filter (valid when every dictionary "
        "word is already in the wordfreq vocabulary, as with the shipped dictionaries).",
    )
    parser.add_argument("--wordfreq-en", type=Path)
    parser.add_argument("--wordfreq-he", type=Path)
    parser.add_argument("--corpus-en", type=Path)
    parser.add_argument("--corpus-he", type=Path)
    parser.add_argument("--dictionary-en", type=Path, default=ROOT / "app/src/main/assets/dictionary/en.csv")
    parser.add_argument("--dictionary-he", type=Path, default=ROOT / "app/src/main/assets/dictionary/he.csv")
    parser.add_argument("--min-count", type=int, default=MIN_COUNT)
    parser.add_argument("--top-k", type=int, default=MAX_SUCCESSORS)
    parser.add_argument("--vocabulary-limit", type=int, default=MAX_VOCABULARY)
    parser.add_argument("--context-vocabulary-limit", type=int, default=MAX_CONTEXT_VOCABULARY,
                        help="bigram contexts: first N dictionary words")
    parser.add_argument("--trigram-top-k", type=int, default=TRIGRAM_SUCCESSORS)
    parser.add_argument("--trigram-context-vocabulary-limit", type=int, default=TRIGRAM_CONTEXT_VOCABULARY,
                        help="both words of a trigram context must be among the first N dictionary words")
    parser.add_argument("--trigram-context-limit", type=int, default=TRIGRAM_CONTEXT_LIMIT,
                        help="keep only the N (first, second) contexts with the largest totals")
    parser.add_argument("--languages", default="en,he", help="comma separated subset of en,he (experiments only)")
    parser.add_argument("--max-sentences", type=int, default=0,
                        help="read only the first N sentences from each source (0 = all)")
    parser.add_argument("--conversation-corpus", type=Path, action="append", default=[],
                        help="local bilingual OPUS Moses ZIP to add to both language models; repeatable, overrides downloads")
    parser.add_argument("--exclude-conversation", action="store_true",
                        help="build from Leipzig news only instead of adding OpenSubtitles v2018 and QED")
    parser.add_argument("--conversation-train-parity", type=int, choices=(0, 1),
                        help="train only on matching even/odd conversation lines; reserve the other half for evaluation")
    parser.add_argument("--db-dir", type=Path,
                        help="keep/reuse the aggregated count databases here (experiments with asset variants)")
    parser.add_argument("--discount", type=float, default=DISCOUNT, help="absolute discount D stored in the header")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if (args.min_count < 1 or args.top_k < 1 or args.top_k > 65535 or
            args.trigram_top_k < 1 or args.trigram_top_k > 65535 or
            args.trigram_context_limit < 1 or args.trigram_context_vocabulary_limit < 1 or
            args.vocabulary_limit < 1 or args.context_vocabulary_limit < 1):
        raise SystemExit("count and vocabulary limits must be positive; --top-k values must be in 1..65535")
    if not 0.0 < args.discount < 1.0:
        raise SystemExit("--discount must be in (0, 1)")
    if args.exclude_conversation and args.conversation_corpus:
        raise SystemExit("--exclude-conversation cannot be combined with --conversation-corpus")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="iaido-sources-") as temporary:
        temporary_path = Path(temporary)
        paths = {}
        source_manifest = {}
        conversation_paths = list(args.conversation_corpus)
        if args.conversation_corpus:
            for index, path in enumerate(conversation_paths):
                if not path.is_file():
                    raise SystemExit(f"conversation corpus does not exist: {path}")
                source_manifest[f"conversation_local_{index + 1}"] = {
                    "path": str(path), "bytes": path.stat().st_size, "sha256": sha256(path),
                }
        elif not args.exclude_conversation:
            for corpus in CONVERSATIONAL_CORPORA:
                corpus_path = temporary_path / f"{corpus['name']}.en-he.txt.zip"
                source_manifest[f"conversation_{corpus['name']}"] = fetch_or_verify(
                    corpus_path, corpus, corpus["name"],
                )
                conversation_paths.append(corpus_path)
        languages = [language for language in args.languages.split(",") if language]
        if not languages or any(language not in ("en", "he") for language in languages):
            raise SystemExit("--languages must be a comma separated subset of en,he")
        for language in languages:
            wordfreq_path = args.wordfreq_en if language == "en" else args.wordfreq_he
            corpus_path = args.corpus_en if language == "en" else args.corpus_he
            if args.dictionary_vocabulary:
                wordfreq_path = None
                source_manifest[f"wordfreq_{language}"] = {"skipped": "dictionary used as vocabulary"}
            elif wordfreq_path is None:
                wordfreq_path = temporary_path / f"large_{language}.msgpack.gz"
                source_manifest[f"wordfreq_{language}"] = fetch_or_verify(wordfreq_path, WORD_FREQ[language], f"wordfreq {language}")
            else:
                source_manifest[f"wordfreq_{language}"] = fetch_or_verify(wordfreq_path, WORD_FREQ[language], f"wordfreq {language}")
            if corpus_path is None:
                corpus_path = temporary_path / f"{language}_news_2020_1M.tar.gz"
                source_manifest[f"corpus_{language}"] = fetch_or_verify(corpus_path, CORPORA[language], f"Leipzig corpus {language}")
            else:
                source_manifest[f"corpus_{language}"] = fetch_or_verify(corpus_path, CORPORA[language], f"Leipzig corpus {language}")
            paths[language] = (wordfreq_path, [corpus_path, *conversation_paths], conversation_paths)
        results = {}
        for language, dictionary_path in (("en", args.dictionary_en), ("he", args.dictionary_he)):
            if language not in languages:
                continue
            source_manifest[f"dictionary_{language}"] = {
                "bytes": dictionary_path.stat().st_size,
                "sha256": sha256(dictionary_path),
            }
            wordfreq_path, corpus_paths, language_conversation_paths = paths[language]
            results[language] = build_language(
                language,
                wordfreq_path,
                corpus_paths,
                dictionary_path,
                args.output_dir / f"{language}.ngram.bin",
                args.min_count,
                args.top_k,
                args.vocabulary_limit,
                args.context_vocabulary_limit,
                args.trigram_top_k,
                args.trigram_context_vocabulary_limit,
                args.trigram_context_limit,
                args.discount,
                args.max_sentences,
                language_conversation_paths,
                args.conversation_train_parity,
                args.db_dir,
            )
        manifest = {
            "format": "NKG2",
            "generator": "tools/generate_ngrams.py",
            "wordfreq_version": WORD_FREQ_VERSION,
            "sequence_source": "Leipzig Corpora Collection 2020 1M news plus optional OPUS Moses corpora",
            "normalization": {
                "en": "NFC, case-fold, ASCII letters only",
                "he": "NFKC, case-fold, remove combining marks, Hebrew letters only",
            },
            "min_count": args.min_count,
            "top_k": args.top_k,
            "vocabulary_limit": args.vocabulary_limit,
            "context_vocabulary_limit": args.context_vocabulary_limit,
            "trigram_top_k": args.trigram_top_k,
            "trigram_context_vocabulary_limit": args.trigram_context_vocabulary_limit,
            "trigram_context_limit": args.trigram_context_limit,
            "discount": args.discount,
            "max_sentences_per_source": args.max_sentences,
            "conversation_train_parity": args.conversation_train_parity,
            "sources": source_manifest,
            "results": results,
        }
        (args.output_dir / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
