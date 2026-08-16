import {
  CENTER,
  createIconStore,
  drawIcon,
  type IconColor,
  type IconConfig
} from '@/utils/iconCanvas'

/** 图形基准半径，圆形与未知类型兜底环的外径 */
const SHAPE_RADIUS = 16

/**
 * 三角形：顶角到中心的垂直距离与底边半宽。
 * 底边刻意加宽，保证填充色采样点落在图形内
 * （约束来源：device-icon-task.md 5.1 验收脚本的固定采样点）
 */
const TRI_HALF_H = 16
const TRI_BASE_HALF = 32

/** 菱形：左右/上下顶点到中心的距离，左右大于上下以覆盖填充色采样点（约束来源同上） */
const DIAMOND_HALF_H = 17
const DIAMOND_HALF_V = 13

/** 方形半边长 */
const SQUARE_HALF = 17

/** 平顶六边形：半宽与半高，宽而扁（受填充色采样点约束，来源同上） */
const HEX_HALF_W = 18
const HEX_HALF_H = 11

/** 未知类型兜底环的内径，与基准半径构成空心圆环 */
const RING_INNER_RADIUS = 10

/**
 * 设备类型对应的正常态配色（类型色）。
 * 形状编码类型、颜色编码状态，正常态取类型色
 */
const TYPE_COLOR: Record<string, IconColor> = {
  SMOKE: { stroke: '#8e44ad', fill: 'rgba(142, 68, 173, 0.85)' },
  WATER: { stroke: '#1e88e5', fill: 'rgba(30, 136, 229, 0.85)' },
  TEMP_HUMI: { stroke: '#17a2b8', fill: 'rgba(23, 162, 184, 0.85)' },
  ELECTRIC: { stroke: '#5c6bc0', fill: 'rgba(92, 107, 192, 0.85)' },
  CAMERA: { stroke: '#d6336c', fill: 'rgba(214, 51, 108, 0.85)' }
}

/** 未知类型兜底配色，取不与状态色冲突的灰蓝 */
const UNKNOWN_TYPE_COLOR: IconColor = {
  stroke: '#5f6b7a',
  fill: 'rgba(95, 107, 122, 0.85)'
}

/**
 * 设备类型登记表：中心字形与 TwinView 图例共用的唯一来源，新增类型只改这里
 */
export const DEVICE_TYPE_META: ReadonlyArray<{ type: string; label: string }> = [
  { type: 'SMOKE', label: '烟' },
  { type: 'WATER', label: '水' },
  { type: 'TEMP_HUMI', label: '温' },
  { type: 'ELECTRIC', label: '电' },
  { type: 'CAMERA', label: '像' }
]

/** 设备类型对应的中心字符，用单字区分类型，避免引入图标字体 */
const TYPE_GLYPH: Record<string, string> = Object.fromEntries(
  DEVICE_TYPE_META.map((meta): [string, string] => [meta.type, meta.label])
)

/** 未知类型兜底字符 */
const UNKNOWN_GLYPH = '?'

/**
 * 绘制设备类型对应的形状路径，供填充、描边与字形裁剪复用
 *
 * <p>形状恒定不随状态变化，告警变色不变形，远距离按形状即可判型。</p>
 */
function traceShape(ctx: CanvasRenderingContext2D, deviceType: string): void {
  ctx.beginPath()
  switch (deviceType) {
    case 'SMOKE':
      // 三角形：顶角朝上，底边加宽以覆盖填充色采样点
      ctx.moveTo(CENTER, CENTER - TRI_HALF_H)
      ctx.lineTo(CENTER - TRI_BASE_HALF, CENTER + TRI_HALF_H)
      ctx.lineTo(CENTER + TRI_BASE_HALF, CENTER + TRI_HALF_H)
      break
    case 'WATER':
      ctx.arc(CENTER, CENTER, SHAPE_RADIUS, 0, Math.PI * 2)
      break
    case 'TEMP_HUMI':
      // 菱形：左右顶点距离大于上下，兼顾辨识度与填充色采样点覆盖
      ctx.moveTo(CENTER, CENTER - DIAMOND_HALF_V)
      ctx.lineTo(CENTER + DIAMOND_HALF_H, CENTER)
      ctx.lineTo(CENTER, CENTER + DIAMOND_HALF_V)
      ctx.lineTo(CENTER - DIAMOND_HALF_H, CENTER)
      break
    case 'ELECTRIC':
      ctx.rect(CENTER - SQUARE_HALF, CENTER - SQUARE_HALF, SQUARE_HALF * 2, SQUARE_HALF * 2)
      break
    case 'CAMERA':
      // 平顶六边形：宽而扁，与圆形/菱形在形状采样上区分开
      ctx.moveTo(CENTER - HEX_HALF_W, CENTER)
      ctx.lineTo(CENTER - HEX_HALF_W / 2, CENTER - HEX_HALF_H)
      ctx.lineTo(CENTER + HEX_HALF_W / 2, CENTER - HEX_HALF_H)
      ctx.lineTo(CENTER + HEX_HALF_W, CENTER)
      ctx.lineTo(CENTER + HEX_HALF_W / 2, CENTER + HEX_HALF_H)
      ctx.lineTo(CENTER - HEX_HALF_W / 2, CENTER + HEX_HALF_H)
      break
    default:
      // 未知类型兜底为空心圆环：外圆正转、内圆反转，非零环绕规则下自然镂空，
      // 形状通道不与任何真实类型混淆；内圆先用 moveTo 另起子路径，避免两圆间出现描边连线
      ctx.arc(CENTER, CENTER, SHAPE_RADIUS, 0, Math.PI * 2)
      ctx.moveTo(CENTER + RING_INNER_RADIUS, CENTER)
      ctx.arc(CENTER, CENTER, RING_INNER_RADIUS, 0, Math.PI * 2, true)
  }
  ctx.closePath()
}

/** 单层图标配置：形状、字形与类型色装配，供共享管线消费 */
const ICON_CONFIG: IconConfig = {
  traceShape,
  glyphOf: (type) => TYPE_GLYPH[type] ?? UNKNOWN_GLYPH,
  typeColor: TYPE_COLOR,
  unknownColor: UNKNOWN_TYPE_COLOR
}

/**
 * 取设备图标 dataURL，同类型同状态复用同一张图
 *
 * @param deviceType 设备类型
 * @param status 运行状态
 * @param alarmLevel 告警级别，0 正常 1 预警 2 告警
 */
const iconStore = createIconStore((deviceType, status, alarmLevel) =>
  drawIcon(deviceType, status, alarmLevel, ICON_CONFIG)
)

export function getDeviceIcon(deviceType: string, status: string, alarmLevel: number): string {
  return iconStore.get(deviceType, status, alarmLevel)
}

/** 选中态图标存储，与普通态缓存键隔离 */
const selectedIconStore = createIconStore((deviceType, status, alarmLevel) =>
  drawIcon(deviceType, status, alarmLevel, ICON_CONFIG, true)
)

/**
 * 取设备选中态图标，仅叠加白描边，形状与配色与普通图标一致
 */
export function getDeviceSelectedIcon(
  deviceType: string,
  status: string,
  alarmLevel: number
): string {
  return selectedIconStore.get(deviceType, status, alarmLevel)
}

/**
 * 当前已缓存的图标张数，供验证用
 */
export function getIconCacheSize(): number {
  return iconStore.cacheSize()
}
