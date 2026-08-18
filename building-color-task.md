# 任务：建筑白模光照修复 + 按用途着色（A + B）

> 给终端 Claude Code 的任务书，整份贴过去即可，无需额外上下文。
> 【已验证】= 我在本机实际跑命令确认过的事实，可直接信任。
> 执行本任务的模型**没有识图能力**，第六节的验收刻意全部做成数字断言。

---

## 一、问题与已查明的事实

用户反馈：三维场景里建筑外立面看起来是黑的，希望变成彩色。

### 1.1 现在并不是「黑白配色」，是被光照压暗了

【已验证】`frontend/src/utils/buildingLayer.ts:13` 的 `HEIGHT_COLOR_STOPS`
本来就是一套蓝灰渐变，按高度分五段：

```
≤12m  #d8e3ee    ≤24m  #b8cde0    ≤40m  #93b2cf    ≤80m  #6d92b8    其余  #4a6f9c
```

`tilesetLayer.ts` 用这套色板生成 `Cesium3DTileStyle`，
且 `colorBlendMode = Cesium3DTileColorBlendMode.REPLACE`。

【已验证】我解析了 `frontend/public/tiles/content/*.glb`，材质是：

```json
{"doubleSided": true, "pbrMetallicRoughness": {"metallicFactor": 0, "roughnessFactor": 0.5019}}
```

纯白、非金属、带 `NORMAL` 法线。**排除了「金属材质缺环境贴图渲染成黑」这个常见坑。**

所以发暗来自 Cesium 的 PBR 光照。诊断依据：截图里**底图影像是亮的、只有 3D 模型发黑**——
影像图层不受 `scene.light` 影响，tileset 受影响，这个反差是光照问题的典型特征。

⚠️ **具体是哪一项导致的，我没能在运行时验证**（应用的 viewer 单例取不到，见 6.1）。
最可能是太阳位置（实机时间晚上八点半，武汉已日落），但也可能是
`imageBasedLightingFactor` 或 `tileset.lightColor` 的默认值。
**你的第一步是查清楚，不要照抄我的猜测就动手改。**

### 1.2 「真实立面颜色」做不到，别往这个方向查

【已验证】切片的 `EXT_structural_metadata` 里只有这些属性：

```
id (INT64) / name (STRING) / height (FLOAT32) /
building_type (STRING) / levels (INT32) / height_source (STRING)
```

**没有任何立面纹理、材质、照片信息**——数据源是 OSM 建筑轮廓拉伸的白模。
要真实立面需要倾斜摄影或实景三维建模，是另一套数据采集管线，不在本任务范围。
**不要尝试找纹理、不要引入外部贴图。**

### 1.3 关键：不需要重新生成切片

【已验证】`building_type` **已经随切片带出来了**（见 1.2 的属性表）。
所以按用途着色**纯前端改 `Cesium3DTileStyle` 即可**，
**禁止重跑 pg2b3dm、禁止改 `t_building_3d`、禁止碰 `frontend/public/tiles/`**。
这条如果搞错，会白白浪费大量时间。

## 二、任务 A：修复光照

目标：让样式色以接近本色的亮度显示，**既不发黑也不过曝**。

### 2.1 先诊断（必做，做完把读数贴出来）

读取并打印以下运行时值，判断到底是哪一项在压暗：

- `viewer.clock.currentTime`（转 ISO8601，看是不是夜间）
- `scene.globe.enableLighting`
- `scene.light` 的类型与 `intensity`
- `tileset.lightColor`
- `tileset.imageBasedLighting.imageBasedLightingFactor`

### 2.2 再修（按诊断结果选，不要全都改一遍）

可选手段，**优先选副作用最小的**：

- 若是太阳位置：把 tileset 与太阳解耦，抬高 `tileset.lightColor`
  （如 `new Cartesian3(2.0, 2.0, 2.0)`，具体值调到不过曝为止）
