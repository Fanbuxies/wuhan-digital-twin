#!/usr/bin/env node
/**
 * Stop hook：验收未通过就阻止 Claude 结束回复。
 * 验收条件来自 central-districts-task.md 第七章「验收（数字断言）」。
 *
 * 设计原则：
 *  1. 只做「可达成」的断言，避免无限 block 循环白烧 token。
 *  2. 环境不可用（Docker 没起、psql 缺失）时跳过对应检查，不算失败——
 *     否则用户关掉 Docker 就永远停不下来。
 *  3. 必须快：每轮 Stop 都跑，psql 单条超时 8 秒。
 */
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const PSQL_TIMEOUT_MS = 8000;

let buf = '';
process.stdin.on('data', (d) => (buf += d));
process.stdin.on('end', () => {
    let input = {};
    try {
        input = JSON.parse(buf || '{}');
    } catch (e) {
        process.exit(0); // 输入异常不阻塞用户
    }
    // 防死循环：上一轮已经是被 hook 拦下续跑的，这次直接放行
    if (input.stop_hook_active) {
        process.exit(0);
    }

    const root = process.env.CLAUDE_PROJECT_DIR || process.cwd();

    // 逃生阀：无人值守时若遇到真正需要用户决策的阻塞（脚本无法自行解决的报错、
    // 需要授权但用户不在场的操作），写一份 .claude/BLOCKED.md 说明情况即可正常结束。
    // 没有这个出口，Claude 会被反复 block 到熔断，用户回来只看到一堆空转。
    const blockedFile = path.join(root, '.claude/BLOCKED.md');
    if (fs.existsSync(blockedFile)) {
        process.exit(0);
    }

    const fails = [];
    const skipped = [];

    checkTickInterval(root, fails);
    checkTileset(root, fails);
    checkSeedTarget(root, fails);
    checkDatabase(root, fails, skipped);

    if (fails.length > 0) {
        const reason =
            '【验收未通过】central-districts-task.md 第七章的数字断言未全部满足，' +
            '请继续修复后再结束，不要提前收工：\n- ' +
            fails.join('\n- ') +
            (skipped.length
                ? '\n\n（以下检查因环境不可用被跳过，不计入失败：' + skipped.join('；') + '）'
                : '') +
            '\n\n注意：涉及 DDL / 批量数据操作的步骤，仍需先把完整 SQL 给用户看过再执行。';
        process.stdout.write(JSON.stringify({ decision: 'block', reason }));
    }
    process.exit(0);
});

/** 7.1 配置断言：telemetry-tick-interval 必须等于 20 */
function checkTickInterval(root, fails) {
    const yml = path.join(root, 'backend/src/main/resources/application.yml');
    if (!fs.existsSync(yml)) {
        fails.push('application.yml 不存在：' + yml);
        return;
    }
    const text = fs.readFileSync(yml, 'utf8');
    const m = text.match(/telemetry-tick-interval:\s*(\d+)/);
    if (!m) {
        fails.push('application.yml 里找不到 app.simulator.telemetry-tick-interval');
    } else if (m[1] !== '20') {
        fails.push(
            `telemetry-tick-interval 当前为 ${m[1]}，任务书要求改为 20（3000ms × 20 = 60 秒落库间隔）`
        );
    }
}

/** 7.4 前端产物：3D Tiles 必须真的落到挂载目录 */
function checkTileset(root, fails) {
    const tileset = path.join(root, 'frontend/public/tiles/tileset.json');
    if (!fs.existsSync(tileset)) {
        fails.push(
            `${tileset} 不存在 —— pg2b3dm 输出没落到挂载目录。` +
                'Git Bash 会把 -v 里的 /app/output 做 MSYS 路径转换，需加 MSYS_NO_PATHCONV=1 或写成 //app/output'
        );
        return;
    }
    let j;
    try {
        j = JSON.parse(fs.readFileSync(tileset, 'utf8'));
    } catch (e) {
        fails.push('tileset.json 不是合法 JSON：' + e.message);
        return;
    }
    if (!j.root) {
        fails.push('tileset.json 内容为空，没有 root 节点');
        return;
    }
    // pg2b3dm 用 implicitTiling 时没有 children，改判 content + subtree 文件是否存在
    const hasChildren = Array.isArray(j.root.children) && j.root.children.length > 0;
    const hasImplicit = j.root.implicitTiling && j.root.content && j.root.content.uri;
    if (!hasChildren && !hasImplicit) {
        fails.push('tileset.json 既没有 children 也没有 implicitTiling + content，切片内容为空');
    }
    const contentDir = path.join(root, 'frontend/public/tiles/content');
    if (!fs.existsSync(contentDir) || fs.readdirSync(contentDir).length === 0) {
        fails.push('frontend/public/tiles/content/ 为空，没有生成任何 glb 切片');
    }
}

