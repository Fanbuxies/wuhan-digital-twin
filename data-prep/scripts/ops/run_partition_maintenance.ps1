# t_device_telemetry 分区维护的每日调度入口（由 Windows 计划任务调用）
#
# 只做「补建未来分区」，不做「DROP 过期分区」：
# 自动删表属于无人确认的 DDL，与项目红线冲突，保留期清理仍由人工执行
# data-prep/partition_maintenance.sql 的第二个 DO 块。
# 本脚本因此不直接跑那个 .sql 文件，而是内联与其第一个 DO 块等价的建区逻辑。
#
# 本文件必须存为 UTF-8 with BOM：Windows PowerShell 5.1 在 gb2312 控制台下
# 读无 BOM 的 UTF-8 会把中文注释解码成乱码，进而破坏后续行的语法解析。
#
# 退出码：0 成功，1 失败（计划任务的「上次运行结果」据此也非 0，与日志双重留痕）

$ErrorActionPreference = 'Stop'

# 容器名与库参数，与 docker-compose.yml 保持一致
$ContainerName = 'twin-pg'
$DbUser = 'twin'
$DbName = 'twin'

# 预建天数：今天 + FUTURE_DAYS 天。取 14 是留出两周容错窗口，
# 脚本连漏两周也不会有新数据落回 default 分区
$FutureDays = 14

# 探活重试：开机后 Docker Desktop 冷启动可能需要数十秒，最多等 5 分钟
$ProbeMaxAttempts = 10
$ProbeIntervalSeconds = 30

# 路径计算：脚本现在在 data-prep/scripts/ops/，日志在 data-prep/logs/
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$dataPrepRoot = Split-Path -Parent (Split-Path -Parent $scriptDir)
$logDir = Join-Path $dataPrepRoot 'logs'
if (-not (Test-Path $logDir)) {
    New-Item -ItemType Directory -Path $logDir | Out-Null
}
$logFile = Join-Path $logDir ("partition_maintenance_{0}.log" -f (Get-Date -Format 'yyyyMMdd'))

function Write-Log {
    param([string]$Message)
    $line = "[{0}] {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Message
    # 追加 UTF8，日志按天一个文件，便于排查某天是否漏跑
    Add-Content -Path $logFile -Value $line -Encoding UTF8
}

# 把 SQL 从 stdin 喂给容器内的 psql，返回输出行数组；退出码留在 $LASTEXITCODE
#
# 两处细节：
# 1) CREATE TABLE IF NOT EXISTS 命中已有分区时 psql 会往 stderr 打 NOTICE，
#    而 $ErrorActionPreference='Stop' 下原生命令的 stderr 会被当成终止性错误。
#    这属于幂等执行的正常输出而非故障，故用 client_min_messages=warning 从源头压掉，
#    真正的报错（ERROR 级）仍会照常出现并被 ON_ERROR_STOP 捕获。
# 2) 调用点包 try/catch 不够，必须先把 stderr 合流再交给管道，
#    所以统一在此函数内用 2>&1，调用方只看返回值与 $LASTEXITCODE。
function Invoke-Psql {
    param([string]$Sql, [switch]$StopOnError)
    # 不用 $args 命名：它是 PowerShell 自动变量，赋值会与函数入参机制冲突
    $psqlArgs = @('-U', $DbUser, '-d', $DbName, '-Atq', '-c', 'SET client_min_messages TO warning;')
    if ($StopOnError) {
        $psqlArgs = @('-v', 'ON_ERROR_STOP=1') + $psqlArgs
    }
    # -f - 从 stdin 读正文，与前面的 -c 共存时 -c 先执行，正好用来设会话级消息级别
    return $Sql | & docker exec -i -e PGPASSWORD $ContainerName psql @psqlArgs -f - 2>&1
}

Write-Log '=== 分区维护开始（仅补建，不清理）==='

