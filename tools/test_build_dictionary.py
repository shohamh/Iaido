import csv
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("build_dictionary.py")
SPEC = importlib.util.spec_from_file_location("build_dictionary", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class BuildDictionaryTest(unittest.TestCase):
    def test_conversion_filters_invalid_rows_sorts_and_deduplicates(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.json"
            destination = root / "dictionary.csv"
            source.write_text(json.dumps([["low", -3], ["high", -1], ["high", -2], ["no-dash", -0.5]]))

            count = MODULE.convert(source, destination)

            self.assertEqual(2, count)
            with destination.open(newline="", encoding="utf-8") as handle:
                rows = list(csv.DictReader(handle))
            self.assertEqual(["high", "low"], [row["word"] for row in rows])
            self.assertGreater(float(rows[0]["frequency"]), float(rows[1]["frequency"]))

    def test_verify_rejects_duplicate_or_unsorted_output(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "dictionary.csv"
            output.write_text("word,frequency\nlow,0.1\nhigh,0.9\nlow,0.2\n", encoding="utf-8")

            with self.assertRaises(ValueError):
                MODULE.verify(output, minimum_entries=2)

    def test_checked_in_source_has_25000_rows(self):
        source = Path(__file__).parent / "data" / "wordfreq-en-25000-log.json"
        self.assertGreaterEqual(len(json.loads(source.read_text(encoding="utf-8"))), 25_000)


if __name__ == "__main__":
    unittest.main()
