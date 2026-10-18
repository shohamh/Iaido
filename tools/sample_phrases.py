#!/usr/bin/env python3
"""Sample realistic phrases for the opt-in segmentation benchmark from a corpus.

Reads Leipzig sentence tables, OPUS Moses ZIPs, or plain one-sentence-per-line text read-only. Use --modulus/--parity
to select disjoint sentence groups; for example, train on conversation parity 0 modulo 2, then sample tune and heldout
phrases from parities 1 and 3 modulo 4. Every sampled word must be in the dictionary; one-letter words are kept for
the benchmark's tap model.

Output: one phrase per line, `history words | phrase words`, deterministic for a given seed.
"""

from __future__ import annotations

import argparse
import csv
import random
import re
import unicodedata
from pathlib import Path

from generate_ngrams import iter_sentences

TOKEN_RE = {"en": re.compile(r"[A-Za-z]+"), "he": re.compile(r"[א-ת]+")}


def normalize(word: str, language: str) -> str:
    """Same normalisation as generate_ngrams.py."""
    normalized = unicodedata.normalize("NFC" if language == "en" else "NFKC", word).casefold()
    if language == "he":
        normalized = "".join(char for char in normalized if not unicodedata.category(char).startswith("M"))
    return normalized


def sentences(path: Path, language: str):
    for index, sentence in enumerate(iter_sentences(path, language)):
        yield index, sentence


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", type=Path, action="append", required=True,
                        help="sentence corpus or OPUS Moses ZIP; repeat to sample several sources")
    parser.add_argument("--dictionary", type=Path, required=True)
    parser.add_argument("--first-sentence", type=int, default=900_000)
    parser.add_argument("--language", choices=("en", "he"), default="en")
    parser.add_argument("--parity", type=int, required=True,
                        help="use sentences whose index modulo --modulus equals this value")
    parser.add_argument("--modulus", type=int, default=2,
                        help="number of disjoint index groups (default 2, for the Leipzig split)")
    parser.add_argument("--candidate-cap", type=int, default=100_000,
                        help="maximum candidate sentences kept in memory when sampling a large corpus")
    parser.add_argument("--count", type=int, default=500)
    parser.add_argument("--seed", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.modulus < 2 or not 0 <= args.parity < args.modulus or args.candidate_cap < args.count:
        raise SystemExit("require modulus >= 2, parity in 0..modulus-1, and candidate-cap >= count")

    with args.dictionary.open(encoding="utf-8", newline="") as handle:
        vocabulary = {row["word"] for row in csv.DictReader(handle) if row.get("word")}
    pool: list[list[str]] = []
    rng = random.Random(args.seed)
    candidates_seen = 0
    for corpus in args.corpus:
        for index, sentence in sentences(corpus, args.language):
            if index < args.first_sentence or index % args.modulus != args.parity:
                continue
            tokens = [normalize(token, args.language) for token in TOKEN_RE[args.language].findall(sentence)]
            if len(tokens) < 5:
                continue
            candidates_seen += 1
            if len(pool) < args.candidate_cap:
                pool.append(tokens)
            else:
                replace = rng.randrange(candidates_seen)
                if replace < args.candidate_cap:
                    pool[replace] = tokens
    rng.shuffle(pool)
    lines = []
    for tokens in pool:
        if len(lines) >= args.count:
            break
        history = rng.choice((0, 1, 2, 2))
        length = rng.choice((3, 3, 4, 4, 5, 6))
        if len(tokens) < history + length:
            continue
        start = rng.randrange(0, len(tokens) - history - length + 1)
        window = tokens[start:start + history + length]
        if not all(token in vocabulary and (len(token) > 1 or (args.language == 'en' and token in 'ai')) for token in window):
            continue
        lines.append(" ".join(window[:history]) + " | " + " ".join(window[history:]))
    args.output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"{args.output}: {len(lines)} phrases from {candidates_seen:,} eligible sentences")


if __name__ == "__main__":
    main()