- 若是 IBL 压暗：`tileset.imageBasedLighting.imageBasedLightingFactor = new Cartesian2(0, 0)`
- **不要**用「把场景时钟固定成正午」的做法——那会让实时告警场景的时间显示失真

**禁止**为了提亮去调 `BUILDING_ALPHA` 或直接把色板整体改亮，
那是掩盖问题，样式色和实际呈现色会对不上。

## 三、任务 B：按建筑用途着色

### 3.1 编码设计：色相编码用途，明度编码高度

沿用本项目已确立的**双通道**设计语言（设备图标就是这么做的）：

- **色相 = 建筑用途**（8 类，见 3.2）
- **明度 = 高度档位**（3 档：≤24m 浅、≤80m 中、>80m 深）

这样既能一眼看出用途，又保留了原来「高度可读」的信息量。
样式条件数 = 8 × 3 = 24 条，`Cesium3DTileStyle` 完全承受得住。

**备选方案**（若用户觉得太花）：只用纯类型色、不做明度分档。先做主方案。

### 3.2 类型归并表（必须严格按这个归并，数量已核对）

【已验证】数据库里 `building_type` 共 42 种取值，合计 5303 行，归并如下：

| 归并类 | 建议色相 | OSM 原始值 | 数量 |
|---|---|---|---|
| 住宅 | `#d9b382` 暖米黄 | apartments, house, residential, dormitory, bungalow, appartment | 1662 |
| 商业 | `#4aa3c7` 青蓝 | commercial, retail, hotel, office, louge, yes;retail | 328 |
| 教育 | `#8e7cc3` 紫 | university, school, college, kindergarten, library, museum | 217 |
| 医疗 | `#d47b9a` 粉 | hospital, clinic | 39 |
| 工业仓储 | `#8a8574` 灰褐 | industrial, greenhouse, barn, water_tower | 28 |
| 交通市政 | `#6b8fa3` 蓝灰 | parking, carport, train_station, guardhouse, gatehouse | 42 |
| 公共文体 | `#5fae94` 绿松 | public, sports_hall, grandstand, stadium, church, cathedral, theatre, pavilion, community | 49 |
| 未分类 | `#c2c8ce` 中性浅灰 | yes, roof, ruins, tower | 2938 |

**八类合计必须等于 5303。**

⚠️ `yes` 一个值就占 2891 条（55%），是 OSM 里「确定是建筑但没细分用途」的意思。
**必须归到「未分类」给中性色**，不要硬塞进住宅或商业——
否则一半的楼是同一个颜色，比现在还糊。

**配色硬约束**：所有类型色必须避开选中高亮色 `#ffb300`（橙），
否则选中状态和普通建筑分不清。上表已按此约束选过。

### 3.3 必须同步改的两处（最容易漏，漏了就是 bug）

**第一处 —— 取消高亮后的还原色。**
`tilesetLayer.ts` 现在还原颜色用的是：

```js
feature.color = Number.isFinite(height) ? pickColorByHeight(height) : Color.WHITE
```

改成按用途着色后，**这里必须同步改成新的取色函数**，
否则点选再取消后建筑会被还原成旧的高度色，跟周围颜色对不上。
`tilesetLayer.ts` 里还有一处「高亮保持定时器」周期重写色，同理要一起改。

**第二处 —— GeoJSON 降级路径。**
`buildingLayer.ts` 是 tilesetUrl 为空时的降级路径，与 tileset 共用色板。
`docs/requirements.md` 第 5 节明确写了两条路径色板一致，
所以**降级路径要同步改成同一套用途配色**，保持视觉语言统一。

## 四、改动范围

**主改：**

- `frontend/src/utils/buildingLayer.ts` — 新增类型归并表与取色函数（**必须导出为纯函数**，见 6.2）
- `frontend/src/utils/tilesetLayer.ts` — 样式条件改为用途×高度，同步改还原色与保持着色
- `frontend/src/utils/viewer.ts` 或 `tilesetLayer.ts` — 光照参数（任务 A）

