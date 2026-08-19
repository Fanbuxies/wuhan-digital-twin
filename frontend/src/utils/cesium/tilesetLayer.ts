import {
  Cartesian2,
  Cartesian3,
  Cesium3DTileColorBlendMode,
  Cesium3DTileFeature,
  Cesium3DTileStyle,
  Cesium3DTileset,
  Color,
  type Cesium3DTile
} from 'cesium'

import {
  BUILDING_ALPHA,
  BUILDING_CATEGORY_RULES,
  HEIGHT_LIGHTNESS_LEVELS,
  pickBuildingColor,
  pickBuildingCssColor
} from '@/utils/cesium/buildingLayer'
import {
  createFacadeShader,
  getTilesetSharedRtc,
  setFacadeShaderRtc
} from '@/utils/cesium/facadeShader'
import { getViewer } from '@/utils/cesium/viewer'

/** 选中高亮色，与 GeoJSON 降级路径一致 */
const HIGHLIGHT_COLOR = '#ffb300'

/** 高亮色（含样式透明度），避免每次写入重复构造 */
const HIGHLIGHT_COLOR_WITH_ALPHA = Color.fromCssColorString(HIGHLIGHT_COLOR).withAlpha(BUILDING_ALPHA)

/** 等待目标瓦片按需加载的超时上限，超过视为未命中并放弃监听 */
const TILE_WAIT_TIMEOUT_MS = 8000

/** 高亮保持重写间隔：瓦片 refinement 重应用样式色会覆盖 feature.color，需周期重写 */
const KEEP_ALIVE_INTERVAL_MS = 1000

/** 背光面环境亮度底：imageBasedLightingFactor 被 Cesium 硬校验为 [0,1]（>1 直接抛错），
 *  环境光改由自定义球谐系数提供——仅 L00 非零即各方向等辐照度。
 *  czm_sphericalHarmonics 用未归一化单项式基，L00 数值即辐照度本身 */
const AMBIENT_IRRADIANCE = 0.2

/** 常数环境光球谐系数：L00 为辐照度，其余八项为零 */
const AMBIENT_SH: ReadonlyArray<Cartesian3> = [
  new Cartesian3(AMBIENT_IRRADIANCE, AMBIENT_IRRADIANCE, AMBIENT_IRRADIANCE),
  Cartesian3.ZERO,
  Cartesian3.ZERO,
  Cartesian3.ZERO,
  Cartesian3.ZERO,
  Cartesian3.ZERO,
  Cartesian3.ZERO,
  Cartesian3.ZERO,
  Cartesian3.ZERO
]

/** 当前挂载的建筑 tileset */
let buildingTileset: Cesium3DTileset | null = null

/** 当前高亮的建筑 id 与要素集合，还原配色与保持着色时使用 */
const highlightedFeatures = new Map<number, Set<Cesium3DTileFeature>>()

/** 高亮保持定时器句柄，clearTilesetHighlight 时停止 */
let keepAliveTimer: number | null = null

/**
 * 按「用途色相 × 高度明度」生成 tileset 样式，色板与 GeoJSON 降级路径共用。
 * 8 类 × 3 档共 24 条条件，类内按高度升序、末档 true 兜底该类超高建筑；
 * 不设总兜底——标签表外的新类型会落到 Cesium 默认白色，
 * SQL 验收保证现有 42 种标签全部覆盖
 */
function buildStyle(): Cesium3DTileStyle {
  const conditions: Array<[string, string]> = []
  for (const rule of BUILDING_CATEGORY_RULES) {
    const typeMatch = rule.types.map((type) => `\${building_type} === '${type}'`).join(' || ')
    for (const level of HEIGHT_LIGHTNESS_LEVELS) {
      const heightExpr =
        level.maxHeight === Number.POSITIVE_INFINITY ? 'true' : `\${height} <= ${level.maxHeight}`
      const cssColor = pickBuildingCssColor(rule.types[0], level.maxHeight)
      conditions.push([`(${typeMatch}) && ${heightExpr}`, `color("${cssColor}", ${BUILDING_ALPHA})`])
    }
  }
  return new Cesium3DTileStyle({
    color: { conditions }
  })
}

