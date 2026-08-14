import {
  Color,
  ColorMaterialProperty,
  ConstantProperty,
  Entity,
  GeoJsonDataSource,
  JulianDate
} from 'cesium'

import type { BuildingFeatureCollection, BuildingFeatureProperties } from '@/api/building'

/** 按高度分段着色，最后一段兜底所有超高建筑 */
const HEIGHT_COLOR_STOPS: ReadonlyArray<{ maxHeight: number; cssColor: string }> = [
  { maxHeight: 12, cssColor: '#d8e3ee' },
  { maxHeight: 24, cssColor: '#b8cde0' },
  { maxHeight: 40, cssColor: '#93b2cf' },
  { maxHeight: 80, cssColor: '#6d92b8' },
  { maxHeight: Number.POSITIVE_INFINITY, cssColor: '#4a6f9c' }
]

/** 白模半透明度，略透以便看清叠压关系 */
const BUILDING_ALPHA = 0.92

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
 * 按高度取分段颜色
 */
function pickColorByHeight(height: number): Color {
  const stop = HEIGHT_COLOR_STOPS.find((item) => height <= item.maxHeight)
  const cssColor =
    stop === undefined ? HEIGHT_COLOR_STOPS[HEIGHT_COLOR_STOPS.length - 1].cssColor : stop.cssColor
  return Color.fromCssColorString(cssColor).withAlpha(BUILDING_ALPHA)
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
    const color = pickColorByHeight(height)
    polygon.height = new ConstantProperty(baseAltitude)
    polygon.extrudedHeight = new ConstantProperty(baseAltitude + height)
    polygon.material = new ColorMaterialProperty(color)
    // 905 栋建筑逐栋描边会明显掉帧，靠分段配色区分体量
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
