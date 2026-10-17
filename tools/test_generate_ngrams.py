import io
import struct
import tarfile
import tempfile
import unittest
from pathlib import Path

from generate_ngrams import BIGRAM_INDEX, HEADER, TRIGRAM_INDEX, aggregate, build_binary, encode_varint, normalize

WORDS = ["the", "cat", "sat", "mat", "dog"]
SENTENCES = ["the cat sat"] * 3 + ["the cat mat"] + ["the dog sat"] * 2


def write_corpus(root: Path) -> Path:
    corpus = root / "fixture.tar.gz"
    with tarfile.open(corpus, "w:gz") as archive:
        payload = "".join(f"{index}\t{sentence}\n" for index, sentence in enumerate(SENTENCES, 1)).encode()
        info = tarfile.TarInfo("fixture_sentences.txt")
        info.size = len(payload)
        archive.addfile(info, io.BytesIO(payload))
    return corpus


def read_varint(data: bytes, position: int) -> tuple[int, int]:
    value = shift = 0
    while True:
        byte = data[position]
        position += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, position
        shift += 7


def parse(data: bytes):
    """Tiny NKG2 reader mirroring CompactNgramScoreStore, for verifying generator output."""
    magic, version, _lang, _flags, words, bg_n, tri_n, bg_bytes, tri_bytes, discount = HEADER.unpack_from(data, 0)
    position = HEADER.size
    bigrams = {}
    for _ in range(bg_n):
        ctx, offset, length, edges, distinct, total = BIGRAM_INDEX.unpack_from(data, position)
        position += BIGRAM_INDEX.size
        bigrams[(ctx,)] = (offset, length, edges, distinct, total)
    trigrams = {}
    for _ in range(tri_n):
        first, second, offset, length, edges, distinct, total = TRIGRAM_INDEX.unpack_from(data, position)
        position += TRIGRAM_INDEX.size
        trigrams[(first, second)] = (offset, length, edges, distinct, total)
    bg_start = position
    tri_start = bg_start + bg_bytes
    assert tri_start + tri_bytes == len(data)

    def edges_of(start, entry):
        offset, length, edges, _distinct, _total = entry
        cursor = start + offset
        previous = 0
        result = {}
        for _ in range(edges):
            delta, cursor = read_varint(data, cursor)
            count, cursor = read_varint(data, cursor)
            previous += delta
            result[previous] = count
        assert cursor == start + offset + length
        return result

    return {
        "magic": magic, "version": version, "words": words, "discount": discount,
        "bigrams": {k: (v[3], v[4], edges_of(bg_start, v)) for k, v in bigrams.items()},
        "trigrams": {k: (v[3], v[4], edges_of(tri_start, v)) for k, v in trigrams.items()},
    }


class GenerateNgramsTest(unittest.TestCase):
    def build(self, root: Path, name: str = "model.bin", **options) -> bytes:
        corpus = write_corpus(root)
        db = root / f"{name}.sqlite3"
        aggregate(corpus, "en", WORDS, set(WORDS), db)
        output = root / name
        minimum = options.pop("minimum", 1)
        top_k = options.pop("top_k", 32)
        build_binary(db, output, "en", len(WORDS), minimum, top_k, **options)
        return output.read_bytes()

    def test_normalization_and_varint(self):
        self.assertEqual("abc", normalize("ABC", "en"))
        self.assertEqual("שלום", normalize("שָׁלוֹם", "he"))
        self.assertEqual(b"\x00", encode_varint(0))
        self.assertEqual(b"\xac\x02", encode_varint(300))

    def test_aggregation_respects_sentence_boundaries_and_vocabulary(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            corpus = root / "fixture.tar.gz"
            with tarfile.open(corpus, "w:gz") as archive:
                payload = b"1\talpha beta gamma\n2\tbeta gamma\n"
                info = tarfile.TarInfo("fixture_sentences.txt")
                info.size = len(payload)
                archive.addfile(info, io.BytesIO(payload))
            db = root / "counts.sqlite3"

            stats = aggregate(corpus, "en", ["alpha", "beta", "gamma"], {"alpha", "beta", "gamma"}, db)
            self.assertEqual(2, stats["sentences"])
            self.assertEqual(5, stats["retained_tokens"])
            output = root / "model.bin"
            result = build_binary(db, output, "en", 3, 1, 32)
            self.assertGreater(result["bigram_contexts"], 0)
            self.assertGreater(result["trigram_contexts"], 0)
            self.assertEqual(output.stat().st_size, result["bytes"])

    def test_nkg2_stores_exact_counts_and_context_totals(self):
        with tempfile.TemporaryDirectory() as directory:
            model = parse(self.build(Path(directory)))
        the, cat, sat, mat, dog = range(5)
        self.assertEqual(b"NKG2", model["magic"])
        self.assertEqual(2, model["version"])
        self.assertEqual(0.75, model["discount"])
        self.assertEqual((2, 6, {cat: 4, dog: 2}), model["bigrams"][(the,)])
        self.assertEqual((2, 4, {sat: 3, mat: 1}), model["bigrams"][(cat,)])
        self.assertEqual((1, 2, {sat: 2}), model["bigrams"][(dog,)])
        self.assertEqual((2, 4, {sat: 3, mat: 1}), model["trigrams"][(the, cat)])
        self.assertEqual((1, 2, {sat: 2}), model["trigrams"][(the, dog)])

    def test_pruning_keeps_totals_and_distinct_of_the_whole_context(self):
        with tempfile.TemporaryDirectory() as directory:
            model = parse(self.build(Path(directory), minimum=2, top_k=1))
        the, cat, sat, mat, dog = range(5)
        # cat -> mat (count 1) is pruned, but total 4 and distinct 2 still describe the full context
        self.assertEqual((2, 4, {sat: 3}), model["bigrams"][(cat,)])
        self.assertEqual((2, 6, {cat: 4}), model["bigrams"][(the,)])

    def test_trigram_context_limit_keeps_largest_contexts_and_trigram_top_k_is_separate(self):
        with tempfile.TemporaryDirectory() as directory:
            model = parse(self.build(Path(directory), trigram_top_k=1, trigram_context_limit=1))
        the, cat, sat, mat, dog = range(5)
        self.assertEqual({(the, cat)}, set(model["trigrams"]))
        self.assertEqual((2, 4, {sat: 3}), model["trigrams"][(the, cat)])
        self.assertEqual({cat: 4, dog: 2}, model["bigrams"][(the,)][2])

    def test_output_is_deterministic_and_discount_is_configurable(self):
        with tempfile.TemporaryDirectory() as directory:
            first = self.build(Path(directory), "a.bin")
            second = self.build(Path(directory), "b.bin")
            custom = parse(self.build(Path(directory), "c.bin", discount=0.5))
        self.assertEqual(first, second)
        self.assertEqual(0.5, custom["discount"])


if __name__ == "__main__":
    unittest.main()
