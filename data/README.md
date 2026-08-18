# Market data

Nothing in this directory is committed — ITCH session files are multiple GB.
`.gitignore` excludes everything here except this file.

## Getting a session file

Nasdaq publishes free sample TotalView-ITCH 5.0 files covering full trading
days. They live on Nasdaq's public data server (`emi.nasdaq.com`, under the
ITCH directory), named by date, e.g. `01302020.NASDAQ_ITCH50.gz`. Exact paths
move around occasionally — if the link is stale, search for
"NASDAQ TotalView-ITCH 5.0 sample file".

Grab one day. One is enough for everything through Phase 6.

## Decompress before use

The parser memory-maps its input, which requires the decompressed file. It will
refuse a `.gz` with a message telling you so.

```bash
gunzip data/01302020.NASDAQ_ITCH50.gz
```

Expect roughly 5–12 GB decompressed depending on the date. Make sure you have
the disk space before starting.

## Run it

```bash
./gradlew replay --args="data/01302020.NASDAQ_ITCH50"
```

You should see a message-mix histogram. The number to note is the order
lifecycle share — adds, cancels, deletes and replaces as a fraction of all
traffic. It is the fact that justifies the entire design of the fast book.

## The spec

The authoritative field layouts are in Nasdaq's *TotalView-ITCH 5.0*
specification PDF, on nasdaqtrader.com under technical support → specifications
→ data products. Everything in `Itch.java` is transcribed from it; keep the PDF
open when you extend the parser to message types it does not yet model.
