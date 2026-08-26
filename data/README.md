# Market data

Nothing in this directory is committed — ITCH session files are multiple GB.
`.gitignore` excludes everything here except this file.

## Getting a session file

Nasdaq publishes free sample TotalView-ITCH 5.0 files covering full trading
days, at:

    https://emi.nasdaq.com/ITCH/Nasdaq%20ITCH/

Files are named by date, e.g. `01302020.NASDAQ_ITCH50.gz`. Fifteen sessions are
posted, spanning 2018–2020. Verified sizes, compressed:

| file | compressed |
|---|---|
| `12302019.NASDAQ_ITCH50.gz` | 3.28 GB |
| `03272019.NASDAQ_ITCH50.gz` | 5.13 GB |
| `01302020.NASDAQ_ITCH50.gz` | 5.21 GB |

`12302019` is the smallest of the set and the cheapest one to start with — the
day after Christmas is a thin session. Grab one day; one is enough for
everything through Phase 6.

```bash
curl -o data/12302019.NASDAQ_ITCH50.gz   "https://emi.nasdaq.com/ITCH/Nasdaq%20ITCH/12302019.NASDAQ_ITCH50.gz"
```

## Decompress before use

The parser memory-maps its input, which requires the decompressed file. It will
refuse a `.gz` with a message telling you so.

```bash
gunzip data/01302020.NASDAQ_ITCH50.gz
```

Expect roughly 3-4x the compressed size once decompressed, so budget 12-20 GB
free for the file plus the `.gz` alongside it. Delete the `.gz` after
decompressing if space is tight.

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
