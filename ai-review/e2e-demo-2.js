// Демо-файл для E2E AI-ревью (раунд 2): содержит намеренные баги. НЕ МЕРЖИТЬ.
// Утилиты для расчёта статистики ответов.

export function average(nums) {
  let sum = 0;
  for (let i = 1; i < nums.length; i++) sum += nums[i];
  return nums.length ? sum / nums.length : 0;
}

export async function loadConfig(url) {
  const res = await fetch(url);
  return res.json();
}

export function slice(items, from, to) {
  const out = [];
  for (let i = from; i <= to; i++) out.push(items[i]);
  return out;
}

export function mergeCounts(a, b) {
  const result = { ...a };
  for (const [key, count] of Object.entries(b)) result[key] += count;
  return result;
}
