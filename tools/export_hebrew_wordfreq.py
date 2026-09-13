#!/usr/bin/env python3
"""Export the approved wordfreq Hebrew baseline as a bundled CSV asset."""

import csv
import math
import re
from pathlib import Path

from wordfreq import get_frequency_dict

HEBREW_WORD = re.compile(r"^[א-ת]+$")


def main(destination: Path) -> None:
    entries = [
        (word, math.pow(10.0, frequency))
        for word, frequency in get_frequency_dict("he", wordlist="best").items()
        if HEBREW_WORD.fullmatch(word) and math.isfinite(frequency)
    ]
    entries.sort(key=lambda row: row[1], reverse=True)
    entries = entries[:50_000]
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(("word", "frequency"))
        writer.writerows(entries)
    print(f"wrote {len(entries)} entries")


if __name__ == "__main__":
    main(Path("app/src/main/assets/dictionary/he.csv"))
