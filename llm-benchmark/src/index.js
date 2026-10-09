// CLI entry point: orchestrates the benchmark sequentially.
//
// Usage:
//   node src/index.js [--limit <n>] [--tasks <n>] [--repeats <n>] [--stream] [--out <path>]
//
// --limit <n>   max configs to run (default: all 6)
// --tasks <n>   max tasks to run (default: all 6)
// --repeats <n> run each config's task set N times (default 1)
// --stream      enable streaming to measure TTFT (firstTokenMs)
// --out <path>  custom output path (default <llm-benchmark>/results/run-<timestamp>.json)

import path from 'node:path';
import { loadEnvFile, getConfigs, JUDGE_MODEL } from '../config.js';
import { TASKS } from '../tasks/tasks.js';
import { chatCompletion } from './provider.js';
import { createResourceSampler, remoteResources, computeTokensPerSec } from './metrics.js';
import { judgeAnswer, deterministicCheck } from './judge.js';
import { buildSummary } from './report.js';
import { writeResults } from './writer.js';

function parseArgs(argv) {
  const args = { limit: null, tasks: null, repeats: 1, stream: false, out: null };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--limit') args.limit = parseInt(argv[++i], 10);
    else if (a === '--tasks') args.tasks = parseInt(argv[++i], 10);
    else if (a === '--repeats') args.repeats = parseInt(argv[++i], 10);
    else if (a === '--stream') args.stream = true;
    else if (a === '--out') args.out = argv[++i];
    else if (a === '--help' || a === '-h') {
      console.log(
        'Usage: node src/index.js [--limit n] [--tasks n] [--repeats n] [--stream] [--out path]',
      );
      process.exit(0);
    } else {
      console.error(`Unknown argument: ${a}`);
      process.exit(1);
    }
  }
  if (!Number.isFinite(args.repeats) || args.repeats < 1) args.repeats = 1;
  return args;
}

function buildMessages(task) {
  return [
    {
      role: 'system',
      content:
        'Ты — экспертный ассистент по программированию. Отвечай по делу, на русском языке, ' +
        'с кодом в блоках с указанием языка.',
    },
    { role: 'user', content: task.prompt },
  ];
}

