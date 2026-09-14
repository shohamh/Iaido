import io
import tarfile
import tempfile
import unittest
from pathlib import Path

from generate_ngrams import aggregate, build_binary, encode_varint, normalize


class GenerateNgramsTest(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
