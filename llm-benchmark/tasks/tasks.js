// Fixed benchmark dataset: 6 Russian coding tasks, one per category.
// Each task: { id, category, prompt, mustContain? }
// mustContain tokens are checked case-insensitively against the model's answer.

export const TASKS = [
  {
    id: 'codegen-bfs',
    category: 'codegen',
    prompt:
      'Напиши функцию на Python, которая обходит бинарное дерево в ширину (iteratively, без рекурсии) и возвращает список значений по уровням. Дерево задано узлами вида class Node: def __init__(self, value, left=None, right=None). Опиши, как работает алгоритм, и укажи сложность.',
    mustContain: ['popleft'],
  },
  {
    id: 'bugfix-loop-sum',
    category: 'bugfix',
    prompt:
      'В этом коде есть баг:\n\n'
      + 'function sumArray(arr) {\n'
      + '  let sum = 0;\n'
      + '  for (let i = 0; i <= arr.length; i++) {\n'
      + '    sum += arr[i];\n'
      + '  }\n'
      + '  return sum;\n'
      + '}\n'
      + '// Ожидается: sumArray([1, 2, 3]) === 6, фактически возвращается NaN\n\n'
      + 'Покажи исправленный код и объясни причину бага.',
    mustContain: ['NaN'],
  },
  {
    id: 'explain-decorator',
    category: 'explanation',
    prompt:
      'Объясни построчно, что делает этот Python-код (декоратор), и приведи пример использования:\n\n'
      + 'def retry(times=3):\n'
      + '    def decorator(func):\n'
      + '        def wrapper(*args, **kwargs):\n'
      + '            last_error = None\n'
      + '            for attempt in range(times):\n'
      + '                try:\n'
      + '                    return func(*args, **kwargs)\n'
      + '                except Exception as e:\n'
      + '                    last_error = e\n'
      + '            raise last_error\n'
      + '        return wrapper\n'
      + '    return decorator\n',
    mustContain: [],
  },
  {
    id: 'sql-top-orders',
    category: 'sql',
    prompt:
      'Напиши SQL-запрос, выбирающий топ-3 пользователей по количеству заказов за последний месяц. Схема: users(id, name), orders(id, user_id, created_at). Кратко поясни запрос.',
    mustContain: ['ORDER BY', 'LIMIT 3'],
  },
  {
    id: 'refactor-callbacks',
    category: 'refactor',
    prompt:
      'Отрефактори этот callback-код в async/await c обработкой ошибок, сохранив порядок вызовов:\n\n'
      + 'getUser(id, (err, user) => {\n'
      + '  if (err) return console.error(err);\n'
      + '  getOrders(user, (err, orders) => {\n'
      + '    if (err) return console.error(err);\n'
      + '    getDetails(orders[0], (err, details) => {\n'
      + '      if (err) return console.error(err);\n'
      + '      console.log(details);\n'
      + '    });\n'
      + '  });\n'
      + '});\n',
    mustContain: ['async', 'await'],
  },
  {
    id: 'tricky-hoisting',
    category: 'tricky-output',
    prompt:
      'Что выведет этот код и почему? Объясни поведение step by step:\n\n'
      + 'console.log(typeof a);\n'
      + 'var a = 5;\n'
      + '\n'
      + 'function f() {\n'
      + '  console.log(a);\n'
      + '  var a = 10;\n'
      + '  console.log(a);\n'
      + '}\n'
      + '\n'
      + 'f();\n',
    mustContain: ['undefined'],
  },
];
