#!/usr/bin/env python3
"""Build compact bilingual bigram/trigram assets for NinjaKeys."""

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
from collections import Counter
from pathlib import Path

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
    },
    "he": {
        "url": "https://downloads.wortschatz-leipzig.de/corpora/heb_news_2020_1M.tar.gz",
        "size": 238638097,
    },
}

HEADER = struct.Struct("<4sBBHIIIIId")
BIGRAM_INDEX = struct.Struct("<IIIHH")
TRIGRAM_INDEX = struct.Struct("<IIIIHH")
MAGIC = b"NKG1"
VERSION = 1
MAX_SUCCESSORS = 32
MIN_COUNT = 2
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


def iter_sentences(path: Path):
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


def encode_varint(value: int) -> bytes:
    if value < 0:
        raise ValueError("varint cannot encode a negative value")
    encoded = bytearray()
    while value >= 0x80:
        encoded.append((value & 0x7F) | 0x80)
        value >>= 7
    encoded.append(value)
    return bytes(encoded)


def aggregate(path: Path, language: str, dictionary_words: list[str], allowed: set[str], db_path: Path) -> dict[str, int]:
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
        for sentence in iter_sentences(path):
            tokens = [word_ids[token] for raw_token in TOKEN_RE[language].findall(sentence)
                      if (token := normalize(raw_token, language)) in allowed]
            sentence_count += 1
            token_count += len(tokens)
            for index in range(1, len(tokens)):
                pending[(2, tokens[index - 1], -1, tokens[index])] += 1
            for index in range(2, len(tokens)):
                pending[(3, tokens[index - 2], tokens[index - 1], tokens[index])] += 1
            if len(pending) >= FLUSH_ROWS:
                flush()
            if sentence_count % 100_000 == 0:
                print(f"{language}: {sentence_count:,} sentences, {token_count:,} retained tokens")
        flush()
    finally:
        connection.close()
    return {"sentences": sentence_count, "retained_tokens": token_count, "vocabulary": len(word_ids)}


def selected_query(kind: int, minimum: int, top_k: int) -> str:
    return (
        "SELECT first, second, next_id, count FROM ("
        "SELECT first, second, next_id, count, "
        "ROW_NUMBER() OVER (PARTITION BY first, second ORDER BY count DESC, next_id ASC) AS rank "
        "FROM ngrams WHERE kind = ? AND count >= ?"
        ") WHERE rank <= ? ORDER BY first, second, next_id"
    )


def write_edges(
    connection: sqlite3.Connection,
    kind: int,
    minimum: int,
    top_k: int,
    max_log_count: float,
    edge_path: Path,
    index_path: Path,
) -> tuple[int, int]:
    context_count = edge_bytes = 0
    current_context: tuple[int, int] | None = None
    current_offset = 0
    current_edges = 0
    previous_id = 0
    edge_handle = edge_path.open("wb")
    index_handle = index_path.open("wb")
    try:
        for first, second, next_id, count in connection.execute(selected_query(kind, minimum, top_k), (kind, minimum, top_k)):
            context = (first, second)
            if context != current_context:
                if current_context is not None:
                    if kind == 2:
                        index_handle.write(BIGRAM_INDEX.pack(
                            current_context[0], current_offset, edge_bytes - current_offset, current_edges, 0,
                        ))
                    else:
                        index_handle.write(TRIGRAM_INDEX.pack(
                            current_context[0], current_context[1], current_offset,
                            edge_bytes - current_offset, current_edges, 0,
                        ))
                    context_count += 1
                current_context = context
                current_offset = edge_bytes
                current_edges = 0
                previous_id = 0
            encoded = encode_varint(next_id - previous_id)
            quantized = 0 if max_log_count == 0.0 else round(math.log1p(count) / max_log_count * 65535)
            edge_handle.write(encoded)
            edge_handle.write(struct.pack("<H", min(65535, quantized)))
            edge_bytes += len(encoded) + 2
            current_edges += 1
            previous_id = next_id
        if current_context is not None:
            if kind == 2:
                index_handle.write(BIGRAM_INDEX.pack(
                    current_context[0], current_offset, edge_bytes - current_offset, current_edges, 0,
                ))
            else:
                index_handle.write(TRIGRAM_INDEX.pack(
                    current_context[0], current_context[1], current_offset,
                    edge_bytes - current_offset, current_edges, 0,
                ))
            context_count += 1
    finally:
        edge_handle.close()
        index_handle.close()
    return context_count, edge_bytes


def max_selected_count(connection: sqlite3.Connection, kind: int, minimum: int, top_k: int) -> int:
    row = connection.execute(
        "SELECT MAX(count) FROM (" + selected_query(kind, minimum, top_k) + ")",
        (kind, minimum, top_k),
    ).fetchone()
    return int(row[0] or 0)


