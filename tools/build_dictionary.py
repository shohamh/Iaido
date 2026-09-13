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


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: build_dictionary.py SOURCE_JSON DESTINATION_CSV")
    count = convert(Path(sys.argv[1]), Path(sys.argv[2]))
    if count < 24_000:
        raise SystemExit(f"expected at least 24000 alphabetic entries, wrote {count}")
    print(f"wrote {count} entries")
