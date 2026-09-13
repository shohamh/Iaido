# English dictionary asset

`en.csv` is generated from the `wordfreq-en-25000` export using
`tools/build_dictionary.py`. The source data is distributed under CC BY-SA 4.0
as documented by the upstream repository; this asset retains the source's
frequency ordering and converts log frequencies to positive frequency values.

Only lowercase alphabetic entries are bundled because the current keyboard
layout and candidate generator operate on letter keys. The source contains
some punctuation-bearing entries, so the normalized asset contains 24k+ valid
letter-only words rather than claiming all 25k source rows.