**必须同步：**

- `docs/requirements.md` 第 5 节「前端图层职责」中建筑层那段——
  补上用途配色规则与归并表（CLAUDE.md 要求）

**建议加（用户已批准，见 6.1）：**

- `viewer.ts` 里加 dev-only 调试钩子，仅用于验收

**禁止碰：**

- `frontend/public/tiles/`、`t_building_3d`、pg2b3dm 相关的一切
- 状态卡图例区（设备图例已定稿，建筑图例是可选项，要加先问用户）

## 五、硬约束

- **禁止新增依赖**，配色用 Cesium 原生 `Cesium3DTileStyle`，不许引色板库
- **禁止销毁重建 tileset**（CLAUDE.md 硬约束），只改 `style` 和 `feature.color`
- Cesium Viewer 单例管理，禁止在组件内 `new Viewer`
- 禁止魔法值，颜色与高度档位阈值全部提成具名常量
- 一律 `<script setup lang="ts">`，注释用中文
- 只改本任务列出的文件，禁止重构无关代码
- 不主动 commit / push

## 六、验收（**不依赖截图，全部是数字断言**）

### 6.1 先加一个 dev-only 调试钩子（用户已批准）

我在验收上一个任务时发现：`import('/src/utils/viewer.ts')` 拿到的是**新的模块实例**，
`getViewer()` 会抛「Viewer 尚未创建」，够不到应用正在用的那个单例。
所以**允许**在 `viewer.ts` 创建 Viewer 之后加一行，仅开发环境生效：

```ts
// 仅开发环境暴露，供自动化验收读取运行时状态，生产构建不包含
if (import.meta.env.DEV) {
  (window as unknown as Record<string, unknown>).__twinViewer = viewer
}
```

**必须用 `import.meta.env.DEV` 包住**，不许无条件挂到 window。

### 6.2 取色函数必须是可独立调用的纯函数

`buildingLayer.ts` 里导出，签名类似：

```ts
export function pickBuildingColor(buildingType: string, height: number): Color
export function mergeBuildingCategory(buildingType: string): string  // 返回 8 类之一
```

这样验收脚本可以 `import('/src/utils/buildingLayer.ts')` 直接调，不依赖运行时单例。

### 6.3 断言一：归并表正确（SQL，纯数字）

```bash
docker exec -e PGPASSWORD=$pw twin-pg psql -U twin -d twin -c "
select building_type, count(*) from t_building group by building_type order by count(*) desc"
```

用 `mergeBuildingCategory()` 对这 42 个原始值分别求归并类，按 3.2 表汇总，
**八类数量必须分别等于 1662 / 328 / 217 / 39 / 28 / 42 / 49 / 2938，合计 5303。**

### 6.4 断言二：配色映射正确（页面里求值，纯数字）

```js
(async () => {
  const b = await import('/src/utils/buildingLayer.ts');
  const types = ['apartments','commercial','university','hospital','industrial','parking','public','yes'];
  const rows = types.map(t => {
    const c = b.pickBuildingColor(t, 30);
    return { type: t, cat: b.mergeBuildingCategory(t),
             rgb: [Math.round(c.red*255), Math.round(c.green*255), Math.round(c.blue*255)].join(',') };
  });
  // 同一用途、不同高度应只有明度差，色相接近
  const lowMid = ['apartments'].map(t => [10,50,120].map(h => {
    const c = b.pickBuildingColor(t, h);
    return [Math.round(c.red*255), Math.round(c.green*255), Math.round(c.blue*255)].join(',');
  }));
  return { rows, distinctHues: new Set(rows.map(r => r.rgb)).size, 住宅三档明度: lowMid[0] };
})()
```

必须满足：

