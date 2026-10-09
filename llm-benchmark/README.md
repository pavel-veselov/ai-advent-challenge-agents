# llm-benchmark

Zero-dependency (Node 22+, built-in `fetch`) LLM benchmark that compares **6 model
configurations** (3 models × 2 temperatures) on **quality**, **speed** and **resource
consumption**, plus extra analysis (efficiency score, determinism, conciseness, verdict).

## Configurations

| # | Provider | Model | Temperature |
|---|----------|-------|-------------|
| 1 | gpustack | `glm-5.3-flash` | 0.1 |
| 2 | gpustack | `glm-5.3-flash` | 1.0 |
| 3 | ollama   | `qwen2.5-coder:3b-instruct-q8_0` | 0.1 |
| 4 | ollama   | `qwen2.5-coder:3b-instruct-q8_0` | 1.0 |
| 5 | ollama   | `qwen2.5-coder:3b` | 0.1 |
| 6 | ollama   | `qwen2.5-coder:3b` | 1.0 |

Quality judging uses `gpt-4o` on GPUStack (independent of the tested models).

## What it measures

- **Quality**
  - LLM-as-judge (`gpt-4o`): `correctness`, `clarity`, `completeness`, `codeQuality`
    (1–10 each) + `overallScore` + `judgeNotes`.
  - Deterministic checks: tasks may declare `mustContain[]` tokens; each is tested
    case-insensitively in the answer → `deterministicPass` (null when a task has no checks).
- **Speed** (per request): `totalMs`, `firstTokenMs` (TTFT, via streaming with `--stream`),
  `completionTokens`, `tokensPerSec = completionTokens / (totalMs / 1000)`.
- **Resources** (ollama configs only): a sampler runs
  `docker stats ollama --no-stream=false --format '{{.CPUPerc}}|{{.MemUsage}}|{{.NetIO}}|{{.PIDs}}'`
  around the config's task loop and aggregates avg/peak CPU%, peak mem used/limit, total
  NetIO. Note: `docker stats` emits a row on its native ~1 s refresh ticker; the sampler
  records every row it emits (finer client-side polling is not supported by docker).
  GPUStack runs remotely → recorded as
  `{ note: "remote (server-side), not measurable locally", netRoundTripMs }`.
- **Extra analysis** (`report.js`)
  - Comparison table per config: avgOverallScore, avgTokensPerSec, avgTTFT, avgTotalMs,
    avgTokens, peakCPU%, peakMem.
  - **Efficiency score** = avgOverallScore / avgCompletionTokens (quality per token).
  - **Determinism** (with `--repeats > 1`): stddev of overallScore and of latency per
    config, plus the consistency delta between temperature 0.1 and 1.0 per model.
  - **Conciseness**: avg completion tokens per task / per model (lower = more concise).
  - **Verdict**: model-vs-model comparison and which temperature wins per model
    (quality vs consistency).

## Setup

```powershell
# 1. Credentials come from the environment (never hardcoded):
#    LLM_BASE_URL, LLM_API_KEY  (GPUStack), OLLAMA_BASE_URL (optional, default
#    http://localhost:11434)
#    Either export them or copy .env.example to .env and fill it in.

# 2. Run the full benchmark (sequential, all 6 configs × 6 tasks):
node src/index.js

# Common flags:
node src/index.js --stream --repeats 3 --out results/my-run.json
#   --limit <n>     max configs to run (default: all 6)
#   --tasks <n>     max tasks to run (default: all 6)
#   --repeats <n>   run each config's task set N times (default 1)
#   --stream        enable streaming to measure TTFT (firstTokenMs)
#   --out <path>    custom output path (default results/run-<timestamp>.json)

# Quick smoke (1 task × first 3 configs):
npm run smoke
```

Everything runs **sequentially** so local CPU inference is comparable. Results are
written to `results/run-<timestamp>.json` containing raw `entries`, per-config `summary`,
determinism/conciseness sections and the `verdict`.

## Layout

```
llm-benchmark/
  package.json        # type: module; scripts: bench, smoke
  .env.example        # placeholder env vars (never commit a real .env)
  config.js           # loads .env/process.env, exports providers + 6-config matrix
  tasks/tasks.js      # fixed dataset: 6 Russian coding tasks (codegen, bugfix,
                      # explanation, sql, refactor, tricky-output)
  src/
    index.js          # CLI entry, sequential orchestration
    provider.js       # chatCompletion() — OpenAI-compatible, stream + usage, TTFT
    metrics.js        # speed metrics + docker stats resource sampler (ollama only)
    judge.js          # gpt-4o LLM-as-judge + deterministic mustContain checks
    report.js         # aggregation, tables, efficiency/determinism/verdict
    writer.js         # writes results/run-<timestamp>.json
```

## Notes

- Zero runtime npm dependencies; only Node 22+ built-ins (`fetch`, `child_process`).
- Resource sampling requires Docker with the local container named exactly `ollama`;
  if Docker is unavailable the run continues with `resources.available = false`.
- The GPUStack API key/base URL are read **only** from the environment
  (`LLM_BASE_URL` / `LLM_API_KEY`) or an optional local `.env` (git-ignored).
