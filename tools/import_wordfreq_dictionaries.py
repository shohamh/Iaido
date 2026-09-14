#!/usr/bin/env python3
"""Convert pinned wordfreq cBpack files into Iaido CSV assets."""

from __future__ import annotations

import gzip
import math
import msgpack
import re
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE_VERSION = "v3.0.2"
SOURCES = {
    "en": ROOT / "tools/data/wordfreq-v3.0.2-small-en.msgpack.gz",
    "he": ROOT / "tools/data/wordfreq-v3.0.2-small-he.msgpack.gz",
}
DESTINATIONS = {
    "en": ROOT / "app/src/main/assets/dictionary/en.csv",
    "he": ROOT / "app/src/main/assets/dictionary/he.csv",
}
ENGLISH_WORD = re.compile(r"^[a-z]+$")
HEBREW_WORD = re.compile(r"^[\u05d0-\u05ea]+$")


def normalize(word: str, language: str) -> str:
    form = "NFC" if language == "en" else "NFKC"
    normalized = unicodedata.normalize(form, word).casefold()
    if language == "he":
        normalized = "".join(char for char in normalized if not unicodedata.category(char).startswith("M"))
    return normalized


def read_entries(source: Path, language: str) -> list[tuple[str, float]]:
    with gzip.open(source, "rb") as handle:
        packed = msgpack.unpack(handle, raw=False)
    if packed[0] != {"format": "cB", "version": 1}:
        raise ValueError(f"Unexpected cBpack header in {source}")

    matcher = ENGLISH_WORD if language == "en" else HEBREW_WORD
    entries: dict[str, float] = {}
    for centibel, bucket in enumerate(packed[1:]):
        frequency = math.pow(10.0, -centibel / 100.0)
        for source_word in bucket:
            word = normalize(source_word, language)
            if matcher.fullmatch(word):
                entries[word] = max(frequency, entries.get(word, 0.0))
    return sorted(entries.items(), key=lambda row: (-row[1], row[0]))


def write_csv(destination: Path, entries: list[tuple[str, float]]) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("w", encoding="utf-8", newline="") as handle:
        handle.write("word,frequency\n")
        handle.writelines(f"{word},{frequency:.12g}\n" for word, frequency in entries)


def main() -> None:
    for language, source in SOURCES.items():
        entries = read_entries(source, language)
        write_csv(DESTINATIONS[language], entries)
        print(f"{SOURCE_VERSION} {language}: wrote {len(entries)} entries to {DESTINATIONS[language]}")


if __name__ == "__main__":
    main()
