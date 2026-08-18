import {
  Color,
  ColorMaterialProperty,
  ConstantProperty,
  Entity,
  GeoJsonDataSource,
  JulianDate
} from 'cesium'

import type { BuildingFeatureCollection, BuildingFeatureProperties } from '@/api/building'

/** 建筑用途归并规则：OSM building 标签 42 种取值归并为 8 类，色相编码用途。
 *  tileset 样式与 GeoJSON 降级路径共用同一套规则 */
export const BUILDING_CATEGORY_RULES: ReadonlyArray<{
  category: string
  cssColor: string
  types: string[]
}> = [
  { category: '住宅', cssColor: '#d9b382', types: ['apartments', 'house', 'residential', 'dormitory', 'bungalow', 'appartment'] },
  { category: '商业', cssColor: '#4aa3c7', types: ['commercial', 'retail', 'hotel', 'office', 'louge', 'yes;retail'] },
  { category: '教育', cssColor: '#8e7cc3', types: ['university', 'school', 'college', 'kindergarten', 'library', 'museum'] },
  { category: '医疗', cssColor: '#d47b9a', types: ['hospital', 'clinic'] },
  { category: '工业仓储', cssColor: '#8a8574', types: ['industrial', 'greenhouse', 'barn', 'water_tower'] },
  { category: '交通市政', cssColor: '#6b8fa3', types: ['parking', 'carport', 'train_station', 'guardhouse', 'gatehouse'] },
  { category: '公共文体', cssColor: '#5fae94', types: ['public', 'sports_hall', 'grandstand', 'stadium', 'church', 'cathedral', 'theatre', 'pavilion', 'community'] },
  { category: '未分类', cssColor: '#c2c8ce', types: ['yes', 'roof', 'ruins', 'tower'] }
]

/** 高度明度分档：明度编码高度（≤24m 浅 / ≤80m 中 / >80m 深），正数向白混合、负数向黑混合 */
export const HEIGHT_LIGHTNESS_LEVELS: ReadonlyArray<{ maxHeight: number; mixToWhite: number }> = [
  { maxHeight: 24, mixToWhite: 0.3 },
  { maxHeight: 80, mixToWhite: 0 },
  { maxHeight: Number.POSITIVE_INFINITY, mixToWhite: -0.3 }
]

/** 未知用途的归并类名，未列入规则表的标签一律兜底到该中性色 */
export const UNCATEGORIZED_BUILDING_CATEGORY = '未分类'

/** 原始标签 → 归并类的映射，由规则表派生，避免两处维护 */
const BUILDING_TYPE_CATEGORY_MAP: ReadonlyMap<string, string> = new Map(
  BUILDING_CATEGORY_RULES.flatMap((rule) => rule.types.map((type) => [type, rule.category] as const))
)

/** 归并类 → 基色的映射，由规则表派生 */
const CATEGORY_COLOR_MAP: ReadonlyMap<string, string> = new Map(
  BUILDING_CATEGORY_RULES.map((rule) => [rule.category, rule.cssColor] as const)
)

/** 白模不透明度：用途配色后无需再透出底图，全不透明避免路网穿透楼体、密集区发糊 */
export const BUILDING_ALPHA = 1.0

/** 选中高亮色 */
const HIGHLIGHT_COLOR = '#ffb300'

/** 高度缺失时的兜底值，与后端 default_by_type 的其他类默认值一致 */
const FALLBACK_HEIGHT = 15

/** 地面基准高程缺失时的兜底值 */
const FALLBACK_BASE_ALTITUDE = 0

/** 记录各建筑的原始配色，高亮切换时还原，避免重建数据源 */
const originalColorMap = new Map<string, Color>()

/** 当前高亮的实体，还原配色时直接使用 */
let highlightedEntity: Entity | null = null

/**
 * 把 OSM 原始建筑标签归并为 8 类之一，未知标签返回未分类
 */
export function mergeBuildingCategory(buildingType: string | null | undefined): string {
  if (buildingType === null || buildingType === undefined) {
    return UNCATEGORIZED_BUILDING_CATEGORY
  }
  return BUILDING_TYPE_CATEGORY_MAP.get(buildingType) ?? UNCATEGORIZED_BUILDING_CATEGORY
}

