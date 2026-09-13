# Open Dilemmas

This file records decisions that may need product-owner input. Entries are kept
until answered or intentionally resolved by the implementation.

## 2026-09-14

- Stage 3 data scope: the resolved ticket names English and Hebrew wordfreq
  data, but Stage 3's roadmap demo explicitly targets real English first. I am
  implementing the English 25k baseline now and leaving the Hebrew source for
  Stage 5, unless you want Hebrew bundled earlier.
- Stage 4 keyboard surface: the current Stage 2 view has no bottom-row space,
  punctuation, or backspace controls. I am adding the smallest complete English
  surface needed by the ticket (letters, comma, period, question mark, quote,
  space, backspace, and number flicks) and will record any later UX choices
  here rather than blocking implementation.
