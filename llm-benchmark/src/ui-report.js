// Self-contained HTML dashboard generator: reads a results JSON produced by
// src/index.js, embeds it in full (entries + answers) into a single
// results/report.html and renders everything client-side — file://-safe,
// zero dependencies, no network, no external fonts.
//
// Security: every `<` in the embedded JSON payload is escaped as \u003c so a
// `</script>` inside a model answer cannot break out of the script tag;
// configs[].apiKey is stripped from the embedded clone (secrets don't belong
// in a shareable report).

import { existsSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { fmtBytes } from './report.js';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');

// ---------------------------------------------------------------------------
// CLI path resolution: argv[2] > results/bench-full.json > newest run-*.json
// ---------------------------------------------------------------------------

function newestRunFile(dir) {
  if (!existsSync(dir)) throw new Error('results dir not found: ' + dir);
  const runs = readdirSync(dir)
    .filter((f) => /^run-.*\.json$/.test(f))
    .map((f) => ({ file: f, mtime: statSync(join(dir, f)).mtimeMs }))
    .sort((a, b) => b.mtime - a.mtime);
  if (!runs.length) throw new Error('no run-*.json files in ' + dir);
  return join(dir, runs[0].file);
}

export function resolveInputPath(argv2) {
  if (argv2) return resolve(argv2);
  const fallback = join(ROOT, 'results', 'bench-full.json');
  if (existsSync(fallback)) return fallback;
  return newestRunFile(join(ROOT, 'results'));
}

/** Remove per-config secrets from the embedded clone (baseUrl is kept). */
function stripSecrets(data) {
  const configs = (data.configs || []).map(({ apiKey, ...rest }) => rest);
  return { ...data, configs };
}

// ---------------------------------------------------------------------------
// CSS: dark terminal-lab theme, custom properties, system font stacks only
// ---------------------------------------------------------------------------

const CSS = `
:root {
  --bg: #0a0d13; --bg-soft: #0e1219; --panel: #11161f; --panel-2: #161c28;
  --border: #232c3e; --border-soft: #1a2231;
  --text: #e8ebf2; --muted: #96a1b8; --faint: #5d6880;
  --accent: #ffb454; --best: #34d399; --best-bg: rgba(52,211,153,0.10);
  --good: #34d399; --warn: #fbbf24; --bad: #f87171;
  --sky: #7dd3fc; --violet: #c4b5fd;
  --mono: ui-monospace, 'Cascadia Code', Consolas, 'JetBrains Mono', Menlo, monospace;
  --sans: 'Segoe UI', -apple-system, 'Helvetica Neue', Arial, sans-serif;
  --radius: 10px;
}
* { box-sizing: border-box; }
body {
  margin: 0; color: var(--text); font: 15px/1.55 var(--sans);
  background:
    radial-gradient(1100px 500px at 85% -10%, rgba(255,180,84,0.07), transparent 60%),
    radial-gradient(900px 600px at -10% 25%, rgba(125,211,252,0.05), transparent 55%),
    var(--bg);
}
.wrap { max-width: 1280px; margin: 0 auto; padding: 0 28px; }
.glow {
  position: fixed; inset: 0; pointer-events: none; z-index: 0;
  background:
    repeating-linear-gradient(0deg, rgba(255,255,255,0.014) 0 1px, transparent 1px 44px),
    repeating-linear-gradient(90deg, rgba(255,255,255,0.014) 0 1px, transparent 1px 44px);
  mask-image: linear-gradient(180deg, rgba(0,0,0,0.9), rgba(0,0,0,0.25) 60%, transparent);
}
header.top, main, footer { position: relative; z-index: 1; }
.kicker {
  font-family: var(--mono); font-size: 11px; letter-spacing: 0.22em;
  text-transform: uppercase; color: var(--accent); margin: 34px 0 10px;
}
h1 { margin: 0 0 14px; font-size: 30px; font-weight: 650; letter-spacing: -0.01em; }
h1 .accent { color: var(--faint); font-weight: 400; }
.chips { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 34px; }
.chip {
  font-family: var(--mono); font-size: 12px; color: var(--muted);
  border: 1px solid var(--border); border-radius: 999px; padding: 4px 11px;
  background: rgba(255,255,255,0.02);
}
.chip.err { color: var(--bad); border-color: rgba(248,113,113,0.45); }
.chip.ok { color: var(--best); border-color: rgba(52,211,153,0.4); }
section { margin: 0 0 40px; }
.sec-head { display: flex; align-items: baseline; gap: 12px; margin: 0 0 14px; flex-wrap: wrap; }
.sec-no { font-family: var(--mono); color: var(--accent); font-size: 13px; }
.sec-head h2 { margin: 0; font-size: 19px; font-weight: 600; letter-spacing: -0.01em; }
.sec-sub { margin: 0; color: var(--faint); font-size: 12.5px; font-family: var(--mono); }
.panel { background: var(--panel); border: 1px solid var(--border-soft); border-radius: var(--radius); padding: 18px 20px; }
.verdict { border-left: 3px solid var(--accent); }
.verdict .v-label {
  font-family: var(--mono); font-size: 11px; letter-spacing: 0.2em;
  color: var(--accent); text-transform: uppercase; margin-bottom: 8px;
}
.verdict .v-line { padding: 3px 0; font-size: 14.5px; }
.verdict .v-line b { color: var(--accent); font-weight: 600; }
.tbl-wrap { padding: 0; overflow: auto; max-height: 74vh; }
table.tbl { width: 100%; border-collapse: collapse; font-size: 13.5px; }
.tbl th, .tbl td { padding: 9px 12px; border-bottom: 1px solid var(--border-soft); text-align: right; white-space: nowrap; }
.tbl th {
  position: sticky; top: 0; z-index: 2; background: var(--panel-2);
  font-family: var(--mono); font-size: 11.5px; font-weight: 500; color: var(--muted);
  text-transform: uppercase; letter-spacing: 0.06em;
  box-shadow: inset 0 -1px 0 var(--border);
}
.tbl td { font-family: var(--mono); font-variant-numeric: tabular-nums; }
.tbl td.cfg, .tbl th:first-child { text-align: left; }
.tbl tbody tr { transition: background 0.15s ease; }
.tbl tbody tr:hover { background: rgba(255,255,255,0.025); }
.tbl td.best { background: var(--best-bg); color: var(--best); font-weight: 600; }
.tbl td.cfg { min-width: 300px; }
.cfg-line { display: flex; gap: 6px; margin-bottom: 3px; }
.badge {
  font-family: var(--mono); font-size: 10px; letter-spacing: 0.08em; text-transform: uppercase;
  border-radius: 4px; padding: 1px 7px; border: 1px solid;
}
.b-gpustack { color: var(--sky); border-color: rgba(125,211,252,0.4); background: rgba(125,211,252,0.07); }
.b-ollama { color: var(--violet); border-color: rgba(196,181,253,0.4); background: rgba(196,181,253,0.07); }
.chip-t { font-family: var(--mono); font-size: 10.5px; color: var(--warn); border-color: rgba(251,191,36,0.35); padding: 1px 8px; }
.cfg-id { font-family: var(--mono); font-size: 12.5px; color: var(--text); }
#charts { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; }
.chart-card .chart-head { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; margin-bottom: 10px; }
.chart-card h3 { margin: 0; font-size: 14.5px; font-weight: 600; }
.chart-sub { font-family: var(--mono); font-size: 11px; color: var(--faint); text-align: right; }
svg.chart { width: 100%; height: auto; display: block; }
svg.chart text { font-family: var(--mono); }
.svg-label { fill: var(--muted); font-size: 11px; }
.svg-val { fill: var(--text); font-size: 11.5px; font-variant-numeric: tabular-nums; }
.bar-acc { fill: var(--accent); opacity: 0.85; }
.bar-good { fill: var(--good); opacity: 0.8; }
.bar-warn { fill: var(--warn); opacity: 0.8; }
.bar-bad { fill: var(--bad); opacity: 0.8; }
.bar-time { fill: var(--violet); opacity: 0.75; }
.bar-eff { fill: var(--best); opacity: 0.75; }
.bar-avg { fill: var(--accent); opacity: 0.85; }
.bar-peak { fill: var(--bad); opacity: 0.7; }
.bar-ram { fill: var(--sky); opacity: 0.75; }
.bar-na { fill: var(--faint); opacity: 0.35; }
.legend { display: flex; gap: 16px; margin: -4px 0 12px; font-family: var(--mono); font-size: 11px; color: var(--muted); }
.legend .sw { display: inline-block; width: 10px; height: 10px; border-radius: 3px; margin-right: 6px; vertical-align: -1px; }
.sw-avg { background: var(--accent); }
.sw-peak { background: var(--bad); opacity: 0.7; }
.sw-ram { background: var(--sky); }
.hm-wrap { max-height: none; }
.hm th.hm-task, .hm td.hm-task { text-align: left; min-width: 210px; }
.hm td.hm-task code { font-family: var(--mono); font-size: 12.5px; display: block; }
.hm td.hm-task .cat { font-size: 11px; color: var(--faint); font-family: var(--mono); }
.hm th.hm-cfg { text-align: center; max-width: 150px; white-space: normal; }
.hm-model { display: block; font-family: var(--mono); font-size: 10px; word-break: break-all; line-height: 1.3; }
.hm-temp { display: block; color: var(--warn); font-size: 10px; margin-top: 2px; }
.hm td { text-align: center; }
.hm-cell { font-weight: 600; cursor: help; transition: filter 0.15s ease; }
.hm-cell:hover { filter: brightness(1.25); }
.hm-na { color: var(--faint); cursor: help; }
details {
  border: 1px solid var(--border-soft); border-radius: var(--radius);
  background: var(--panel); margin-bottom: 12px; transition: border-color 0.15s ease;
}
details[open] { border-color: var(--border); }
details > summary {
  cursor: pointer; padding: 12px 16px; display: flex; align-items: center; gap: 10px;
  list-style: none; flex-wrap: wrap; transition: background 0.15s ease;
}
details > summary::-webkit-details-marker { display: none; }
details > summary::before { content: '\\25B8'; color: var(--faint); font-family: var(--mono); transition: transform 0.15s ease; }
details[open] > summary::before { transform: rotate(90deg); }
details > summary:hover { background: rgba(255,255,255,0.02); }
#fails { margin-top: 14px; }
#fails > summary, .cfg-block > summary { font-family: var(--mono); font-size: 13px; }
.cards { padding: 6px 16px 16px; display: grid; gap: 12px; }
.card { border: 1px solid var(--border-soft); border-radius: 8px; padding: 12px 14px; background: var(--panel-2); }
.card-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 8px; }
.card-head code { font-family: var(--mono); font-size: 12px; }
.score-pill {
  font-family: var(--mono); font-size: 11.5px; font-weight: 600;
  border-radius: 999px; padding: 2px 10px; border: 1px solid;
}
.score-pill.good { color: var(--good); border-color: rgba(52,211,153,0.4); }
.score-pill.warn { color: var(--warn); border-color: rgba(251,191,36,0.4); }
.score-pill.bad { color: var(--bad); border-color: rgba(248,113,113,0.45); }
.score-pill.na { color: var(--faint); border-color: var(--border); }
.notes { margin: 0; color: var(--muted); font-size: 13.5px; }
.empty { color: var(--faint); font-size: 13px; margin: 6px 0; }
.stat-chips { display: flex; flex-wrap: wrap; gap: 10px; }
.stat-chip {
  border: 1px solid var(--border-soft); border-radius: 8px; background: var(--panel);
  padding: 10px 14px; display: flex; flex-direction: column; gap: 2px; min-width: 200px;
  transition: border-color 0.15s ease, transform 0.15s ease;
}
.stat-chip:hover { border-color: var(--border); transform: translateY(-1px); }
.stat-val { font-family: var(--mono); font-size: 18px; font-weight: 600; color: var(--accent); font-variant-numeric: tabular-nums; }
.stat-key { font-family: var(--mono); font-size: 11px; color: var(--faint); word-break: break-all; }
.cfg-block[open] > summary { border-bottom: 1px solid var(--border-soft); }
.entry { margin: 0 12px 12px; background: var(--bg-soft); }
.entry .entry-body { padding: 14px 18px 18px; }
.entry > summary > code.e-task { font-family: var(--mono); font-size: 12.5px; color: var(--text); }
.entry > summary .cat { font-family: var(--mono); font-size: 11px; color: var(--faint); }
.entry > summary .sum-right { margin-left: auto; font-family: var(--mono); font-size: 11.5px; color: var(--muted); }
.meta-grid { display: flex; flex-direction: column; gap: 6px; margin-bottom: 12px; }
.meta-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.meta-k {
  font-family: var(--mono); font-size: 10px; letter-spacing: 0.14em; text-transform: uppercase;
  color: var(--faint); min-width: 96px; display: inline-block;
}
.meta-row .mono, .mono { font-family: var(--mono); font-size: 12px; color: var(--muted); }
.tok { font-family: var(--mono); font-size: 11px; border-radius: 4px; padding: 1px 8px; border: 1px solid; margin-left: 4px; }
.tok.ok { color: var(--good); border-color: rgba(52,211,153,0.4); }
.tok.miss { color: var(--bad); border-color: rgba(248,113,113,0.45); }
.chip-ok { color: var(--good); border-color: rgba(52,211,153,0.4); }
.chip-bad { color: var(--bad); border-color: rgba(248,113,113,0.45); }
.chip-off { color: var(--faint); border-color: var(--border); }
.err-box {
  color: var(--bad); font-family: var(--mono); font-size: 12.5px;
  background: rgba(248,113,113,0.07); border: 1px solid rgba(248,113,113,0.3);
  border-radius: 8px; padding: 10px 14px;
}
.judge-notes { margin: 0 0 12px; }
.judge-notes p { margin: 4px 0 0; color: var(--muted); font-size: 13.5px; border-left: 2px solid var(--border); padding-left: 12px; }
pre.answer {
  margin: 0; padding: 16px 18px; background: #0b0f16; border: 1px solid var(--border-soft);
  border-radius: 8px; overflow: auto; max-height: 480px;
  font-family: var(--mono); font-size: 12.5px; line-height: 1.55; color: #cdd6e4;
  white-space: pre-wrap; word-break: break-word;
}
footer.foot { padding: 10px 28px 40px; color: var(--faint); font-family: var(--mono); font-size: 11.5px; }
@media (max-width: 980px) {
  #charts { grid-template-columns: 1fr; }
  .tbl td.cfg { min-width: 220px; }
  h1 { font-size: 24px; }
}
@media (prefers-reduced-motion: reduce) { * { transition: none !important; } }
`;

// ---------------------------------------------------------------------------
// Client renderer: string-concat HTML only (no template literals) so the code
// survives being embedded in the Node-side template below.
// ---------------------------------------------------------------------------

const CLIENT = String.raw`
(function () {
  'use strict';

  var data = JSON.parse(document.getElementById('bench-data').textContent);
  var meta = data.meta || {};
  var summary = data.summary || {};
  var perConfig = summary.perConfig || [];
  var configs = data.configs || [];
  var tasks = data.tasks || [];
  var entries = data.entries || [];
  var inputName = document.body.getAttribute('data-input') || 'results.json';

  var NA = '\u2014';
  var ESC_MAP = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };

  function esc(v) {
    return String(v === null || v === undefined ? '' : v).replace(/[&<>"']/g, function (ch) {
      return ESC_MAP[ch];
    });
  }
  function isNum(v) { return v !== null && v !== undefined && Number.isFinite(Number(v)); }
  function f1(v) { return isNum(v) ? Number(v).toFixed(1) : NA; }
  function f2(v) { return isNum(v) ? Number(v).toFixed(2) : NA; }
  function f4(v) { return isNum(v) ? Number(v).toFixed(4) : NA; }
  function fInt(v) { return isNum(v) ? String(Math.round(Number(v))) : NA; }
  function fPct(v) { return isNum(v) ? Math.round(v * 100) + '%' : NA; }
  function fGiB(bytes) { return isNum(bytes) ? (bytes / 1073741824).toFixed(1) + ' GiB' : NA; }

  // Display-only quantization hint: the bare name 'qwen2.5-coder:3b' hides that
  // it is the Q4_K_M build, while the q8_0 variant is self-describing already.
  function tagQ4(label) {
    var s = String(label === null || label === undefined ? '' : label);
    // Two spellings exist: raw model name uses ':' (qwen2.5-coder:3b), the
    // configId slug from config.js uses '_' (qwen2.5-coder_3b). The q8_0
    // variant contains 'q8_0' in either spelling and stays untagged.
    var isQ4 = s.indexOf('qwen2.5-coder:3b') >= 0 || s.indexOf('qwen2.5-coder_3b') >= 0;
    return isQ4 && s.indexOf('q8_0') < 0 ? s + ' (Q4_K_M)' : s;
  }

  function humanDuration(ms) {
    if (!isNum(ms)) return NA;
    var total = Math.round(ms / 1000);
    var h = Math.floor(total / 3600);
    var m = Math.floor((total % 3600) / 60);
    var s = total % 60;
    var parts = [];
    if (h) parts.push(h + ' \u0447');
    if (m) parts.push(m + ' \u043C\u0438\u043D');
    if (s || !parts.length) parts.push(s + ' \u0441');
    return parts.join(' ');
  }

  function fmtDate(iso, withTime) {
    var d = new Date(iso);
    if (isNaN(d.getTime())) return String(iso);
    var opts = { day: '2-digit', month: '2-digit', year: 'numeric' };
    if (withTime) { opts.hour = '2-digit'; opts.minute = '2-digit'; }
    return d.toLocaleString('ru-RU', opts);
  }

  // --- 1: meta chips ---------------------------------------------------------

  function renderChips() {
    var errCount = meta.errorCount || 0;
    var start = meta.startedAt ? new Date(meta.startedAt) : null;
    var finish = meta.finishedAt ? new Date(meta.finishedAt) : null;
    var period =
      fmtDate(meta.startedAt, false) +
      (start ? ' ' + start.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' }) : '') +
      ' \u2192 ' +
      (finish ? finish.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' }) : NA);
    var items = [
      period,
      humanDuration(meta.durationMs),
      entries.length + ' \u043F\u0440\u043E\u0433\u043E\u043D\u043E\u0432 / ' + errCount + ' \u043E\u0448\u0438\u0431\u043E\u043A',
      '\u0441\u0443\u0434\u044C\u044F: ' + (meta.judgeModel || NA),
      'Node ' + (meta.nodeVersion || NA),
      'stream: ' + (meta.stream ? '\u0432\u043A\u043B' : '\u0432\u044B\u043A\u043B'),
    ];
    document.getElementById('chips').innerHTML = items.map(function (t, i) {
      var cls = 'chip';
      if (i === 2) cls += errCount ? ' err' : ' ok';
      return '<span class="' + cls + '">' + esc(t) + '</span>';
    }).join('');
  }

  // --- 2: verdict -------------------------------------------------------------

  function renderVerdict() {
    var lines = String(summary.verdict || '').split('\n');
    document.getElementById('verdict').innerHTML = lines.map(function (ln, i) {
      var cls = i === 0 ? 'v-label' : 'v-line';
      return '<div class="' + cls + '">' + esc(ln) + '</div>';
    }).join('');
  }

  // --- 3: comparison table -----------------------------------------------------

  var COLS = [
    { key: 'configId', label: '\u041A\u043E\u043D\u0444\u0438\u0433', type: 'config' },
    { key: 'avgOverallScore', label: 'Score', fmt: f2, best: 'max', title: '\u0421\u0440\u0435\u0434\u043D\u0438\u0439 overallScore \u0441\u0443\u0434\u044C\u0438 (1\u201310)' },
    { key: 'avgTokensPerSec', label: 'tok/s', fmt: f2, best: 'max', title: '\u0421\u043A\u043E\u0440\u043E\u0441\u0442\u044C \u0433\u0435\u043D\u0435\u0440\u0430\u0446\u0438\u0438' },
    { key: 'avgTTFT', label: 'TTFT ms', fmt: fInt, best: 'min', title: '\u0412\u0440\u0435\u043C\u044F \u0434\u043E \u043F\u0435\u0440\u0432\u043E\u0433\u043E \u0442\u043E\u043A\u0435\u043D\u0430' },
    { key: 'avgTotalMs', label: 'totalMs', fmt: fInt, best: 'min', title: '\u0421\u0440\u0435\u0434\u043D\u0435\u0435 \u043F\u043E\u043B\u043D\u043E\u0435 \u0432\u0440\u0435\u043C\u044F \u043E\u0442\u0432\u0435\u0442\u0430' },
    { key: 'avgCompletionTokens', label: '\u0422\u043E\u043A\u0435\u043D\u044B', fmt: fInt, best: 'min', title: '\u0421\u0440\u0435\u0434\u043D\u0435\u0435 \u0447\u0438\u0441\u043B\u043E completion-\u0442\u043E\u043A\u0435\u043D\u043E\u0432' },
    { key: 'efficiencyScore', label: '\u042D\u0444\u0444.', fmt: f4, best: 'max', title: '\u041A\u0430\u0447\u0435\u0441\u0442\u0432\u043E \u0437\u0430 \u0442\u043E\u043A\u0435\u043D: overallScore / completionTokens' },
    { key: 'deterministicPassRate', label: '\u0414\u0435\u0442%', fmt: fPct, best: 'max', title: 'mustContain \u043F\u0440\u043E\u0432\u0435\u0440\u043A\u0438' },
    { key: 'avgCpuPct', label: 'CPU avg%', fmt: f1, best: 'min', title: '\u0421\u0440\u0435\u0434\u043D\u044F\u044F \u0437\u0430\u0433\u0440\u0443\u0437\u043A\u0430 CPU (docker, ollama)' },
    { key: 'peakCpuPct', label: 'CPU peak%', fmt: f1, best: 'min', title: '\u041F\u0438\u043A\u043E\u0432\u0430\u044F \u0437\u0430\u0433\u0440\u0443\u0437\u043A\u0430 CPU (docker, ollama)' },
    {
      key: 'peakMemUsedHuman', label: 'RAM peak', bestKey: 'peakMemUsedBytes', best: 'min',
      fmt: function (v, row) { return row.peakMemUsedHuman || NA; },
      title: '\u041F\u0438\u043A RAM \u043A\u043E\u043D\u0442\u0435\u0439\u043D\u0435\u0440\u0430 (docker, ollama)',
    },
  ];

  var bestMap = {};
  COLS.forEach(function (c) {
    if (!c.best) return;
    var vals = perConfig.map(function (r) { return r[c.bestKey || c.key]; }).filter(isNum).map(Number);
    if (!vals.length) return;
    bestMap[c.label] = c.best === 'max' ? Math.max.apply(null, vals) : Math.min.apply(null, vals);
  });

  function renderCell(c, row) {
    if (c.type === 'config') {
      return '<td class="cfg"><div class="cfg-line">' +
        '<span class="badge b-' + esc(row.providerType) + '">' + esc(row.providerType) + '</span>' +
        '<span class="chip chip-t">t' + esc(String(row.temperature)) + '</span></div>' +
        '<code class="cfg-id">' + esc(tagQ4(row.configId)) + '</code></td>';
    }
    var num = row[c.bestKey || c.key];
    var cls = [];
    var title = '';
    if (!isNum(num)) {
      if (['avgCpuPct', 'peakCpuPct', 'peakMemUsedHuman'].indexOf(c.key) >= 0) {
        title = ' title="' + esc(row.resourcesNote || '\u043D\u0435\u0442 \u0434\u0430\u043D\u043D\u044B\u0445') + '"';
      }
    } else if (c.best && Number(num) === bestMap[c.label]) {
      cls.push('best');
    }
    return '<td' + title + (cls.length ? ' class="' + cls.join(' ') + '"' : '') + '>' +
      c.fmt(row[c.key], row) + '</td>';
  }

  function renderTable() {
    var head = '<thead><tr>' + COLS.map(function (c) {
      return '<th' + (c.title ? ' title="' + esc(c.title) + '"' : '') + '>' + esc(c.label) + '</th>';
    }).join('') + '</tr></thead>';
    var body = '<tbody>' + perConfig.map(function (row) {
      return '<tr>' + COLS.map(function (c) { return renderCell(c, row); }).join('') + '</tr>';
    }).join('') + '</tbody>';
    document.getElementById('tbl').innerHTML = head + body;
  }

  // --- 4: hand-rolled SVG bar charts ---------------------------------------------

  function svgBarChart(rows, o) {
    o = o || {};
    var width = o.width || 560;
    var labelW = o.labelW || 218;
    var rowH = o.rowH || 26;
    var valueW = o.valueW || 96;
    var barW = width - labelW - valueW;
    var y = 4;
    var out = [];
    for (var i = 0; i < rows.length; i++) {
      var r = rows[i];
      var frac = Math.max(0, Math.min(1, r.frac || 0));
      var w = frac * barW;
      var cy = y + rowH / 2 + 3.5;
      if (r.label) out.push('<text x="4" y="' + cy + '" class="svg-label">' + esc(r.label) + '</text>');
      if (frac > 0) {
        out.push('<rect x="' + labelW + '" y="' + (y + 5) + '" width="' + Math.max(w, 3) +
          '" height="' + (rowH - 10) + '" rx="3" class="bar ' + (r.cls || 'bar-acc') + '"/>');
      }
      out.push('<text x="' + (labelW + w + 8) + '" y="' + cy + '" class="svg-val">' + esc(r.display) + '</text>');
      y += rowH;
    }
    return '<svg viewBox="0 0 ' + width + ' ' + (y + 4) + '" class="chart" role="img">' + out.join('') + '</svg>';
  }

  function scoreCls(v) {
    if (!isNum(v)) return 'bar-na';
    return v >= 8 ? 'bar-good' : v >= 6 ? 'bar-warn' : 'bar-bad';
  }

  function chartCard(title, sub, svg) {
    return '<div class="panel chart-card"><div class="chart-head"><h3>' + esc(title) + '</h3>' +
      (sub ? '<span class="chart-sub">' + esc(sub) + '</span>' : '') + '</div>' + svg + '</div>';
  }

  function maxOf(rows, key) {
    var vals = rows.map(function (r) { return r[key]; }).filter(isNum).map(Number);
    return vals.length ? Math.max.apply(null, vals) : 1;
  }

  function resourcesChart() {
    var maxCpu = maxOf(perConfig, 'peakCpuPct');
    var maxRam = maxOf(perConfig, 'peakMemUsedBytes');
    var rows = [];
    for (var i = 0; i < perConfig.length; i++) {
      var r = perConfig[i];
      if (r.resourcesAvailable && (isNum(r.avgCpuPct) || isNum(r.peakMemUsedBytes))) {
        rows.push({ label: tagQ4(r.configId), frac: (Number(r.avgCpuPct) || 0) / maxCpu, display: f1(r.avgCpuPct) + ' %', cls: 'bar-avg' });
        rows.push({ frac: (Number(r.peakCpuPct) || 0) / maxCpu, display: f1(r.peakCpuPct) + ' %', cls: 'bar-peak' });
        rows.push({ frac: (Number(r.peakMemUsedBytes) || 0) / maxRam, display: r.peakMemUsedHuman || fGiB(r.peakMemUsedBytes), cls: 'bar-ram' });
      } else {
        rows.push({ label: tagQ4(r.configId), frac: 0, display: 'n/a (remote)', cls: 'bar-na' });
      }
    }
    var legend = '<div class="legend">' +
      '<span class="lg"><i class="sw sw-avg"></i>CPU avg</span>' +
      '<span class="lg"><i class="sw sw-peak"></i>CPU peak</span>' +
      '<span class="lg"><i class="sw sw-ram"></i>RAM peak</span></div>';
    return '<div class="panel chart-card"><div class="chart-head"><h3>\u0420\u0435\u0441\u0443\u0440\u0441\u044B \u043B\u043E\u043A\u0430\u043B\u044C\u043D\u044B\u0435</h3>' +
      '<span class="chart-sub">docker stats, \u043A\u043E\u043D\u0442\u0435\u0439\u043D\u0435\u0440 ollama</span></div>' +
      legend + svgBarChart(rows, { rowH: 22, labelW: 218, valueW: 110 }) + '</div>';
  }

  function renderCharts() {
    var maxTps = maxOf(perConfig, 'avgTokensPerSec');
    var maxMs = maxOf(perConfig, 'avgTotalMs');
    var maxEff = maxOf(perConfig, 'efficiencyScore');

    var quality = perConfig.map(function (r) {
      return { label: tagQ4(r.configId), frac: (Number(r.avgOverallScore) || 0) / 10, display: f2(r.avgOverallScore), cls: scoreCls(r.avgOverallScore) };
    });
    var speed = perConfig.map(function (r) {
      var v = Number(r.avgTokensPerSec) || 0;
      return { label: tagQ4(r.configId), frac: v > 0 ? Math.sqrt(v / maxTps) : 0, display: f2(r.avgTokensPerSec) + ' tok/s', cls: 'bar-acc' };
    });
    var time = perConfig.map(function (r) {
      var v = Number(r.avgTotalMs) || 0;
      return { label: tagQ4(r.configId), frac: v / maxMs, display: fInt(r.avgTotalMs) + ' ms', cls: 'bar-time' };
    });
    var eff = perConfig.map(function (r) {
      var v = Number(r.efficiencyScore) || 0;
      return { label: tagQ4(r.configId), frac: v / maxEff, display: f4(r.efficiencyScore), cls: 'bar-eff' };
    });

    document.getElementById('charts').innerHTML =
      chartCard('\u041A\u0430\u0447\u0435\u0441\u0442\u0432\u043E', 'avgOverallScore, 0\u201310', svgBarChart(quality)) +
      chartCard('\u0421\u043A\u043E\u0440\u043E\u0441\u0442\u044C', '\u221A-\u0448\u043A\u0430\u043B\u0430, \u0437\u043D\u0430\u0447\u0435\u043D\u0438\u044F \u0440\u0435\u0430\u043B\u044C\u043D\u044B\u0435', svgBarChart(speed)) +
      chartCard('\u0421\u0440\u0435\u0434\u043D\u0435\u0435 \u0432\u0440\u0435\u043C\u044F \u043E\u0442\u0432\u0435\u0442\u0430', 'avgTotalMs \u2014 \u043C\u0435\u043D\u044C\u0448\u0435 \u043B\u0443\u0447\u0448\u0435', svgBarChart(time)) +
      chartCard('\u042D\u0444\u0444\u0435\u043A\u0442\u0438\u0432\u043D\u043E\u0441\u0442\u044C', '\u043A\u0430\u0447\u0435\u0441\u0442\u0432\u043E \u0437\u0430 \u0442\u043E\u043A\u0435\u043D', svgBarChart(eff)) +
      resourcesChart();
  }

  // --- 5: heatmap task x config --------------------------------------------------

  function heatmapCell(taskId, configId) {
    var e = null;
    for (var i = 0; i < entries.length; i++) {
      if (entries[i].taskId === taskId && entries[i].configId === configId) { e = entries[i]; break; }
    }
    if (!e || e.error || !e.judge || !isNum(e.judge.overallScore)) {
      var why = e && e.error ? String(e.error) : '\u043D\u0435\u0442 \u0434\u0430\u043D\u043D\u044B\u0445';
      return '<td class="hm-na" title="' + esc(why) + '">\u2014</td>';
    }
    var sc = Number(e.judge.overallScore);
    var hue = Math.max(0, Math.min(1, (sc - 1) / 9)) * 120;
    var style = 'background:hsla(' + hue.toFixed(0) + ',70%,45%,0.22);color:hsl(' + hue.toFixed(0) + ',85%,64%)';
    var note = e.judge.judgeNotes || '';
    var title = 'score ' + sc + (note ? '\n' + note : '');
    return '<td class="hm-cell" style="' + style + '" title="' + esc(title) + '">' + esc(f2(sc)) + '</td>';
  }

  function renderHeatmap() {
    var head = '<thead><tr><th class="hm-task">\u0417\u0430\u0434\u0430\u0447\u0430</th>' + configs.map(function (c) {
      return '<th class="hm-cfg"><span class="hm-model">' + esc(tagQ4(c.model)) + '</span>' +
        '<span class="hm-temp">t' + esc(String(c.temperature)) + '</span></th>';
    }).join('') + '</tr></thead>';
    var body = '<tbody>' + tasks.map(function (t) {
      var cells = configs.map(function (c) { return heatmapCell(t.id, c.id); }).join('');
      return '<tr><td class="hm-task"><code>' + esc(t.id) + '</code><span class="cat">' + esc(t.category) + '</span></td>' + cells + '</tr>';
    }).join('') + '</tbody>';
    document.getElementById('heatmap').innerHTML = head + body;
    renderFails();
  }

  function renderFails() {
    var bad = entries.filter(function (e) {
      return e.judge && isNum(e.judge.overallScore) && Number(e.judge.overallScore) <= 4;
    }).sort(function (a, b) { return a.judge.overallScore - b.judge.overallScore; });
    document.querySelector('#fails summary').textContent =
      '\u041F\u0440\u043E\u0432\u0430\u043B\u044B (score \u2264 4) \u2014 ' + bad.length;
    var host = document.getElementById('fails-cards');
    if (!bad.length) {
      host.innerHTML = '<p class="empty">\u041D\u0435\u0442 \u043F\u0440\u043E\u0432\u0430\u043B\u043E\u0432: \u0432\u0441\u0435 \u043E\u0446\u0435\u043D\u043A\u0438 \u0432\u044B\u0448\u0435 4.</p>';
      return;
    }
    host.innerHTML = bad.map(function (e) {
      return '<div class="card fail-card"><div class="card-head">' +
        '<code>' + esc(tagQ4(e.configId)) + '</code>' +
        '<span class="chip chip-t">' + esc(e.taskId) + '</span>' +
        '<span class="score-pill bad">' + f2(e.judge.overallScore) + '</span></div>' +
        '<p class="notes">' + esc(e.judge.judgeNotes || '') + '</p></div>';
    }).join('');
  }

  // --- 6: conciseness -------------------------------------------------------------

  function renderConciseness() {
    var perModel = (summary.conciseness || {}).avgTokensPerModel || {};
    var keys = Object.keys(perModel).sort(function (a, b) { return perModel[a] - perModel[b]; });
    document.getElementById('concise').innerHTML = keys.length
      ? keys.map(function (k) {
        return '<div class="stat-chip"><span class="stat-val">' + esc(f1(perModel[k])) +
          '<span class="mono"> \u0442\u043E\u043A</span></span><span class="stat-key">' + esc(tagQ4(k)) + '</span></div>';
      }).join('')
      : '<p class="empty">\u041D\u0435\u0442 \u0434\u0430\u043D\u043D\u044B\u0445.</p>';
  }

  // --- determinism (only present with repeats > 1) --------------------------------

  function renderDeterminism() {
    var det = summary.determinism;
    if (!det) return;
    document.getElementById('sec-det').hidden = false;
    var el = document.getElementById('det-tbl');
    var first = det[Object.keys(det)[0]] || {};
    var tKeys = Object.keys(first.temperatures || {});
    var thead = '<thead><tr><th>\u041C\u043E\u0434\u0435\u043B\u044C</th>' +
      tKeys.map(function (tk) { return '<th>t=' + esc(tk) + ' (score, \u03C3)</th>'; }).join('') +
      '<th>\u0394 scoreStdDev</th><th>\u0394 latencyStdDev</th><th>\u0441\u0442\u0430\u0431\u0438\u043B\u044C\u043D\u0435\u0435</th></tr></thead>';
    var body = '<tbody>' + Object.keys(det).map(function (model) {
      var d = det[model] || {};
      var temps = d.temperatures || {};
      var tCells = tKeys.map(function (tk) {
        var t = temps[tk] || {};
        return '<td>' + f2(t.avgOverallScore) + ' <span class="muted">(\u03C3 ' + f2(t.scoreStdDev) + ')</span></td>';
      }).join('');
      return '<tr><td class="cfg"><code class="cfg-id">' + esc(tagQ4(model)) + '</code></td>' + tCells +
        '<td>' + f2(d.scoreStdDevDelta) + '</td><td>' + f1(d.latencyStdDevDelta) + '</td>' +
        '<td>' + esc(d.stabilityWinner || NA) + '</td></tr>';
    }).join('') + '</tbody>';
    el.innerHTML = thead + body;
  }

  // --- 7: per-config answers -------------------------------------------------------

  function pillCls(v) {
    if (!isNum(v)) return 'na';
    return Number(v) >= 8 ? 'good' : Number(v) >= 6 ? 'warn' : 'bad';
  }

  function detChips(e) {
    if (!e.deterministic) return '<span class="chip chip-off">\u0431\u0435\u0437 \u043F\u0440\u043E\u0432\u0435\u0440\u043E\u043A</span>';
    var pass = e.deterministic.deterministicPass;
    var status = pass === null || pass === undefined
      ? '<span class="chip chip-off">\u0431\u0435\u0437 \u043F\u0440\u043E\u0432\u0435\u0440\u043E\u043A</span>'
      : '<span class="chip ' + (pass ? 'chip-ok' : 'chip-bad') + '">' +
        (pass ? '\u043F\u0440\u043E\u0432\u0435\u0440\u043A\u0438 \u043F\u0440\u043E\u0439\u0434\u0435\u043D\u044B' : '\u0435\u0441\u0442\u044C \u043D\u0435\u0441\u043E\u0432\u043F\u0430\u0434\u0435\u043D\u0438\u044F') + '</span>';
    var toks = Array.isArray(e.deterministic.tokens)
      ? e.deterministic.tokens.map(function (t) {
        return '<span class="tok ' + (t.found ? 'ok' : 'miss') + '">' + esc(t.token) +
          (t.found ? ' \u00B7 ok' : ' \u00B7 miss') + '</span>';
      }).join('')
      : '';
    return status + toks;
  }

  function entryDetails(e) {
    var score = e.judge ? e.judge.overallScore : null;
    var pill = e.error
      ? '<span class="score-pill bad">\u043E\u0448\u0438\u0431\u043A\u0430</span>'
      : '<span class="score-pill ' + pillCls(score) + '">' + f2(score) + '</span>';
    return '<details class="entry"><summary>' +
      '<code class="e-task">' + esc(e.taskId) + '</code>' +
      '<span class="cat">' + esc(e.category) + '</span>' + pill +
      '<span class="sum-right mono">' + fInt(e.usage && e.usage.completionTokens) + ' \u0442\u043E\u043A \u00B7 ' +
      f2(e.speed && e.speed.tokensPerSec) + ' tok/s \u00B7 ' +
      fInt(e.timing && e.timing.totalMs) + ' ms</span></summary>' +
      '<div class="entry-body">' +
      '<div class="meta-grid">' +
      '<div class="meta-row"><span class="meta-k">\u0442\u043E\u043A\u0435\u043D\u044B</span><span class="mono">prompt ' +
      fInt(e.usage && e.usage.promptTokens) + ' \u00B7 completion ' + fInt(e.usage && e.usage.completionTokens) +
      ' \u00B7 total ' + fInt(e.usage && e.usage.totalTokens) + '</span></div>' +
      '<div class="meta-row"><span class="meta-k">\u0432\u0440\u0435\u043C\u044F</span><span class="mono">TTFT ' +
      fInt(e.timing && e.timing.firstTokenMs) + ' ms \u00B7 total ' + fInt(e.timing && e.timing.totalMs) + ' ms</span></div>' +
      '<div class="meta-row"><span class="meta-k">mustContain</span>' + detChips(e) + '</div>' +
      '</div>' +
      (e.error ? '<p class="err-box">' + esc(e.error) + '</p>' : '') +
      (e.judge && e.judge.judgeNotes
        ? '<div class="judge-notes"><span class="meta-k">\u0437\u0430\u043C\u0435\u0442\u043A\u0438 \u0441\u0443\u0434\u044C\u0438</span><p>' +
          esc(e.judge.judgeNotes) + '</p></div>'
        : '') +
      '<pre class="answer">' + esc(e.answer || '') + '</pre>' +
      '</div></details>';
  }

  function renderAnswers() {
    var byConfig = {};
    entries.forEach(function (e) {
      if (!byConfig[e.configId]) byConfig[e.configId] = [];
      byConfig[e.configId].push(e);
    });
    var order = {};
    tasks.forEach(function (t, i) { order[t.id] = i; });
    document.getElementById('answers').innerHTML = perConfig.map(function (c) {
      var es = (byConfig[c.configId] || []).slice().sort(function (a, b) {
        return (order[a.taskId] || 0) - (order[b.taskId] || 0);
      });
      return '<details class="cfg-block"><summary>' +
        '<span class="badge b-' + esc(c.providerType) + '">' + esc(c.providerType) + '</span>' +
        '<code class="cfg-id">' + esc(tagQ4(c.configId)) + '</code>' +
        '<span class="chip chip-t">t' + esc(String(c.temperature)) + '</span>' +
        '<span class="sum-right mono">score <b>' + f2(c.avgOverallScore) + '</b> \u00B7 ' +
        c.successfulRuns + '/' + c.runs + ' \u043E\u043A</span></summary>' +
        (es.length ? es.map(entryDetails).join('') : '<p class="empty">\u041D\u0435\u0442 \u043F\u0440\u043E\u0433\u043E\u043D\u043E\u0432.</p>') +
        '</details>';
    }).join('');
  }

  function renderFoot() {
    document.getElementById('foot').innerHTML =
      '<span>\u0421\u0433\u0435\u043D\u0435\u0440\u0438\u0440\u043E\u0432\u0430\u043D\u043E \u043B\u043E\u043A\u0430\u043B\u044C\u043D\u043E \u0438\u0437 ' + esc(inputName) +
      ' \u00B7 \u0437\u0430\u043F\u0438\u0441\u0435\u0439: ' + entries.length + ' \u00B7 \u0441\u0443\u0434\u044C\u044F: ' +
      esc(meta.judgeModel || NA) + ' \u00B7 \u0432\u043D\u0435\u0448\u043D\u0438\u0435 \u0437\u0430\u043F\u0440\u043E\u0441\u044B \u043E\u0442\u0441\u0443\u0442\u0441\u0442\u0432\u0443\u044E\u0442</span>';
  }

  renderChips();
  renderVerdict();
  renderTable();
  renderCharts();
  renderHeatmap();
  renderConciseness();
  renderDeterminism();
  renderAnswers();
  renderFoot();
})();
`;

// ---------------------------------------------------------------------------
// Page shell: the JSON payload is embedded in full (entries + answers included).
// All `<` in the payload are escaped as \u003c so a literal closing script tag
// inside answers/notes can never terminate the script tag prematurely.
// ---------------------------------------------------------------------------

function escapeAttr(s) {
  return String(s).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');
}

function renderPage(data, inputName) {
  const payload = JSON.stringify(data).replace(/</g, '\\u003c');
  return `<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>LLM Benchmark — отчёт</title>
<style>${CSS}</style>
</head>
<body data-input="${escapeAttr(inputName)}">
<div class="glow" aria-hidden="true"></div>
<header class="top"><div class="wrap">
<div class="kicker">llm-benchmark · отчёт по прогону</div>
<h1>LLM Benchmark<span class="accent"> · дашборд результатов</span></h1>
<div class="chips" id="chips"></div>
</div></header>
<main class="wrap">
<section>
  <div class="sec-head"><span class="sec-no">01</span><h2>Вердикт</h2></div>
  <div class="panel verdict" id="verdict"></div>
</section>
<section>
  <div class="sec-head"><span class="sec-no">02</span><h2>Сравнение конфигураций</h2><p class="sec-sub">зелёное — лучшее значение колонки</p></div>
  <div class="panel tbl-wrap"><table class="tbl" id="tbl"></table></div>
</section>
<section>
  <div class="sec-head"><span class="sec-no">03</span><h2>Диаграммы</h2></div>
  <div id="charts"></div>
</section>
<section>
  <div class="sec-head"><span class="sec-no">04</span><h2>Теплокарта · задача × конфиг</h2><p class="sec-sub">ячейка = judge.overallScore (1–10), серое — нет данных, подсказка = judgeNotes</p></div>
  <div class="panel tbl-wrap hm-wrap"><table class="tbl hm" id="heatmap"></table></div>
  <details id="fails"><summary>Провалы (score ≤ 4)</summary><div class="cards" id="fails-cards"></div></details>
</section>
<section>
  <div class="sec-head"><span class="sec-no">05</span><h2>Лаконичность</h2><p class="sec-sub">средние completion-токены на модель — меньше значит лаконичнее</p></div>
  <div class="stat-chips" id="concise"></div>
</section>
<section id="sec-det" hidden>
  <div class="sec-head"><span class="sec-no">06</span><h2>Детерминизм</h2><p class="sec-sub">виден только при repeats &gt; 1</p></div>
  <div class="panel tbl-wrap"><table class="tbl" id="det-tbl"></table></div>
</section>
<section>
  <div class="sec-head"><span class="sec-no">07</span><h2>Ответы моделей</h2><p class="sec-sub">полные ответы, заметки судьи, тайминги и mustContain-проверки</p></div>
  <div id="answers"></div>
</section>
</main>
<footer class="foot wrap" id="foot"></footer>
<script type="application/json" id="bench-data">${payload}</script>
<script>
${CLIENT}
</script>
</body>
</html>
`;
}

// ---------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------

function main() {
  try {
    const inputPath = resolveInputPath(process.argv[2]);
    const data = stripSecrets(JSON.parse(readFileSync(inputPath, 'utf8')));
    if (!data.meta || !data.summary || !Array.isArray(data.entries)) {
      throw new Error('unexpected results shape: meta / summary / entries missing');
    }

    const outPath = join(dirname(inputPath), 'report.html');
    const html = renderPage(data, basename(inputPath));
    writeFileSync(outPath, html, 'utf8');

    console.log(`ui-report: ${inputPath} -> ${outPath} (${fmtBytes(Buffer.byteLength(html, 'utf8'))})`);
  } catch (err) {
    console.error('ui-report: ' + (err && err.message ? err.message : err));
    process.exitCode = 1;
  }
}

main();
