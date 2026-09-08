# run-backend.ps1 — супервизор backend на Windows.
# Держит `java -jar` «живым»: после любого завершения JVM пауза 1 секунда и повторный запуск.
# Управление стоп/старт — через контрольный сервер на 127.0.0.1:8081 (в отдельном in-process
# runspace, НЕ Start-Job и НЕ HttpListener) + стоп-флаг %TEMP%\opencode\llm-agent.stop:
#   POST /stop   → создаёт флаг и убивает java (перезапуск блокируется до POST /start)
#   POST /start  → снимает флаг (супервизор перезапускает backend)
#   GET  /status → {"stopped":<bool>,"java":<bool>}
#
# Секреты в этом файле НЕ хранятся и НЕ выводятся: значения (LLM_API_KEY, LLM_BASE_URL и т.п.)
# берутся из окружения текущей сессии и автоматически наследуются дочерним JVM-процессом.

param(
    [string]$JavaHome = "C:\Users\60128627\.jdks\corretto-21.0.9",
    [string]$JarPath = (Join-Path $env:TEMP "llm-agent-build\llm-agent-backend\libs\llm-agent-backend-0.0.1-SNAPSHOT.jar")
)

$javaExe = Join-Path $JavaHome "bin\java.exe"
$log = Join-Path $env:TEMP "opencode\llm-agent-backend.log"

if (-not (Test-Path -LiteralPath $javaExe)) {
    throw "java.exe не найден по пути: $javaExe. Передайте -JavaHome с каталогом JDK 21."
}
if (-not (Test-Path -LiteralPath $JarPath)) {
    throw "Jar не найден: $JarPath. Соберите backend: cd backend; gradlew.bat bootJar"
}

# Эти переменные окружения использует приложение (значения не логируем и не задаём здесь —
# только перечисляем имена, чтобы было видно, что именно наследуется в JVM).
$usedEnv = @(
    'LLM_PROVIDER', 'LLM_BASE_URL', 'LLM_API_KEY', 'LLM_MODEL',
    'LLM_TEMPERATURE', 'LLM_TOP_P', 'LLM_TOP_K', 'LLM_MAX_TOKENS',
    'LLM_TIMEOUT_SECONDS', 'AGENT_MAX_ITERATIONS', 'SERVER_PORT',
    'SQLITE_DB_PATH'
)

# --- Управление стоп/старт: флаг-файл + контрольный сервер (127.0.0.1:8081) ---
$flagFile = Join-Path $env:TEMP "opencode\llm-agent.stop"
$flagDir = Split-Path -Parent $flagFile
if (-not (Test-Path -LiteralPath $flagDir)) {
    New-Item -ItemType Directory -Path $flagDir -Force | Out-Null
}

# Свежий супервизор = сервис должен работать: снимаем стоп-флаг, если он остался от прошлого запуска.
if (Test-Path -LiteralPath $flagFile) {
    Remove-Item -LiteralPath $flagFile -Force
}

# Control-server в отдельном in-process runspace (НЕ Start-Job, НЕ HttpListener).
$serverScript = {
    param($flagPath, $logPath)
    $listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, 8081)
    $listener.Start()
    try {
        while ($true) {
            if ($listener.Pending()) {
                $client = $listener.AcceptTcpClient()
                if ($null -eq $client) { continue }
                try {
                    $stream = $client.GetStream()
                    $stream.ReadTimeout = 2000
                    $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::ASCII)
                    $line = $reader.ReadLine()
                    if ($null -ne $line) {
                        $parts = $line.Split(' ')
                        $method = if ($parts.Count -gt 0) { $parts[0] } else { '' }
                        $path = if ($parts.Count -gt 1) { $parts[1] } else { '' }
                        $statusCode = 404
                        $statusText = 'Not Found'
                        $body = '{"error":"not found"}'
                        if ($method -eq 'POST' -and $path -eq '/stop') {
                            New-Item -ItemType File -Path $flagPath -Force | Out-Null
                            Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
                                Where-Object { $_.CommandLine -like ('*llm-agent-b' + 'ackend*.jar*') } |
                                ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
                            $statusCode = 200; $statusText = 'OK'
                            $body = '{"stopped":true}'
                            "[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] [ctrl] POST /stop — стоп-флаг создан, java остановлен" | Add-Content -Path $logPath
                        } elseif ($method -eq 'POST' -and $path -eq '/start') {
                            if (Test-Path -LiteralPath $flagPath) { Remove-Item -LiteralPath $flagPath -Force }
                            $statusCode = 200; $statusText = 'OK'
                            $body = '{"starting":true}'
                            "[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] [ctrl] POST /start — стоп-флаг снят" | Add-Content -Path $logPath
                        } elseif ($method -eq 'GET' -and $path -eq '/status') {
                            $stopped = Test-Path -LiteralPath $flagPath
                            $javaAlive = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
                                Where-Object { $_.CommandLine -like ('*llm-agent-b' + 'ackend*.jar*') }).Count -gt 0
                            $statusCode = 200; $statusText = 'OK'
                            $stoppedStr = if ($stopped) { 'true' } else { 'false' }
                            $javaStr = if ($javaAlive) { 'true' } else { 'false' }
                            $body = '{"stopped":' + $stoppedStr + ',"java":' + $javaStr + '}'
                        }
                        $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($body)
                        $head = "HTTP/1.1 $statusCode $statusText`r`nContent-Type: application/json`r`nContent-Length: $($bodyBytes.Length)`r`nConnection: close`r`n`r`n"
                        $headBytes = [System.Text.Encoding]::ASCII.GetBytes($head)
                        $stream.Write($headBytes, 0, $headBytes.Length)
                        $stream.Write($bodyBytes, 0, $bodyBytes.Length)
                        $stream.Flush()
                    }
                } catch {
                    # ошибка отдельного соединения (например timeout чтения) — сервер не роняем
                } finally {
                    if ($null -ne $client) { $client.Close() }
                }
            } else {
                Start-Sleep -Milliseconds 200
            }
        }
    } finally {
        $listener.Stop()
    }
}
$runspace = [System.Management.Automation.Runspaces.RunspaceFactory]::CreateRunspace()
$runspace.Open()
$ps = [System.Management.Automation.PowerShell]::Create()
$ps.Runspace = $runspace
[void]$ps.AddScript($serverScript).AddArgument($flagFile).AddArgument($log)
$controlHandle = $ps.BeginInvoke()

Write-Host "[run-backend] Супервизор: Java=$javaExe" -ForegroundColor Cyan
Write-Host "[run-backend] Jar: $JarPath" -ForegroundColor Cyan
Write-Host "[run-backend] Пробрасываемые env-переменные (без значений): $($usedEnv -join ', ')" -ForegroundColor DarkGray
Write-Host "[run-backend] Контроль: 127.0.0.1:8081 (POST /stop, POST /start, GET /status), стоп-флаг $flagFile" -ForegroundColor Cyan

while ($true) {
    Write-Host "[run-backend] $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') Запуск backend..." -ForegroundColor Green
    & $javaExe -jar $JarPath
    $code = $LASTEXITCODE
    Write-Host "[run-backend] $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') Процесс завершён (код $code). Перезапуск через 1 c..." -ForegroundColor Yellow
    Start-Sleep -Seconds 1
    # Пока стоит стоп-флаг — не перезапускаем (ждём POST /start).
    while (Test-Path -LiteralPath $flagFile) {
        Start-Sleep -Seconds 1
    }
}