def build_binary(db_path: Path, output: Path, language: str, word_count: int, minimum: int, top_k: int) -> dict[str, int | str | float]:
    connection = sqlite3.connect(db_path)
    maximum = max(max_selected_count(connection, 2, minimum, top_k), max_selected_count(connection, 3, minimum, top_k))
    max_log_count = math.log1p(maximum)
    with tempfile.TemporaryDirectory(prefix="ninjakeys-edges-") as temporary:
        temporary_path = Path(temporary)
        bg_edges = temporary_path / "bigram.edges"
        bg_index = temporary_path / "bigram.index"
        tri_edges = temporary_path / "trigram.edges"
        tri_index = temporary_path / "trigram.index"
        bg_contexts, bg_bytes = write_edges(connection, 2, minimum, top_k, max_log_count, bg_edges, bg_index)
        tri_contexts, tri_bytes = write_edges(connection, 3, minimum, top_k, max_log_count, tri_edges, tri_index)
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
                    max_log_count,
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
        "max_log_count": max_log_count,
    }


def build_language(
    language: str,
    wordfreq_path: Path,
    corpus_path: Path,
    dictionary_path: Path,
    output: Path,
    minimum: int,
    top_k: int,
) -> dict[str, object]:
    dictionary_words = list(dict.fromkeys(normalize(word, language) for word in read_dictionary(dictionary_path)))
    large_vocabulary = read_wordfreq_vocabulary(wordfreq_path, language)
    allowed = set(dictionary_words) & large_vocabulary
    if not allowed:
        raise ValueError(f"No dictionary words survived {language} vocabulary filtering")
    with tempfile.TemporaryDirectory(prefix=f"ninjakeys-{language}-") as temporary:
        db_path = Path(temporary) / "ngrams.sqlite3"
        counts = aggregate(corpus_path, language, dictionary_words, allowed, db_path)
        result = build_binary(db_path, output, language, len(dictionary_words), minimum, top_k)
    return {"language": language, "counts": counts, "asset": result}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--wordfreq-en", type=Path)
    parser.add_argument("--wordfreq-he", type=Path)
    parser.add_argument("--corpus-en", type=Path)
    parser.add_argument("--corpus-he", type=Path)
    parser.add_argument("--dictionary-en", type=Path, default=ROOT / "app/src/main/assets/dictionary/en.csv")
    parser.add_argument("--dictionary-he", type=Path, default=ROOT / "app/src/main/assets/dictionary/he.csv")
    parser.add_argument("--min-count", type=int, default=MIN_COUNT)
    parser.add_argument("--top-k", type=int, default=MAX_SUCCESSORS)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.min_count < 1 or args.top_k < 1 or args.top_k > 65535:
        raise SystemExit("--min-count must be positive and --top-k must be in 1..65535")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="ninjakeys-sources-") as temporary:
        temporary_path = Path(temporary)
        paths = {}
        source_manifest = {}
        for language in ("en", "he"):
            wordfreq_path = args.wordfreq_en if language == "en" else args.wordfreq_he
            corpus_path = args.corpus_en if language == "en" else args.corpus_he
            if wordfreq_path is None:
                wordfreq_path = temporary_path / f"large_{language}.msgpack.gz"
                source_manifest[f"wordfreq_{language}"] = fetch_or_verify(wordfreq_path, WORD_FREQ[language], f"wordfreq {language}")
            else:
                source_manifest[f"wordfreq_{language}"] = fetch_or_verify(wordfreq_path, WORD_FREQ[language], f"wordfreq {language}")
            if corpus_path is None:
                corpus_path = temporary_path / f"{language}_news_2020_1M.tar.gz"
                source_manifest[f"corpus_{language}"] = fetch_or_verify(corpus_path, CORPORA[language], f"Leipzig corpus {language}")
            else:
                source_manifest[f"corpus_{language}"] = fetch_or_verify(corpus_path, CORPORA[language], f"Leipzig corpus {language}")
            paths[language] = (wordfreq_path, corpus_path)
        results = {}
        for language, dictionary_path in (("en", args.dictionary_en), ("he", args.dictionary_he)):
            wordfreq_path, corpus_path = paths[language]
            results[language] = build_language(
                language,
                wordfreq_path,
                corpus_path,
                dictionary_path,
                args.output_dir / f"{language}.ngram.bin",
                args.min_count,
                args.top_k,
            )
        manifest = {
            "format": "NKG1",
            "generator": "tools/generate_ngrams.py",
            "wordfreq_version": WORD_FREQ_VERSION,
            "sequence_source": "Leipzig Corpora Collection 2020 1M news",
            "normalization": {
                "en": "NFC, case-fold, ASCII letters only",
                "he": "NFKC, case-fold, remove combining marks, Hebrew letters only",
            },
            "min_count": args.min_count,
            "top_k": args.top_k,
            "sources": source_manifest,
            "results": results,
        }
        (args.output_dir / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
