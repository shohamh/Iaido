---
id: 1
title: Gesture-recognition algorithm architecture
type: grilling
status: open
assignee: null
blocked_by: []
---

## Question

Design the pluggable gesture-recognition interface (so the matching algorithm can be swapped later) and the initial concrete algorithm: classic path-matching of a drawn trace against a weighted dictionary/trie, in the style of Swype/Nintype. Covers: how a gesture path is sampled/normalized, how candidate words are scored against the path, how ties/ambiguity are resolved, and the shape of the interface a future alternate algorithm would implement.
