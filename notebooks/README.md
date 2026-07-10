# The assay book

The documentation book is a set of Clay notebooks: Markdown files for
prose-only chapters and ordinary Clojure namespaces for everything with
runnable examples. [Clay](https://scicloj.github.io/clay/) evaluates the
`.clj` chapters and renders the whole set as a
[Quarto](https://quarto.org) book.

Every example actually runs at render time. So that rendering is
deterministic and needs no API keys or network, executable chapters use
a canned in-process provider adapter (`book.demo`, in this directory) —
the code is identical to what you'd run against a real provider; only
the config differs.

## Rendering

Requires the `quarto` CLI on your PATH (only for the final book;
evaluation itself is pure JVM):

    bb book          # or: clojure -M:book

Output lands in `docs/` (ready for GitHub Pages).

## Chapters

| File                             | Content                                        |
|----------------------------------|------------------------------------------------|
| `index.md`                       | what assay is, installation                    |
| `getting_started.clj`            | config, generate, the response map             |
| `conversations_and_streaming.clj`| multi-turn, streaming                          |
| `tools.clj`                      | function calling, manual tool handling         |
| `evals.clj`                      | the eval system, end to end (the flagship)     |
| `design.md`                      | keyspace, compatibility promises, architecture |
| `adapters.md`                    | writing a provider adapter                     |
| `roadmap.md`                     | planned (additive) extensions                  |

Style note for `.clj` chapters: prose lives in `;;` comment paragraphs
written as one long line per paragraph — no hard wrapping; let your
editor soft-wrap.
