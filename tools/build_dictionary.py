#!/usr/bin/env python3
"""Convert a wordfreq JSON export into NinjaKeys' compact CSV asset."""

import csv
import json
import math
import re
import sys
from pathlib import Path

WORD_RE = re.compile(r"^[a-z]+$")


def convert(source: Path, destination: Path) -> int:
    rows = json.loads(source.read_text(encoding="utf-8"))
    entries = {}
    for row in rows:
        if not isinstance(row, list) or len(row) != 2:
            continue
        word, log_frequency = row
        if not isinstance(word, str) or not WORD_RE.fullmatch(word):
            continue
        if not isinstance(log_frequency, (int, float)) or not math.isfinite(log_frequency):
            continue
        entries[word] = math.exp(log_frequency)

    ordered = sorted(entries.items(), key=lambda item: item[1], reverse=True)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(("word", "frequency"))
        writer.writerows(ordered)
    return len(ordered)


def verify(dictionary: Path, minimum_entries: int = 24_000) -> None:
    with dictionary.open(newline="", encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))
    words = [row.get("word", "") for row in rows]
    frequencies = [float(row["frequency"]) for row in rows]
    if len(rows) < minimum_entries:
        raise ValueError(f"expected at least {minimum_entries} rows, got {len(rows)}")
    if any(not WORD_RE.fullmatch(word) for word in words):
        raise ValueError("dictionary contains a non-alphabetic word")
    if len(set(words)) != len(words):
        raise ValueError("dictionary contains duplicate words")
    if any(not math.isfinite(value) or value <= 0 for value in frequencies):
        raise ValueError("dictionary contains an invalid frequency")
    if any(left < right for left, right in zip(frequencies, frequencies[1:])):
        raise ValueError("dictionary is not sorted by descending frequency")


if __name__ == "__main__":
    if len(sys.argv) == 3:
        count = convert(Path(sys.argv[1]), Path(sys.argv[2]))
        verify(Path(sys.argv[2]))
        print(f"wrote {count} entries")
    elif len(sys.argv) == 2 and sys.argv[1] == "--verify":
        verify(Path("app/src/main/assets/dictionary/en.csv"))
        print("dictionary verified")
    else:
        raise SystemExit("usage: build_dictionary.py SOURCE_JSON DESTINATION_CSV | --verify")