/**
 * 按「用途色相 × 高度明度」取样式色（不含 alpha）。
 * 色相由用途归并类决定，明度分三档：≤24m 浅、≤80m 本色、>80m 深
 */
export function pickBuildingCssColor(buildingType: string, height: number): string {
  const category = mergeBuildingCategory(buildingType)
  const baseCss = CATEGORY_COLOR_MAP.get(category) ?? CATEGORY_COLOR_MAP.get(UNCATEGORIZED_BUILDING_CATEGORY)!
  const level = HEIGHT_LIGHTNESS_LEVELS.find((item) => height <= item.maxHeight)
    ?? HEIGHT_LIGHTNESS_LEVELS[HEIGHT_LIGHTNESS_LEVELS.length - 1]
  const base = Color.fromCssColorString(baseCss)
  if (level.mixToWhite > 0) {
    return Color.lerp(base, Color.WHITE, level.mixToWhite, new Color()).toCssHexString()
  }
  if (level.mixToWhite < 0) {
    return Color.lerp(base, Color.BLACK, -level.mixToWhite, new Color()).toCssHexString()
  }
  return baseCss
}

/**
 * 按「用途色相 × 高度明度」取建筑配色（含白模透明度）。
 * tileset 图层还原高亮时复用同一取色函数，保证与样式色一致
 */
export function pickBuildingColor(buildingType: string, height: number): Color {
  return Color.fromCssColorString(pickBuildingCssColor(buildingType, height)).withAlpha(BUILDING_ALPHA)
}

/**
 * 读取要素属性，GeoJsonDataSource 把 properties 挂在 entity.properties 上
 */
function readProperties(entity: Entity): BuildingFeatureProperties | null {
  if (entity.properties === undefined) {
    return null
  }
  return entity.properties.getValue(JulianDate.now()) as BuildingFeatureProperties
}

/**
 * 载入建筑轮廓并按 height 拉伸为白模
 *
 * <p>extrudedHeight 是绝对高度，故需叠加 baseAltitude 作为地面基准。</p>
 */
export async function loadBuildingLayer(
  featureCollection: BuildingFeatureCollection
): Promise<GeoJsonDataSource> {
  const dataSource = await GeoJsonDataSource.load(featureCollection, { clampToGround: false })
  originalColorMap.clear()
  highlightedEntity = null
  for (const entity of dataSource.entities.values) {
    const polygon = entity.polygon
    if (polygon === undefined) {
      continue
    }
    const properties = readProperties(entity)
    const height = Number(properties?.height ?? FALLBACK_HEIGHT)
    const baseAltitude = Number(properties?.baseAltitude ?? FALLBACK_BASE_ALTITUDE)
    const color = pickBuildingColor(properties?.buildingType ?? '', height)
    polygon.height = new ConstantProperty(baseAltitude)
    polygon.extrudedHeight = new ConstantProperty(baseAltitude + height)
    polygon.material = new ColorMaterialProperty(color)
    // 905 栋建筑逐栋描边会明显掉帧，靠用途×高度双通道配色区分
    polygon.outline = new ConstantProperty(false)
    originalColorMap.set(String(entity.id), color)
  }
  return dataSource
}

/**
 * 还原上一次高亮的建筑配色
 */
export function clearHighlight(): void {
  if (highlightedEntity === null) {
    return
  }
  const originalColor = originalColorMap.get(String(highlightedEntity.id))
  if (highlightedEntity.polygon !== undefined && originalColor !== undefined) {
    highlightedEntity.polygon.material = new ColorMaterialProperty(originalColor)
  }
  highlightedEntity = null
}

/**
 * 高亮指定建筑，仅改材质颜色，不销毁重建数据源
 */
export function highlightBuilding(entity: Entity): void {
  clearHighlight()
  if (entity.polygon === undefined) {
    return
  }
  entity.polygon.material = new ColorMaterialProperty(
    Color.fromCssColorString(HIGHLIGHT_COLOR).withAlpha(BUILDING_ALPHA)
  )
  highlightedEntity = entity
}

/**
 * 判断实体是否属于建筑图层，用于拾取结果分发
 */
export function isBuildingEntity(entity: Entity): boolean {
  return originalColorMap.has(String(entity.id))
}