/**
 * 载入建筑 3D Tiles 图层。重复调用先移除旧 tileset，避免叠加
 */
export async function loadBuildingTileset(url: string): Promise<Cesium3DTileset> {
  removeBuildingTileset()
  const tileset = await Cesium3DTileset.fromUrl(url)
  // REPLACE 模式下样式色只应用一次（HIGHLIGHT 会把样式色与材质再乘一遍，配色偏暗），
  // 且 feature.color 直接覆盖材质色，高亮是纯色而非与底色相乘的浑浊色
  tileset.colorBlendMode = Cesium3DTileColorBlendMode.REPLACE
  tileset.style = buildStyle()
  // 照明强度统一由 scene.light 控制（固定方向光），tileset 侧光照颜色显式保持中性
  tileset.lightColor = new Cartesian3(1.0, 1.0, 1.0)
  // IBL 通道全开，背光面的环境亮度底由自定义球谐系数提供（见 AMBIENT_SH）
  tileset.imageBasedLighting.imageBasedLightingFactor = new Cartesian2(1.0, 1.0)
  tileset.imageBasedLighting.sphericalHarmonicCoefficients = [...AMBIENT_SH]
  // 立面程序化细节（楼层横带/窗格/屋顶）：MODIFY_MATERIAL 在样式色上乘明暗，不破坏配色与高亮
  const facadeShader = createFacadeShader()
  tileset.customShader = facadeShader
  const sharedRtc = getTilesetSharedRtc(tileset)
  if (sharedRtc !== null) {
    setFacadeShaderRtc(facadeShader, sharedRtc)
  } else {
    // 根瓦片变换尚未就绪时等首个内容瓦片加载（毫秒级），避免用占位原点渲染出错位的横带
    const setOriginOnce = (): void => {
      const rtc = getTilesetSharedRtc(tileset)
      if (rtc !== null) {
        setFacadeShaderRtc(facadeShader, rtc)
        tileset.tileLoad.removeEventListener(setOriginOnce)
      }
    }
    tileset.tileLoad.addEventListener(setOriginOnce)
  }
  getViewer().scene.primitives.add(tileset)
  buildingTileset = tileset
  return tileset
}

/**
 * 移除建筑 tileset，供重载与卸载时调用
 */
export function removeBuildingTileset(): void {
  clearTilesetHighlight()
  const scene = getViewer().scene
  if (buildingTileset !== null && !buildingTileset.isDestroyed()) {
    scene.primitives.remove(buildingTileset)
  }
  buildingTileset = null
}

/**
 * 高亮指定建筑要素，仅改 feature.color，不销毁重建 tileset。
 * 场景点选拿到的是可见要素，单个即可
 */
export function highlightBuildingFeature(feature: Cesium3DTileFeature): void {
  clearTilesetHighlight()
  const buildingId = getBuildingIdFromFeature(feature)
  if (buildingId === null) {
    // 无主键的要素无法按 id 找回，只着色不登记
    feature.color = HIGHLIGHT_COLOR_WITH_ALPHA
    return
  }
  applyHighlight([feature], buildingId)
}

/**
 * 还原上一次高亮的建筑配色。
 * 样式色在装载时写入批纹理，被高亮覆盖后需按「用途 × 高度」重算同一取色函数的颜色还原
 */
export function clearTilesetHighlight(): void {
  for (const features of highlightedFeatures.values()) {
    for (const feature of features) {
      try {
        const height = Number(feature.getProperty('height'))
        const buildingType = feature.getProperty('building_type')
        feature.color = Number.isFinite(height)
          ? pickBuildingColor(String(buildingType ?? ''), height)
          : Color.WHITE
      } catch {
        // 要素已随瓦片卸载销毁，忽略
      }
    }
  }
  highlightedFeatures.clear()
  if (keepAliveTimer !== null) {
    window.clearInterval(keepAliveTimer)
    keepAliveTimer = null
  }
}

/**
 * 给一批要素写入高亮色并登记。同一要素重复写入由 Set 去重
 */