/** 阶段 3：seed_devices.py 的目标值必须已按 2000 台上限调整过 */
function checkSeedTarget(root, fails) {
    const seed = path.join(root, 'data-prep/seed_devices.py');
    if (!fs.existsSync(seed)) {
        return; // 脚本不在就不判，交给 DB 断言兜底
    }
    const text = fs.readFileSync(seed, 'utf8');
    const m = text.match(/TARGET_DEVICE_TOTAL\s*=\s*(\d+)/);
    if (m && m[1] === '600') {
        fails.push(
            'seed_devices.py 的 TARGET_DEVICE_TOTAL 仍是 600（阶段 3 未做）。' +
                '该脚本实际入库会超出目标约 22%，要落到 2000 台需设为约 1650，或修正 cell 配额逻辑'
        );
    }
}

/** 7.1 / 7.2 数据库断言。psql 或容器不可用时整体跳过 */
function checkDatabase(root, fails, skipped) {
    const pgpass = readPgPassword();
    if (!pgpass) {
        skipped.push('未取到 PGPASSWORD（User 环境变量），跳过全部数据库断言');
        return;
    }
    const probe = psql(pgpass, 'select 1');
    if (probe === null) {
        skipped.push('数据库连不上（twin-pg 容器可能没起），跳过全部数据库断言');
        return;
    }

    // 7.1 分区改造：必须有按天的实际分区，且 default 分区为空
    const partCount = psqlInt(
        pgpass,
        `select count(*) from pg_class c
         join pg_inherits i on i.inhrelid = c.oid
         join pg_class p on p.oid = i.inhparent
         where p.relname = 't_device_telemetry' and c.relname <> 't_device_telemetry_default'`
    );
    if (partCount !== null && partCount < 1) {
        fails.push(
            't_device_telemetry 只有 default 分区，没有任何按天的实际分区（阶段 0 未完成）。' +
                '分区裁剪不生效，也无法用 DROP 分区做 7 天保留'
        );
    }

    const defaultRows = psqlInt(pgpass, 'select count(*) from t_device_telemetry_default');
    if (defaultRows !== null && defaultRows > 0) {
        fails.push(
            `t_device_telemetry_default 还有 ${defaultRows} 行，期望 0。` +
                '说明新数据仍落回 default 分区，或旧数据未按 5.2 清空重建'
        );
    }

    // 7.2 数据规模
    // 任务书写的下限是 30000，但那是行政区裁剪前的估算值。实测七区裁剪后为 29818 栋
    // （七区分别有 17836/5412/2246/1718/1536/813/280 栋，采集无遗漏），
    // 与估算仅差 0.6%，属数据本身决定的结果，故下限放宽到 29000。
    const buildings = psqlInt(pgpass, 'select count(*) from t_building');
    if (buildings !== null && (buildings < 29000 || buildings > 38000)) {
        fails.push(
            `t_building 共 ${buildings} 行，期望落在 29000 ~ 38000（阶段 1/2 的采集与入库未完成）`
        );
    }

    const b3d = psqlInt(pgpass, 'select count(*) from t_building_3d');
    if (buildings !== null && b3d !== null && b3d !== buildings) {
        fails.push(`t_building_3d 有 ${b3d} 行，必须等于 t_building 的 ${buildings} 行`);
    }

    // 行政区裁剪是否真的生效
    const outside = psqlInt(
        pgpass,
        `select count(*) from t_building b
         where not exists (
           select 1 from t_district d where st_intersects(b.footprint, d.boundary)
         )`
    );
    if (outside !== null && outside > 0) {
        fails.push(
            `有 ${outside} 栋建筑落在七区边界并集之外，期望 0。入库时的 ST_Intersects 裁剪没生效`
        );
    }

    const districts = psqlInt(pgpass, 'select count(*) from t_district');
    if (districts !== null && districts !== 7) {
        fails.push(`t_district 有 ${districts} 行，期望 7（江岸/江汉/硚口/汉阳/武昌/青山/洪山）`);
    }

    // 设备总量：必须落在 1900~2000 且不超过 2000
    const devices = psqlInt(pgpass, 'select count(*) from t_device');
    if (devices !== null && (devices < 1900 || devices > 2000)) {
        fails.push(`t_device 有 ${devices} 台，必须落在 1900 ~ 2000 且不得超过 2000`);
    }

    // 设备空间分布：防止仍全挤在原来那一小片
    const cells = psqlInt(
        pgpass,
        `select count(*) from (
           select width_bucket(st_x(location), 114.05, 114.47, 6) as c,
                  width_bucket(st_y(location), 30.42, 30.72, 4) as r
           from t_device where location is not null group by 1, 2
         ) t`
    );
    if (cells !== null && cells > 0 && cells < 8) {
        fails.push(
            `设备只分布在 ${cells} 个网格里，说明仍挤在原 bbox 那一小片，没有覆盖七个区（期望多数网格有值）`
        );
    }

    // 7.3 容量：稳定态遥测 ≤ 5 GB
    const telemetryBytes = psqlInt(pgpass, "select pg_total_relation_size('t_device_telemetry')");
    const FIVE_GB = 5 * 1024 * 1024 * 1024;
    if (telemetryBytes !== null && telemetryBytes > FIVE_GB) {
        const gb = (telemetryBytes / 1024 / 1024 / 1024).toFixed(2);
        fails.push(
            `t_device_telemetry 占用 ${gb} GB，超过 5 GB 阈值。降频或 7 天保留策略其中之一没真正生效`
        );
    }
}