try {
    # 密码现取现用，只经 docker 的环境变量通道传给容器，不进命令行、不进日志
    $pgPassword = [Environment]::GetEnvironmentVariable('PGPASSWORD', 'User')
    if ([string]::IsNullOrEmpty($pgPassword)) {
        Write-Log 'RESULT=FAIL 原因：User 作用域环境变量 PGPASSWORD 为空'
        exit 1
    }
    $env:PGPASSWORD = $pgPassword

    # 1) 探活：容器未就绪时重试，避免开机后因 Docker 冷启动而每天必失败
    $ready = $false
    for ($attempt = 1; $attempt -le $ProbeMaxAttempts; $attempt++) {
        & docker exec -e PGPASSWORD $ContainerName pg_isready -U $DbUser -d $DbName *> $null
        if ($LASTEXITCODE -eq 0) {
            $ready = $true
            Write-Log ("容器 {0} 就绪（第 {1} 次探活）" -f $ContainerName, $attempt)
            break
        }
        Write-Log ("容器 {0} 尚未就绪（第 {1}/{2} 次探活），{3} 秒后重试" -f $ContainerName, $attempt, $ProbeMaxAttempts, $ProbeIntervalSeconds)
        Start-Sleep -Seconds $ProbeIntervalSeconds
    }
    if (-not $ready) {
        Write-Log 'RESULT=FAIL 原因：探活超时，容器不可用'
        exit 1
    }

    # 建区前后各记一次分区清单，日志自带前后对照
    $listSql = "SELECT c.relname FROM pg_class c JOIN pg_inherits i ON i.inhrelid = c.oid JOIN pg_class p ON p.oid = i.inhparent WHERE p.relname = 't_device_telemetry' ORDER BY c.relname;"

    $before = Invoke-Psql -Sql $listSql
    Write-Log ("建区前分区数 {0}：{1}" -f ($before | Measure-Object).Count, ($before -join ','))

    # 2) 补建分区。与 partition_maintenance.sql 第一个 DO 块等价，幂等（CREATE ... IF NOT EXISTS）
    $buildSql = @"
DO `$`$
DECLARE
    d date;
    d_start timestamptz;
    d_end timestamptz;
    part_name text;
    future_days integer := $FutureDays;
BEGIN
    FOR d IN
        SELECT generate_series(
            (now() AT TIME ZONE 'Asia/Shanghai')::date,
            (now() AT TIME ZONE 'Asia/Shanghai')::date + future_days,
            interval '1 day'
        )::date
    LOOP
        d_start := (d::text || ' 00:00:00+08')::timestamptz;
        d_end   := ((d + 1)::text || ' 00:00:00+08')::timestamptz;
        part_name := 't_device_telemetry_' || to_char(d, 'YYYYMMDD');
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF t_device_telemetry FOR VALUES FROM (%L) TO (%L)',
            part_name, d_start, d_end
        );
    END LOOP;
END `$`$;
"@

    $output = Invoke-Psql -Sql $buildSql -StopOnError
    $psqlExit = $LASTEXITCODE
    if ($output) {
        Write-Log ("psql 输出：{0}" -f ($output -join ' | '))
    }
    if ($psqlExit -ne 0) {
        Write-Log ("RESULT=FAIL 原因：psql 退出码 {0}" -f $psqlExit)
        exit 1
    }

    $after = Invoke-Psql -Sql $listSql
    Write-Log ("建区后分区数 {0}：{1}" -f ($after | Measure-Object).Count, ($after -join ','))

    # 3) 兜底核对：default 分区若开始进数据，说明预建没跟上，需要人工介入
    $defaultRows = Invoke-Psql -Sql 'SELECT count(*) FROM t_device_telemetry_default;'
    Write-Log ("default 分区行数：{0}" -f $defaultRows)
    if ("$defaultRows" -ne '0') {
        Write-Log 'WARN：default 分区已有数据，预建天数可能不足或脚本曾漏跑，请人工核查'
    }

    Write-Log 'RESULT=OK'
    exit 0
}
catch {
    Write-Log ("RESULT=FAIL 未预期异常：{0}" -f $_.Exception.Message)
    exit 1
}
finally {
    # 密码不留在会话环境里
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
}
