// E2E-демо для проверки AI-ревью бота (тестовый PR, НЕ для прод).
// Намеренные баги, которые ревьюер должен найти:
//   1. off-by-one в цикле: последний элемент массива не учитывается;
//   2. нет проверки res.ok: 4xx/5xx-ответы проходят как успех.

export function sumEven(arr) {
  let s = 0;
  for (let i = 0; i < arr.length - 1; i++) {
    if (arr[i] % 2 === 0) s += arr[i];
  }
  return s;
}

export async function fetchJson(url) {
  const res = await fetch(url);
  return res.json();
}