function failedJudge(message) {
  return {
    correctness: null,
    clarity: null,
    completeness: null,
    codeQuality: null,
    overallScore: null,
    judgeNotes: 'judge failed: ' + message,
    judgeError: message,
  };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  loadEnvFile();

  // Throws a helpful error if LLM_BASE_URL / LLM_API_KEY are missing.
  const allConfigs = getConfigs();
  const configs = args.limit ? allConfigs.slice(0, args.limit) : allConfigs;
  const tasks = args.tasks ? TASKS.slice(0, args.tasks) : TASKS;

  const startedAt = new Date().toISOString();
  console.log(
    `llm-benchmark: ${configs.length} config(s) x ${tasks.length} task(s) x ` +
      `${args.repeats} repeat(s), stream=${args.stream}`,
  );
  console.log(`judge: ${JUDGE_MODEL}\n`);

  const entries = [];

  for (let ci = 0; ci < configs.length; ci++) {
    const cfg = configs[ci]; // id is already an ASCII-safe slug (config.js)
    console.log(`[${ci + 1}/${configs.length}] ${cfg.id}`);

    // Resource sampling wraps the config's whole task loop (all tasks x repeats).
    let sampler = null;
    if (cfg.providerType === 'ollama') {
      sampler = createResourceSampler({ container: cfg.container });
      sampler.start();
    }

    for (let ri = 1; ri <= args.repeats; ri++) {
      for (const task of tasks) {
        const entry = {
          configId: cfg.id,
          providerType: cfg.providerType,
          model: cfg.model,
          temperature: cfg.temperature,
          taskId: task.id,
          category: task.category,
          repeat: ri,
          startedAt: new Date().toISOString(),
          answer: null,
          usage: null,
          timing: null,
          speed: null,
          judge: null,
          deterministic: null,
          resources: null,
          error: null,
        };

        // 1. The request itself.
        try {
          const res = await chatCompletion({
            baseUrl: cfg.baseUrl,
            apiKey: cfg.apiKey,
            model: cfg.model,
            temperature: cfg.temperature,
            messages: buildMessages(task),
            stream: args.stream,
            providerType: cfg.providerType,
          });
          entry.answer = res.text;
          entry.usage = res.usage;
          entry.timing = res.timing;
          entry.speed = {
            tokensPerSec: computeTokensPerSec(res.usage?.completionTokens, res.timing?.totalMs),
          };
        } catch (err) {
          entry.error = `chatCompletion failed: ${err.message}`;
        }

        // 2. Quality: deterministic checks + LLM-as-judge (gpt-4o on GPUStack).
        if (entry.answer) {
          entry.deterministic = deterministicCheck(entry.answer, task.mustContain);
          const judgeCfg = configs.find((c) => c.providerType === 'gpustack') ?? cfg;
          try {
            entry.judge = await judgeAnswer({
              baseUrl: judgeCfg.baseUrl,
              apiKey: judgeCfg.apiKey,
              model: JUDGE_MODEL,
              task,
              answer: entry.answer,
            });
          } catch (err) {
            entry.judge = failedJudge(err.message);
          }
        }

        // 3. Resources: ollama aggregate filled after the sampler stops; gpustack remote.
        if (cfg.providerType === 'ollama') {
          entry.resources = { pending: true };
        } else {
          entry.resources = remoteResources(entry.timing?.firstTokenMs ?? null);
        }

        entries.push(entry);

        const score = entry.judge?.overallScore;
        const tps = entry.speed?.tokensPerSec;
        console.log(
          `  task=${task.id} repeat=${ri} error=${entry.error ? 'YES' : 'no'} ` +
            `score=${score ?? 'n/a'} ` +
            `totalMs=${entry.timing?.totalMs != null ? Math.round(entry.timing.totalMs) : 'n/a'} ` +
            `tps=${tps != null ? Math.round(tps) : 'n/a'}`,
        );
      }
    }

    if (sampler) {
      const resources = sampler.stop();
      for (const e of entries) {
        if (e.configId === cfg.id && e.resources && e.resources.pending) {
          e.resources = resources;
        }
      }
      console.log(
        `  resources: samples=${resources.sampleCount} peakCPU=${resources.peakCpuPct ?? 'n/a'}% ` +
          `peakMem=${resources.peakMemUsedBytes ?? 'n/a'} bytes`,
      );
    }
    console.log('');
  }

  const finishedAt = new Date().toISOString();
  const summary = buildSummary(entries, configs, tasks, args);

  const errorCount = entries.filter((e) => e.error).length;
  const judgeErrorCount = entries.filter((e) => e.judge && e.judge.judgeError).length;

  const doc = {
    meta: {
      startedAt,
      finishedAt,
      durationMs: Date.parse(finishedAt) - Date.parse(startedAt),
      args,
      judgeModel: JUDGE_MODEL,
      nodeVersion: process.version,
      configCount: configs.length,
      taskCount: tasks.length,
      repeats: args.repeats,
      stream: args.stream,
      errorCount,
      judgeErrorCount,
    },
    configs,
    tasks,
    entries,
    summary,
  };

  // --out <path> support: split into dir + file for the writer.
  let writeOpts = {};
  if (args.out) {
    const abs = path.resolve(args.out);
    writeOpts = { dir: path.dirname(abs), file: path.basename(abs) };
  }
  const outPath = writeResults(doc, writeOpts);

  console.log(summary.table);
  console.log('');
  console.log(
    `done: ${entries.length} entries, errors: ${errorCount} request / ${judgeErrorCount} judge`,
  );
  console.log(`results: ${outPath}`);
  // Exit 0 even when some entries errored — partial results are still written.
}

main().catch((err) => {
  console.error('FATAL:', err.message);
  process.exit(1);
});
