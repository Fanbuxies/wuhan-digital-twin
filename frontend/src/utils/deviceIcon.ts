/** 图标画布边长，48 足够 retina 下不虚，再大 250 张 billboard 显存压力上升 */
const ICON_SIZE = 48

/** 图形半径 */
const SHAPE_RADIUS = 16

/** 描边宽度 */
const STROKE_WIDTH = 3

/** 中心文字字号 */
const GLYPH_FONT = 'bold 18px sans-serif'

/** 设备离线 */
const STATUS_OFFLINE = 'OFFLINE'

/** 设备故障 */
const STATUS_FAULT = 'FAULT'

/** 告警级别：预警 */
const LEVEL_WARN = 1

/** 告警级别：告警 */
const LEVEL_ALARM = 2

/** 各状态对应的描边与填充色 */
const COLOR_NORMAL = { stroke: '#2eb85c', fill: 'rgba(46, 184, 92, 0.85)' }
const COLOR_WARN = { stroke: '#f9b115', fill: 'rgba(249, 177, 21, 0.85)' }
const COLOR_ALARM = { stroke: '#e55353', fill: 'rgba(229, 83, 83, 0.9)' }
const COLOR_OFFLINE = { stroke: '#909399', fill: 'rgba(144, 147, 153, 0.7)' }
const COLOR_FAULT = { stroke: '#e6a23c', fill: 'rgba(230, 162, 60, 0.85)' }

/** 设备类型对应的中心字符，用单字区分类型，避免引入图标字体 */
const TYPE_GLYPH: Record<string, string> = {
  SMOKE: '烟',
  WATER: '水',
  TEMP_HUMI: '温',
  ELECTRIC: '电',
  CAMERA: '像'
}

/** 未知类型兜底字符 */
const UNKNOWN_GLYPH = '?'

/**
 * dataURL 缓存，键为「类型|状态标识」。5 类型 × 5 状态最多 25 张，全程只生成一次
 */
const iconCache = new Map<string, string>()

/**
 * 取状态配色。离线与故障优先于告警级别——设备不在线时的指标没有意义
 */
function resolveColor(status: string, alarmLevel: number): { stroke: string; fill: string } {
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
  return COLOR_NORMAL
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
 * 画一枚圆形带描边的图标并返回 dataURL
 */
function drawIcon(deviceType: string, status: string, alarmLevel: number): string {
  const canvas = document.createElement('canvas')
  canvas.width = ICON_SIZE
  canvas.height = ICON_SIZE
  const ctx = canvas.getContext('2d')
  if (ctx === null) {
    throw new Error('无法获取 canvas 2d 上下文，设备图标生成失败')
  }
  const center = ICON_SIZE / 2
  const color = resolveColor(status, alarmLevel)

  ctx.beginPath()
  ctx.arc(center, center, SHAPE_RADIUS, 0, Math.PI * 2)
  ctx.fillStyle = color.fill
  ctx.fill()
  ctx.lineWidth = STROKE_WIDTH
  ctx.strokeStyle = color.stroke
  ctx.stroke()

  ctx.font = GLYPH_FONT
  ctx.fillStyle = '#ffffff'
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.fillText(TYPE_GLYPH[deviceType] ?? UNKNOWN_GLYPH, center, center + 1)

  return canvas.toDataURL('image/png')
}

/**
 * 取设备图标 dataURL，同类型同状态复用同一张图
 *
 * @param deviceType 设备类型
 * @param status 运行状态
 * @param alarmLevel 告警级别，0 正常 1 预警 2 告警
 */
export function getDeviceIcon(deviceType: string, status: string, alarmLevel: number): string {
  const key = `${deviceType}|${resolveStateKey(status, alarmLevel)}`
  const cached = iconCache.get(key)
  if (cached !== undefined) {
    return cached
  }
  const dataUrl = drawIcon(deviceType, status, alarmLevel)
  iconCache.set(key, dataUrl)
  return dataUrl
}

/**
 * 当前已缓存的图标张数，供验证用
 */
export function getIconCacheSize(): number {
  return iconCache.size
}
