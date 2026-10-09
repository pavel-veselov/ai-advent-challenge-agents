// Aggregation + comparison report: per-config aggregates (quality, speed, resources),
// efficiency score, determinism (repeats>1), conciseness matrix, ASCII table, verdict.

function mean(arr) {
  if (!arr.length) return null;
  return arr.reduce((a, b) => a + b, 0) / arr.length;
}

function stddev(arr) {
  if (arr.length < 2) return null;
  const m = mean(arr);
  const variance = arr.reduce((acc, v) => acc + (v - m) ** 2, 0) / (arr.length - 1);
  return Math.sqrt(variance);
}

function round(v, digits = 2) {
  if (v === null || v === undefined || !Number.isFinite(v)) return null;
  const p = 10 ** digits;
  return Math.round(v * p) / p;
}

export function fmtBytes(bytes) {
  if (bytes === null || bytes === undefined || !Number.isFinite(bytes)) return 'n/a';
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB'];
  let v = bytes;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${round(v, 1)}${units[i]}`;
}

function groupBy(arr, keyFn) {
  const map = new Map();
  for (const item of arr) {
    const k = keyFn(item);
    if (!map.has(k)) map.set(k, []);
    map.get(k).push(item);
  }
  return map;
}

/**
 * One aggregate record per config: quality (judge), speed, resources (ollama only,
 * flat fields), efficiency, variance, deterministic pass rate.
 */
function perConfigSummary(configs, entries) {
  const byConfig = groupBy(entries, (e) => e.configId);

  return configs.map((cfg) => {
    const es = byConfig.get(cfg.id) || [];
    const ok = es.filter((e) => !e.error && e.judge && e.judge.overallScore !== null);
    const speeds = ok.map((e) => e.speed?.tokensPerSec).filter((v) => v !== null);
    const ttfts = ok.map((e) => e.timing?.firstTokenMs).filter((v) => v !== null);
    const totals = ok.map((e) => e.timing?.totalMs).filter((v) => v !== null);
    const completions = ok.map((e) => e.usage?.completionTokens).filter((v) => v !== null);
    const prompts = ok.map((e) => e.usage?.promptTokens).filter((v) => v !== null);
    const scores = ok.map((e) => e.judge.overallScore);

    const detChecked = es.filter(
      (e) => e.deterministic && e.deterministic.deterministicPass !== null,
    );
    const detPassed = detChecked.filter((e) => e.deterministic.deterministicPass === true);

    const res = (es.find((e) => e.resources && e.resources.available) || {}).resources || null;
    const avgCompletionTokens = mean(completions);
    const avgOverallScore = mean(scores);

    return {
      configId: cfg.id, // ASCII-safe slug from config.js
      providerType: cfg.providerType,
      model: cfg.model,
      temperature: cfg.temperature,
      runs: es.length,
      successfulRuns: ok.length,
      failedRuns: es.filter((e) => e.error).length,
      avgOverallScore: round(avgOverallScore),
      avgCorrectness: round(mean(ok.map((e) => e.judge.correctness))),
      avgClarity: round(mean(ok.map((e) => e.judge.clarity))),
      avgCompleteness: round(mean(ok.map((e) => e.judge.completeness))),
      avgCodeQuality: round(mean(ok.map((e) => e.judge.codeQuality))),
      avgTokensPerSec: round(mean(speeds)),
      avgTTFT: round(mean(ttfts), 1),
      avgTotalMs: round(mean(totals), 1),
      avgCompletionTokens: round(mean(completions), 1),
      avgPromptTokens: round(mean(prompts), 1),
      efficiencyScore:
        avgOverallScore && avgCompletionTokens
          ? round(avgOverallScore / avgCompletionTokens, 4)
          : null,
      scoreStdDev: round(stddev(scores)),
      totalMsStdDev: round(stddev(totals), 1),
      deterministicPassRate: detChecked.length
        ? round(detPassed.length / detChecked.length, 3)
        : null,
      avgCpuPct: res ? round(res.avgCpuPct) : null,
      peakCpuPct: res ? round(res.peakCpuPct) : null,
      peakMemUsedBytes: res ? res.peakMemUsedBytes : null,
      peakMemUsedHuman: res ? fmtBytes(res.peakMemUsedBytes) : null,
      peakMemLimitBytes: res ? res.peakMemLimitBytes : null,
      totalNetIOBytes: res ? res.totalNetIOBytes : null,
      resourcesAvailable: res ? true : false,
      resourcesNote: (es.find((e) => e.resources) || {}).resources?.note ?? null,
    };
  });
}

/**
 * Determinism (only meaningful with args.repeats > 1): per model, compare
 * scoreStdDev & latencyStdDev at temperature 0.1 vs 1.0 plus the deltas.
 */
function determinismSummary(entries) {
  const byModel = groupBy(entries, (e) => `${e.providerType}/${e.model}`);
  const result = {};
  for (const [modelKey, es] of byModel) {
    const byTemp = groupBy(es, (e) => e.temperature);
    const perTemp = {};
    for (const [temp, ts] of byTemp) {
      const ok = ts.filter((e) => !e.error && e.judge?.overallScore !== null);
      // Variance across repeats of the same task = true determinism signal.
      const perTask = groupBy(ok, (e) => e.taskId);
      const repeatSds = [];
      for (const group of perTask.values()) {
        const sd = stddev(group.map((e) => e.judge.overallScore));
        if (sd !== null) repeatSds.push(sd);
      }
      const latencies = ok.map((e) => e.timing?.totalMs).filter((v) => v !== null);
      perTemp[String(temp)] = {
        runs: ts.length,
        avgOverallScore: round(mean(ok.map((e) => e.judge.overallScore))),
        scoreStdDev: round(stddev(ok.map((e) => e.judge.overallScore))),
        meanRepeatScoreStdDev: round(mean(repeatSds)),
        latencyStdDev: round(stddev(latencies), 1),
      };
    }

    const low = perTemp['0.1'];
    const high = perTemp['1.0'];
    const scoreDelta =
      low && high && low.scoreStdDev !== null && high.scoreStdDev !== null
        ? round(high.scoreStdDev - low.scoreStdDev)
        : null;
    const latencyDelta =
      low && high && low.latencyStdDev !== null && high.latencyStdDev !== null
        ? round(high.latencyStdDev - low.latencyStdDev, 1)
        : null;
    const stabilityWinner =
      scoreDelta === null ? null : scoreDelta > 0 ? 't0.1' : scoreDelta < 0 ? 't1.0' : 'tie';

    result[modelKey] = {
      temperatures: perTemp,
      scoreStdDevDelta: scoreDelta,
      latencyStdDevDelta: latencyDelta,
      stabilityWinner,
    };
  }
  return result;
}

/** Conciseness matrix: avg completionTokens per task per config (+ per model). */
function concisenessSummary(entries, tasks) {
  const byTask = groupBy(entries, (e) => e.taskId);
  const perTask = {};
  for (const task of tasks) {
    const es = byTask.get(task.id) || [];
    if (!es.length) continue;
    const byConfig = groupBy(es, (e) => e.configId);
    perTask[task.id] = Object.fromEntries(
      [...byConfig.entries()].map(([k, rows]) => [
        k,
        round(mean(rows.map((e) => e.usage?.completionTokens).filter((v) => v !== null)), 1),
      ]),
    );
  }

  const byModel = groupBy(entries, (e) => `${e.providerType}/${e.model}`);
  const perModel = Object.fromEntries(
    [...byModel.entries()].map(([k, es]) => [
      k,
      round(
        mean(es.map((e) => e.usage?.completionTokens).filter((v) => v !== null)),
        1,
      ),
    ]),
  );

  return { avgTokensPerTask: perTask, avgTokensPerModel: perModel };
}

/** ASCII comparison table (ASCII-only, consistent column widths). */
export function renderComparisonTable(summary) {
  const perConfig = summary.perConfig || [];
  const header = [
    'config',
    'score',
    'tok/s',
    'ttftMs',
    'totalMs',
    'avgTok',
    'eff',
    'det%',
    'avgCpu%',
    'cpuPk%',
    'memPk',
  ];
  const rows = perConfig.map((c) => [
    c.configId,
    c.avgOverallScore ?? 'n/a',
    c.avgTokensPerSec ?? 'n/a',
    c.avgTTFT ?? 'n/a',
    c.avgTotalMs ?? 'n/a',
    c.avgCompletionTokens ?? 'n/a',
    c.efficiencyScore ?? 'n/a',
    c.deterministicPassRate === null ? 'n/a' : `${Math.round(c.deterministicPassRate * 100)}%`,
    c.avgCpuPct ?? '-',
    c.peakCpuPct ?? '-',
    c.peakMemUsedHuman ?? '-',
  ]);

  const widths = header.map((h, i) =>
    Math.max(h.length, ...rows.map((r) => String(r[i]).length)),
  );
  const line = (cells) => cells.map((c, i) => String(c).padEnd(widths[i])).join(' | ');
  const sep = widths.map((w) => '-'.repeat(w)).join('-+-');

  return [line(header), sep, ...rows.map(line)].join('\n');
}

/**
 * Verdict (Russian): best quality / fastest / most efficient / best resources across
 * the 3 models, plus temperature winner per model (quality vs stability).
 */
export function buildVerdict(summary) {
  const perConfig = summary.perConfig || [];
  const determinism = summary.determinism || null;

  const byModel = groupBy(perConfig, (c) => `${c.providerType}/${c.model}`);
  const modelStats = [...byModel.entries()].map(([model, temps]) => ({
    model,
    temps,
    avgScore: mean(temps.map((t) => t.avgOverallScore).filter((v) => v !== null)),
    avgTps: mean(temps.map((t) => t.avgTokensPerSec).filter((v) => v !== null)),
    eff: mean(temps.map((t) => t.efficiencyScore).filter((v) => v !== null)),
  }));

  const lines = ['=== VERDICT ==='];

  const rankedByScore = [...modelStats].sort((a, b) => (b.avgScore ?? 0) - (a.avgScore ?? 0));
  if (rankedByScore.length && rankedByScore[0].avgScore !== null) {
    lines.push(
      `1. Качество: ${rankedByScore
        .map((m, i) => `${i + 1}) ${m.model} (${m.avgScore ?? 'n/a'})`)
        .join('; ')}`,
    );
  }
  const fastest = [...modelStats].sort((a, b) => (b.avgTps ?? 0) - (a.avgTps ?? 0))[0];
  if (fastest && fastest.avgTps !== null) {
    lines.push(`2. Скорость: быстрее всех ${fastest.model} (${round(fastest.avgTps)} tok/s avg).`);
  }
  const mostEfficient = [...modelStats].sort((a, b) => (b.eff ?? 0) - (a.eff ?? 0))[0];
  if (mostEfficient && mostEfficient.eff !== null) {
    lines.push(
      `3. Эффективность (качество/токен): ${mostEfficient.model} (eff ${round(mostEfficient.eff, 4)}).`,
    );
  }
  const withRes = perConfig.filter((c) => c.resourcesAvailable && c.peakCpuPct !== null);
  if (withRes.length) {
    const bestRes = [...withRes].sort((a, b) => a.peakCpuPct - b.peakCpuPct)[0];
    lines.push(
      `4. Ресурсы (среди локальных): меньше всех CPU грузит ${bestRes.configId} (peak ${bestRes.peakCpuPct}%, mem peak ${bestRes.peakMemUsedHuman}).`,
    );
  }

  for (const m of modelStats) {
    const tLow = m.temps.find((t) => t.temperature === 0.1);
    const tHigh = m.temps.find((t) => t.temperature === 1.0);
    if (!tLow || !tHigh) continue;
    const qWinner =
      tLow.avgOverallScore === tHigh.avgOverallScore
        ? 'ничья'
        : (tLow.avgOverallScore ?? 0) > (tHigh.avgOverallScore ?? 0)
          ? 't0.1'
          : 't1.0';

    const det = determinism?.[m.model];
    const stabilityNote =
      det && det.stabilityWinner
        ? `стабильность: победитель ${det.stabilityWinner} (delta scoreStdDev ${det.scoreStdDevDelta}, delta latencyStdDev ${det.latencyStdDevDelta})`
        : 'данных по стабильности недостаточно (нужен --repeats > 1)';

    lines.push(
      `[${m.model}] температура: по качеству ${qWinner} (t0.1=${tLow.avgOverallScore ?? 'n/a'}, t1.0=${tHigh.avgOverallScore ?? 'n/a'}); ${stabilityNote}.`,
    );
  }

  return lines.join('\n');
}

/**
 * Full summary construction.
 * @param {Array} entries  raw run entries
 * @param {Array} configs  configs actually run
 * @param {Array} tasks    tasks actually run
 * @param {object} args    parsed CLI args ({ repeats, ... })
 */
export function buildSummary(entries, configs, tasks, args) {
  const perConfig = perConfigSummary(configs, entries);
  const conciseness = concisenessSummary(entries, tasks);
  const repeats = args?.repeats ?? 1;

  // Determinism is only meaningful across repeats — include the section only then.
  const determinism = repeats > 1 ? determinismSummary(entries) : null;

  const summary = { perConfig, conciseness, verdict: null, table: null };
  if (determinism) summary.determinism = determinism;

  summary.verdict = buildVerdict(summary);
  summary.table = renderComparisonTable(summary);
  return summary;
}
