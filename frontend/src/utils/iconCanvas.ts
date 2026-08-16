/**
 * 设备与设施两层共用的图标画布管线。
 *
 * <p>两层的图标编码规则相同：形状编码类型（恒定），填充色编码状态
 * （正常取类型色，异常被状态色覆盖）。状态色表、状态优先级、缓存键
 * 格式与绘制管线逐字一致，抽到此处共用，避免两处手抄漂移；
 * 各模块只保留形状几何、类型色与中心字形三份配置。</p>
 */

/** 图标画布边长，48 足够 retina 下不虚，再大 250 张 billboard 显存压力上升 */
const ICON_SIZE = 48

/** 描边宽度 */
const STROKE_WIDTH = 3

/** 选中态外圈描边在基础描边上的叠加宽度 */
const SELECT_OUTLINE_EXTRA = 4

/** 选中态外圈描边颜色，白圈与状态色、告警色均不冲突 */
const SELECT_OUTLINE_COLOR = '#ffffff'

/** 画布中心坐标，各模块描画形状时以它为准 */
export const CENTER = ICON_SIZE / 2

/** 中心文字字号 */
const GLYPH_FONT = 'bold 18px sans-serif'

/** 离线 */
const STATUS_OFFLINE = 'OFFLINE'

/** 故障 */
const STATUS_FAULT = 'FAULT'

/** 告警级别：预警 */
const LEVEL_WARN = 1

/** 告警级别：告警 */
const LEVEL_ALARM = 2

/** 单色样式：描边色与填充色 */
export interface IconColor {
  stroke: string
  fill: string
}

/** 各状态对应的描边与填充色，色相与类型色错开，正常态不会被误读成告警 */
const COLOR_WARN: IconColor = { stroke: '#f9b115', fill: 'rgba(249, 177, 21, 0.85)' }
const COLOR_ALARM: IconColor = { stroke: '#e55353', fill: 'rgba(229, 83, 83, 0.9)' }
const COLOR_OFFLINE: IconColor = { stroke: '#909399', fill: 'rgba(144, 147, 153, 0.7)' }
const COLOR_FAULT: IconColor = { stroke: '#e6a23c', fill: 'rgba(230, 162, 60, 0.85)' }

/** 单层图标配置：形状描画、中心字形、类型色与未知类型兜底色 */
export interface IconConfig {
  traceShape: (ctx: CanvasRenderingContext2D, type: string) => void
  glyphOf: (type: string) => string
  typeColor: Record<string, IconColor>
  unknownColor: IconColor
}

/**
 * 取状态配色。离线与故障优先于告警级别——对象不在线时的指标没有意义；
 * 正常档取类型色，异常档被状态色覆盖
 */
function resolveColor(
  type: string,
  status: string,
  alarmLevel: number,
  typeColor: Record<string, IconColor>,
  unknownColor: IconColor
): IconColor {
  if (status === STATUS_OFFLINE) {
    return COLOR_OFFLINE
  }
  if (status === STATUS_FAULT) {
    return COLOR_FAULT
  }
  if (alarmLevel === LEVEL_ALARM) {
    return COLOR_ALARM
  }
  if (alarmLevel === LEVEL_WARN) {
    return COLOR_WARN
  }
  return typeColor[type] ?? unknownColor
}

/**
 * 缓存键中的状态部分：离线/故障只有一种形态，在线时按告警级别分档
 */
function resolveStateKey(status: string, alarmLevel: number): string {
  if (status === STATUS_OFFLINE || status === STATUS_FAULT) {
    return status
  }
  return `ONLINE_${alarmLevel}`
}

/**
 * 画一枚按类型区分形状与配色的图标并返回 dataURL
 *
 * <p>双通道编码：形状编码类型（恒定），填充色编码状态（正常取类型色，异常被状态色覆盖）。</p>
 */
export function drawIcon(
  type: string,
  status: string,
  alarmLevel: number,
  config: IconConfig,
  selected = false
): string {
  const canvas = document.createElement('canvas')
  canvas.width = ICON_SIZE
  canvas.height = ICON_SIZE
  const ctx = canvas.getContext('2d')
  if (ctx === null) {
    throw new Error('无法获取 canvas 2d 上下文，图标生成失败')
  }
  const color = resolveColor(type, status, alarmLevel, config.typeColor, config.unknownColor)

  config.traceShape(ctx, type)
  ctx.fillStyle = color.fill
  ctx.fill()
  ctx.lineWidth = STROKE_WIDTH
  ctx.strokeStyle = color.stroke
  ctx.stroke()

  // 字形裁剪进形状内，避免字符溢出污染形状轮廓（远距离按形状判型的前提）
  ctx.save()
  config.traceShape(ctx, type)
  ctx.clip()
  ctx.font = GLYPH_FONT
  ctx.fillStyle = '#ffffff'
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.fillText(config.glyphOf(type), CENTER, CENTER + 1)
  ctx.restore()

  // 选中态在形状外圈叠一圈白描边，作为与告警色/状态色独立的视觉通道
  if (selected) {
    config.traceShape(ctx, type)
    ctx.lineWidth = STROKE_WIDTH + SELECT_OUTLINE_EXTRA
    ctx.strokeStyle = SELECT_OUTLINE_COLOR
    ctx.stroke()
  }

  return canvas.toDataURL('image/png')
}

/** 图标存取：按「类型|状态标识」缓存 dataURL，同类型同状态全程只生成一次 */
export interface IconStore {
  get: (type: string, status: string, alarmLevel: number) => string
  cacheSize: () => number
}

/**
 * 建图标存储：缓存键为「类型|状态标识」，类型 × 状态组合最多 25/20 张
 *
 * @param draw 图标绘制函数，通常指向 drawIcon 与模块配置的组合
 */
export function createIconStore(
  draw: (type: string, status: string, alarmLevel: number) => string
): IconStore {
  const iconCache = new Map<string, string>()
  return {
    get(type, status, alarmLevel) {
      const key = `${type}|${resolveStateKey(status, alarmLevel)}`
      const cached = iconCache.get(key)
      if (cached !== undefined) {
        return cached
      }
      const dataUrl = draw(type, status, alarmLevel)
      iconCache.set(key, dataUrl)
      return dataUrl
    },
    cacheSize() {
      return iconCache.size
    }
  }
}
