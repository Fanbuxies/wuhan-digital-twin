# 任务：建筑立面程序化增强（CustomShader 画楼层与窗格）

> 给终端 Claude Code 的任务书，整份贴过去即可，无需额外上下文。
> 【已验证】= 我在本机实际跑命令确认过的事实，可直接信任。
> 执行本任务的模型**没有识图能力**，第六节验收全部做成数字断言，目视由用户负责。

---

## 一、目标与技术选型

现在的建筑是纯色盒子（已按用途着色）。目标是**在不换数据、不损失任何交互的前提下**，
给立面加上程序化细节——楼层横带、竖向窗格、底商区分、屋顶与立面区分，
让远看有建筑质感，而不是一块块糖块。

### 1.1 必须用 CustomShader，不能用 Cesium3DTileStyle

`Cesium3DTileStyle` **只能设置颜色**，画不了任何图案。

【已验证】切片 glb 的顶点属性只有：

```
POSITION, NORMAL, _FEATURE_ID_0
```

**没有 `TEXCOORD_0`**，也就是没有 UV 坐标，所以**贴图这条路直接封死**——
不能用纹理贴图做窗格。

唯一可行的是 **`Cesium3DTileset.customShader`**（Cesium 的 `CustomShader` API），
在片元着色器里用**模型坐标 + 法线**程序化算出图案。Cesium 1.115 原生支持，不需要新依赖。

### 1.2 必须用 MODIFY_MATERIAL 模式

`CustomShaderMode.MODIFY_MATERIAL`：样式色和 `feature.color`（选中高亮色）
会先算好传进 shader，你在其基础上**乘上**窗格明暗即可。

`REPLACE_MATERIAL` 会把这些全部丢掉，**高亮和用途配色都会失效**，禁止使用。

⚠️ 这一条我**没有实机验证过**，只是按 Cesium 的语义推断。
**你的第一步是先搭一个最小 shader 验证这个前提**（见 6.2），
确认样式色和高亮色确实能透传进来，再往下做。前提不成立就停下来问我。

## 二、已知的数据事实（直接用，不用再查）

【已验证】`t_building` 共 5303 行：

- **`levels` 只有 1008 行非空，覆盖率 19.0%**（取值 1~97）。
  **不能用 `levels` 驱动楼层数**，绝大多数楼没有这个值。
- `height` 全部非空，范围 3.20 ~ 169.60 m
- 项目的层高约定是 **3.2 m/层**（`height` 的取值几乎都是 3.2 的整数倍，
  由 OSM `levels × 3.2` 推出；`height_source = default_by_type` 的那批统一是 15.0 m）

**所以楼层数一律用 `height / 3.2` 算**，不要读 `levels`。

【已验证】切片元数据（`EXT_structural_metadata`）里可用的属性：

```
id (INT64) / name (STRING) / height (FLOAT32) /
building_type (STRING) / levels (INT32) / height_source (STRING)
```

## 三、要画的内容

按优先级，**先做 1~3，做完汇报**，4~5 等我确认：

1. **楼层横带**：沿高度方向每 3.2 m 一道细的暗色分隔线，模拟楼板
2. **竖向窗格**：沿立面水平方向周期性明暗，模拟窗与墙柱交替
3. **屋顶与立面区分**：用法线判断——接近水平的面（屋顶）不画窗格，
   给一个略暗或略灰的处理，避免屋顶出现竖条纹
4. **底层区分**（可选）：最下面约 4 m 作为底商，用整片通透的处理而非小窗
5. **按用途区分立面样式**（可选）：商业走横向幕墙分格、住宅走规则小窗，
   通过 shader 里读 `fsInput.metadata.building_type` 实现

**分寸**：这是白模增强，不是做真实建筑。图案要克制——
远看有质感即可，对比度过强会让 5303 栋楼在城市视角糊成一片噪点。

## 四、关键技术点与坑

### 4.1 高度方向的取法要实测确定

shader 里拿到的是 `fsInput.attributes.positionMC`（模型坐标）。
glTF 默认 Y-up，但 3D Tiles 会施加变换，pg2b3dm 的输出到底哪个分量是"高度"
**必须实测确定，不要假设**。

做法：先写一个临时 shader 把某个分量直接映射成颜色，
看是不是随楼高渐变，确定后再正式用。

### 4.2 楼层横带要基于"楼底起算的高度"

`positionMC` 是模型局部坐标，同一个瓦片里多栋楼共用一个坐标系，
直接拿绝对坐标画横带会导致**不同楼的楼层线对不齐地面**。
需要用该片元所属要素的 `height` 元数据配合，或用瓦片内的相对基准。
这一条是本任务最容易出视觉 bug 的地方，做完务必抽查高矮不同的楼是否都从楼底起算。

### 4.3 性能

5303 栋楼逐片元算图案，指令数不能太高。
禁止在 shader 里用循环、`pow` 堆叠、噪声函数。
横带和窗格用 `fract` / `step` / `smoothstep` 就够了。

### 4.4 GeoJSON 降级路径不做这个

降级路径是 `GeoJsonDataSource` 的 Entity 多边形，**没有 customShader 能力**，
保持现在的纯色即可。不要为此改降级路径，也不要试图给它加图案。
在 `docs/requirements.md` 里注明这个差异。

## 五、改动范围与硬约束

**主改：**

- `frontend/src/utils/tilesetLayer.ts` — 构造并挂载 `customShader`
- 可新增 `frontend/src/utils/facadeShader.ts` 承载 GLSL 与参数常量（推荐，避免 tilesetLayer 膨胀）

**必须同步：**

- `docs/requirements.md` 第 5 节建筑层那段，补上立面着色器说明与降级路径差异

