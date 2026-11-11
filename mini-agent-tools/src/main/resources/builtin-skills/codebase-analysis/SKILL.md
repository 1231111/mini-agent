---
name: codebase-analysis
description: Trace code paths and explain a repository's architecture or bug using focused searches and file evidence.
---

# Codebase analysis

Use this skill for repository questions, architecture reviews, and bug diagnosis.

1. Start with the relevant entry point, API, or error text. Use `search_code` to locate symbols and `list_files` to understand nearby structure; use `read_package` for a Java package when several related source files matter.
2. Read the smallest set of source files that can establish the execution path. Follow callers and data flow from input to output, and distinguish observed code behavior from assumptions.
3. For a suspected bug, identify a concrete trigger, the failing condition, and the resulting user-visible symptom. Check tests and configuration for an existing contract before proposing changes.
4. When editing is requested, make a focused change, preserve unrelated working-tree edits, and run the narrowest meaningful test or validation. Do not claim a test passed unless it was run successfully.
5. Explain findings with file paths and line references. Separate root cause, impact, fix, and remaining uncertainty.
