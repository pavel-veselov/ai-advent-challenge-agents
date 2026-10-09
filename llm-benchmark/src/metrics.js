// Speed metrics + resource sampling (docker stats for the local ollama container).
// Zero deps: uses child_process spawn for the streaming `docker stats` feed.

import { spawn } from 'node:child_process';

// Decimal KB/MB/GB/TB = 1e3-based; binary KiB/MiB/GiB/TiB = 1024-based.
const SIZE_UNITS = {
  B: 1,
  KB: 1e3,
  MB: 1e6,
  GB: 1e9,
  TB: 1e12,
  KIB: 1024,
  MIB: 1024 ** 2,
  GIB: 1024 ** 3,
  TIB: 1024 ** 4,
};

/** '123.4MiB' | '1.05MB' | '987B' -> bytes (null on failure) */
export function parseSize(str) {
  if (!str) return null;
  const m = String(str).trim().match(/^([\d.]+)\s*([KMGT]?I?B)$/i);
  if (!m) return null;
  const mult = SIZE_UNITS[m[2].toUpperCase()];
  if (!mult) return null;
  return Math.round(parseFloat(m[1]) * mult);
}

/** '12.34%' -> 12.34 (null on failure) */
export function parseCpuPct(s) {
  const m = String(s ?? '').match(/([\d.]+)\s*%/);
  return m ? parseFloat(m[1]) : null;
}

/** Parse one `docker stats --format` row: 'CPUPerc|MemUsage|NetIO|PIDs' */
export function parseStatsRow(row) {
  const parts = String(row).trim().split('|');
  if (parts.length < 4) return null;
  const [cpuPerc, memUsage, netIO, pids] = parts;

  const memParts = String(memUsage).split('/');
  const netParts = String(netIO).split('/');

  return {
    cpuPct: parseCpuPct(cpuPerc),
    memUsed: parseSize(memParts[0]),
    memLimit: parseSize(memParts[1]),
    netIn: parseSize(netParts[0]),
    netOut: parseSize(netParts[1]),
    pids: /^\d+$/.test(String(pids).trim()) ? parseInt(pids, 10) : null,
    ts: Date.now(),
  };
}

/** tokensPerSec = completionTokens / (totalMs / 1000); rounded, or null. */
export function computeTokensPerSec(completionTokens, totalMs) {
  if (!Number.isFinite(completionTokens) || completionTokens <= 0) return null;
  if (!Number.isFinite(totalMs) || totalMs <= 0) return null;
  return Math.round((completionTokens / (totalMs / 1000)) * 100) / 100;
}

function mean(arr) {
  if (!arr.length) return null;
  return arr.reduce((a, b) => a + b, 0) / arr.length;
}

/**
 * Streaming resource sampler for a docker container.
 * start() spawns `docker stats <container> --no-stream=false --format '{{.CPUPerc}}|{{.MemUsage}}|{{.NetIO}}|{{.PIDs}}'`
 * and parses every line the docker ticker emits (~1 s native refresh). Wrap a
 * config's task loop with start()/stop(); stop() kills the child and returns
 * the aggregate. Never throws — docker failures come back as { available:false, note }.
 */
export function createResourceSampler({ container = 'ollama' } = {}) {
  const samples = [];
  let child = null;
  let dockerError = null;
  let leftover = '';

  function onChunk(chunk) {
    leftover += chunk.toString('utf8');
    let idx;
    while ((idx = leftover.indexOf('\n')) >= 0) {
      const line = leftover.slice(0, idx).replace(/\r/g, '').trim();
      leftover = leftover.slice(idx + 1);
      if (!line) continue;
      const row = parseStatsRow(line);
      if (row) samples.push(row);
    }
  }

  return {
    start() {
      try {
        child = spawn(
          'docker',
          [
            'stats',
            container,
            '--no-stream=false',
            '--format',
            '{{.CPUPerc}}|{{.MemUsage}}|{{.NetIO}}|{{.PIDs}}',
          ],
          { stdio: ['ignore', 'pipe', 'pipe'] },
        );
        child.stdout.on('data', onChunk);
        child.stderr.on('data', (d) => {
          dockerError = (dockerError ? dockerError + ' ' : '') + d.toString('utf8').trim();
        });
        child.on('error', (err) => {
          dockerError = `docker spawn failed: ${err.message}`;
        });
      } catch (err) {
        dockerError = `docker spawn failed: ${err.message}`;
        child = null;
      }
    },

    stop() {
      if (child) {
        try {
          child.kill();
        } catch {
          /* already dead */
        }
        child = null;
      }

      if (!samples.length) {
        return {
          available: false,
          note: dockerError
            ? `docker stats unavailable: ${dockerError.slice(0, 200)}`
            : 'no samples collected',
          sampleCount: 0,
          avgCpuPct: null,
          peakCpuPct: null,
          peakMemUsedBytes: null,
          peakMemLimitBytes: null,
          totalNetIOBytes: null,
        };
      }

      const cpus = samples.map((s) => s.cpuPct).filter((v) => v !== null);
      const memUsed = samples.map((s) => s.memUsed).filter((v) => v !== null);
      const memLimit = samples.map((s) => s.memLimit).filter((v) => v !== null);
      const last = samples[samples.length - 1];

      return {
        available: true,
        note: `docker stats streaming (native ~1s refresh), container=${container}`,
        sampleCount: samples.length,
        avgCpuPct: mean(cpus),
        peakCpuPct: cpus.length ? Math.max(...cpus) : null,
        peakMemUsedBytes: memUsed.length ? Math.max(...memUsed) : null,
        peakMemLimitBytes: memLimit.length ? Math.max(...memLimit) : null,
        totalNetIOBytes: last ? (last.netIn ?? 0) + (last.netOut ?? 0) : null,
      };
    },
  };
}

/** Resource record for remote providers (gpustack): not measurable locally. */
export function remoteResources(netRoundTripMs = null) {
  return {
    available: false,
    note: 'remote (server-side), not measurable locally',
    netRoundTripMs,
  };
}