function applyHighlight(features: Cesium3DTileFeature[], buildingId: number): void {
  let set = highlightedFeatures.get(buildingId)
  if (set === undefined) {
    set = new Set<Cesium3DTileFeature>()
    highlightedFeatures.set(buildingId, set)
  }
  for (const feature of features) {
    feature.color = HIGHLIGHT_COLOR_WITH_ALPHA
    set.add(feature)
  }
  startKeepAlive()
}

/**
 * 周期重写高亮色：瓦片 refinement 会重应用样式色覆盖 feature.color，需按 id 找回重写。
 * 只在高亮激活期间运行，开销为每次全量要素扫描（毫秒级）
 */
function keepAlive(): void {
  const tileset = buildingTileset
  if (tileset === null) {
    return
  }
  for (const buildingId of highlightedFeatures.keys()) {
    const found: Cesium3DTileFeature[] = []
    collectFeaturesInTiles(tileset.root, buildingId, found)
    for (const feature of found) {
      feature.color = HIGHLIGHT_COLOR_WITH_ALPHA
    }
  }
}

function startKeepAlive(): void {
  if (keepAliveTimer !== null) {
    return
  }
  keepAliveTimer = window.setInterval(keepAlive, KEEP_ALIVE_INTERVAL_MS)
}

/**
 * 从要素属性取建筑主键，属性缺失或非数值时返回 null
 */
export function getBuildingIdFromFeature(feature: Cesium3DTileFeature): number | null {
  const buildingId = Number(feature.getProperty('id'))
  return Number.isFinite(buildingId) ? buildingId : null
}

/**
 * 判断拾取结果是否为建筑 tileset 要素，用于拾取结果分发
 */
export function isBuildingFeature(picked: unknown): picked is Cesium3DTileFeature {
  return picked instanceof Cesium3DTileFeature
}

/**
 * 在单个瓦片中收集所有匹配主键的要素，瓦片未加载或无要素时不写入
 */
function collectFeaturesInTile(
  tile: Cesium3DTile,
  buildingId: number,
  result: Cesium3DTileFeature[]
): void {
  const content = tile.content
  if (content !== undefined && content.featuresLength > 0) {
    for (let index = 0; index < content.featuresLength; index += 1) {
      const feature = content.getFeature(index)
      if (getBuildingIdFromFeature(feature) === buildingId) {
        result.push(feature)
      }
    }
  }
}

/**
 * 深度优先遍历瓦片树收集匹配主键的建筑要素，未加载瓦片的 content 为空直接跳过。
 * 同一建筑在瓦片数据中可能存在多个重复要素，全部收集，高亮时一并写入
 */
function collectFeaturesInTiles(
  tile: Cesium3DTile,
  buildingId: number,
  result: Cesium3DTileFeature[]
): void {
  collectFeaturesInTile(tile, buildingId, result)
  for (const child of tile.children) {
    collectFeaturesInTiles(child, buildingId, result)
  }
}

/**
 * 按主键高亮建筑要素，仅改 feature.color，不销毁重建 tileset。
 * 瓦片按需加载，目标要素可能尚未加载，且同一建筑可能有重复要素分布在
 * 不同瓦片中陆续加载：轮询整段窗口期，每次收集全部匹配要素并着色；
 * 超时未命中返回 false。窗口期结束后由 keepAlive 兜底后续 refinement 覆盖。
 */
export function highlightBuildingById(buildingId: number): Promise<boolean> {
  const tileset = buildingTileset
  if (tileset === null) {
    return Promise.resolve(false)
  }
  clearTilesetHighlight()
  return new Promise((resolve) => {
    let found = false
    const poll = (): void => {
      const matches: Cesium3DTileFeature[] = []
      collectFeaturesInTiles(tileset.root, buildingId, matches)
      if (matches.length > 0) {
        applyHighlight(matches, buildingId)
        found = true
        resolve(true)
      }
    }
    poll()
    const pollTimer = window.setInterval(poll, 500)
    window.setTimeout(() => {
      window.clearInterval(pollTimer)
      resolve(found)
    }, TILE_WAIT_TIMEOUT_MS)
  })
}