| 断言 | 期望 |
|---|---|
| `distinctHues` | **8**（八类颜色互不相同） |
| 住宅三档明度 | 三个值互不相同，且 RGB 比例接近（同色相不同明度） |
| 任一类型色与 `#ffb300` 的欧氏距离 | **> 80**（不与高亮色混淆） |

### 6.5 断言三：样式真的挂到了 tileset 上（运行时）

```js
(() => {
  const v = window.__twinViewer;
  const scene = v.scene;
  let ts = null;
  for (let i = 0; i < scene.primitives.length; i++) {
    const p = scene.primitives.get(i);
    if (p?.constructor?.name === 'Cesium3DTileset') { ts = p; break; }
  }
  const hits = [];
  const w = scene.canvas.clientWidth, h = scene.canvas.clientHeight;
  for (let gy = 1; gy <= 6; gy++) for (let gx = 1; gx <= 6; gx++) {
    const p = scene.pick(new (window.Cesium?.Cartesian2 || Object)(Math.round(gx*w/7), Math.round(gy*h/7)));
    if (p?.constructor?.name === 'Cesium3DTileFeature') {
      hits.push({ type: p.getProperty('building_type'), height: p.getProperty('height'),
                  rgb: [Math.round(p.color.red*255), Math.round(p.color.green*255), Math.round(p.color.blue*255)].join(',') });
    }
  }
  return { styleConditions: ts?.style?.color?.conditions?.length,
           lightColor: ts?.lightColor ? [ts.lightColor.x, ts.lightColor.y, ts.lightColor.z] : '未设置',
           iblFactor: ts?.imageBasedLighting?.imageBasedLightingFactor
             ? [ts.imageBasedLighting.imageBasedLightingFactor.x, ts.imageBasedLighting.imageBasedLightingFactor.y] : null,
           pickedCount: hits.length, samples: hits.slice(0, 6) };
})()
```

（Cartesian2 的取法按实际情况调整，能拿到即可。）

必须满足：

| 断言 | 期望 |
|---|---|
| `styleConditions` | **24**（8 类 × 3 档） |
| `lightColor` / `iblFactor` | 是任务 A 中你实际设定的值，不是「未设置」 |
| `pickedCount` | > 0 |
| 每个 sample 的 `rgb` | 与 `pickBuildingColor(type, height)` 的返回值**完全一致** |

最后一条是最关键的——它证明**渲染出来的颜色确实来自新的配色逻辑**，
而不是样式写了但没生效。

### 6.6 断言四：高亮与还原

用 `tilesetLayer.ts` 已有的高亮接口对某个建筑高亮，再取消，然后重新 pick 该 feature：

- 高亮后 `feature.color` ≈ `#ffb300`（255,179,0）
- 取消后 `feature.color` **等于** `pickBuildingColor(该楼的 type, 该楼的 height)`
  —— 这条专门用来抓 3.3 说的那个还原色 bug

### 6.7 汇报要求

把 6.3 ~ 6.6 四段的**完整原始输出**贴给用户，不要只写「验证通过」。
**亮度是否合适（不发黑也不过曝）这一项你无法判断，不要下结论**——
由用户和另一个有识图能力的会话做目视确认。

## 七、环境提醒

- 前端 `http://localhost:5173`，后端 `http://localhost:8080`，PostGIS 容器 `twin-pg` 映射 5434
- 密码在用户级环境变量 `PGPASSWORD`：
  `[Environment]::GetEnvironmentVariable('PGPASSWORD','User')`，
  当前 shell 的 `$env:PGPASSWORD` 是空的，必须显式从 User 作用域取；禁止明文写进任何文件
- **前后端可能由另一个会话托管着 8080 / 5173**，要重启后端前先说一声，两边同时抢 8080 会打架
- 本任务是纯前端改动，正常情况下**不需要重启后端**
- 改完前端 Vite 会 HMR，但验收脚本受 HMR 模块实例影响，**跑验收前先硬刷新页面**