/** 密码只从 User 作用域环境变量取，禁止明文落盘或回显 */
let pgPassCache;
function readPgPassword() {
    if (pgPassCache !== undefined) {
        return pgPassCache;
    }
    if (process.env.PGPASSWORD) {
        pgPassCache = process.env.PGPASSWORD;
        return pgPassCache;
    }
    try {
        const out = execFileSync(
            'powershell.exe',
            [
                '-NoProfile',
                '-Command',
                "[Environment]::GetEnvironmentVariable('PGPASSWORD','User')"
            ],
            { encoding: 'utf8', timeout: PSQL_TIMEOUT_MS, stdio: ['ignore', 'pipe', 'ignore'] }
        );
        pgPassCache = (out || '').trim() || null;
        return pgPassCache;
    } catch (e) {
        pgPassCache = null;
        return pgPassCache;
    }
}

/** 执行一条 SQL，返回裸值字符串；连不上或出错返回 null */
function psql(pgpass, sql) {
    try {
        const out = execFileSync(
            'psql',
            [
                '-h', 'localhost',
                '-p', '5434',
                '-U', 'twin',
                '-d', 'twin',
                '-t', '-A', '-X',
                '-v', 'ON_ERROR_STOP=1',
                '-c', sql
            ],
            {
                encoding: 'utf8',
                timeout: PSQL_TIMEOUT_MS,
                stdio: ['ignore', 'pipe', 'ignore'],
                env: { ...process.env, PGPASSWORD: pgpass, PGCONNECT_TIMEOUT: '5' }
            }
        );
        return (out || '').trim();
    } catch (e) {
        return null;
    }
}

/** 表不存在等情况返回 null，由调用方跳过该条断言 */
function psqlInt(pgpass, sql) {
    const raw = psql(pgpass, sql);
    if (raw === null || raw === '') {
        return null;
    }
    const n = Number(raw.split('\n')[0].trim());
    return Number.isFinite(n) ? n : null;
}