**禁止碰：**

- `frontend/public/tiles/`、`t_building_3d`、pg2b3dm 相关一切——**本任务不需要重新生成切片**
- 已定稿的用途配色逻辑（`buildingLayer.ts` 的归并表与取色函数）
- 上一轮刚调好的光照参数（`DirectionalLight` intensity 1.2、球谐环境光、`BUILDING_ALPHA` 1.0）
- 设备/设施图层与图标

**硬约束：**

- **禁止新增依赖**，用 Cesium 原生 `CustomShader`
- **禁止销毁重建 tileset**，`customShader` 是可以直接赋值的属性
- 禁止魔法值，层高、窗格周期、明暗强度全部提成具名常量
- 注释用中文；一律 `<script setup lang="ts">`（本任务基本不涉及 vue 文件）
- 只改本任务列出的文件，禁止重构无关代码
- 不主动 commit / push

## 六、验收（**不依赖截图，全部数字断言**）

页面里可用 `window.__twinViewer`（上一个任务已加的 dev-only 钩子）取运行时状态。
**跑验收前先硬刷新页面**，否则 HMR 会让模块实例对不上。

### 6.1 前置：不得破坏已通过的断言

上一个任务（建筑配色）的四段断言**必须全部仍然通过**，逐条复跑并贴输出：

- 归并表八类数量 1662 / 328 / 217 / 39 / 28 / 42 / 49 / 2938，合计 5303
- `distinctHues === 8`，住宅三档明度互不相同
- `styleConditions === 24`，采样 feature 的 rgb 与 `pickBuildingColor` 一致
- 高亮 ≈ (255,179,0)，取消后还原为 `pickBuildingColor(type, height)`

### 6.2 断言一：MODIFY_MATERIAL 前提成立（**最先做这个**）

挂一个最小 shader（只把 material.diffuse 原样输出），然后：

| 断言 | 期望 |
|---|---|
| `tileset.customShader` 已挂载 | 非 null |
| 采样 feature 的渲染色 | 仍等于 `pickBuildingColor(type, height)` |
| 高亮某栋楼后再采样 | 仍 ≈ (255,179,0) |

**这三条不同时成立就停下来告诉我**，说明 MODIFY_MATERIAL 不透传颜色，方案要改。

### 6.3 断言二：着色器参数与挂载状态

```js
(() => {
  const v = window.__twinViewer;
  let ts = null;
  for (let i = 0; i < v.scene.primitives.length; i++) {
    const p = v.scene.primitives.get(i);
    if (p?.constructor?.name === 'Cesium3DTileset') { ts = p; break; }
  }
  const cs = ts?.customShader;
  return {
    hasCustomShader: !!cs,
    mode: cs?.mode,
    uniformNames: cs ? Object.keys(cs.uniforms || {}) : [],
    fragmentLength: cs?.fragmentShaderText?.length ?? 0,
    styleConditions: ts?.style?.color?.conditions?.length ?? null
  };
})()
```

期望：`hasCustomShader === true`，`mode` 是 MODIFY_MATERIAL 对应值，
`styleConditions` 仍为 **24**（着色器不该影响样式）。

### 6.4 断言三：帧率没有明显劣化

加了逐片元计算，必须量化性能影响。改动**前后各测一次**，同一视角：

```js
(async () => {
  const samples = [];
  let last = performance.now();
  await new Promise(res => {
    let n = 0;
    const tick = () => {
      const now = performance.now();
      samples.push(now - last); last = now;
      if (++n < 180) requestAnimationFrame(tick); else res();
    };
    requestAnimationFrame(tick);
  });
  samples.sort((a, b) => a - b);
  const avg = samples.reduce((s, x) => s + x, 0) / samples.length;
  return { 平均帧间隔ms: +avg.toFixed(2), 平均FPS: +(1000 / avg).toFixed(1),
           中位数ms: +samples[Math.floor(samples.length / 2)].toFixed(2),
           最差5%ms: +samples[Math.floor(samples.length * 0.95)].toFixed(2) };
})()
```

期望：**平均 FPS 相比改动前下降不超过 15%**，且不低于 30。
超了就说明 shader 太重，简化图案。

### 6.5 断言四：楼层线从楼底起算（抓 4.2 那个坑）

这条没法纯数字验证，但可以做一个间接检查：
在 shader 里临时输出"该片元算出的楼层序号"到颜色通道，
对**高矮差异大的两栋楼**（如 height=15 和 height=96）各采样底部附近的片元，
确认两者算出的楼层序号都接近 0（都从各自楼底起算），而不是一栋 0 一栋 20。
验完把临时输出去掉。

### 6.6 汇报要求

把 6.1 ~ 6.5 的**完整原始输出**贴给用户，不要只写「验证通过」。
**图案好不好看、密不密、对比度合不合适，你无法判断，不要下结论**——
由用户和另一个有识图能力的会话做目视确认。

## 七、环境提醒

- 前端 `http://localhost:5173`，后端 `http://localhost:8080`，PostGIS 容器 `twin-pg` 映射 5434
- 本任务是**纯前端改动，不需要重启后端**。Vite 会 HMR
- **8080 / 5173 目前由另一个会话的 preview 托管**，
  不要执行 `mvn spring-boot:run`，会跟那边抢端口
- 密码在用户级环境变量 `PGPASSWORD`：
  `[Environment]::GetEnvironmentVariable('PGPASSWORD','User')`，禁止明文写入任何文件
- `develop` 分支上的改动已于今日提交推送，工作区应是干净的；
  **不要 stash / reset / checkout 覆盖**，也不要替用户提交
