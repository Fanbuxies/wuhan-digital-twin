import {
  CENTER,
  createIconStore,
  drawIcon,
  type IconColor,
  type IconConfig
} from '@/utils/iconCanvas'

/** 圆角方形：边长与圆角半径 */
const ROUND_SQUARE_SIZE = 28
const ROUND_SQUARE_RADIUS = 6

/** 五边形：外接圆半径，顶点朝上 */
const PENTAGON_RADIUS = 16

/** 竖胶囊：宽与高，端部为半圆 */
const PILL_WIDTH = 18
const PILL_HEIGHT = 32

/** 顶弧方牌：下半矩形的高与整宽，顶部为半圆 */
const SIGN_WIDTH = 24
const SIGN_HEIGHT = 26

/** 未知类型兜底环：外径与内径，轮廓与设备层兜底一致 */
const RING_OUTER_RADIUS = 16
const RING_INNER_RADIUS = 10

/**
 * 设施类型对应的正常态配色（类型色），与设备类型色、状态色均错开。
 * 形状编码类型、颜色编码状态，正常态取类型色
 */
const TYPE_COLOR: Record<string, IconColor> = {
  CHARGING_PILE: { stroke: '#2f9e44', fill: 'rgba(47, 158, 68, 0.85)' },
  STREET_LAMP: { stroke: '#8d6e63', fill: 'rgba(141, 110, 99, 0.85)' },
  MANHOLE: { stroke: '#0ca678', fill: 'rgba(12, 166, 120, 0.85)' },
  BUS_STOP: { stroke: '#845ef7', fill: 'rgba(132, 94, 247, 0.85)' }
}

/** 未知类型兜底配色 */
const UNKNOWN_TYPE_COLOR: IconColor = {
  stroke: '#5f6b7a',
  fill: 'rgba(95, 107, 122, 0.85)'
}

/** 设施类型对应的中心字符，用单字区分类型，避免引入图标字体 */
const TYPE_GLYPH: Record<string, string> = {
  CHARGING_PILE: '充',
  STREET_LAMP: '灯',
  MANHOLE: '井',
  BUS_STOP: '公'
}

/** 未知类型兜底字符 */
const UNKNOWN_GLYPH = '?'

/**
 * 绘制设施类型对应的形状路径，供填充、描边与字形裁剪复用
 *
 * <p>形状恒定不随状态变化，告警变色不变形，远距离按形状即可判型。</p>
 */
function traceShape(ctx: CanvasRenderingContext2D, facilityType: string): void {
  ctx.beginPath()
  switch (facilityType) {
    case 'CHARGING_PILE':
      // 圆角方形
      ctx.roundRect(
        CENTER - ROUND_SQUARE_SIZE / 2,
        CENTER - ROUND_SQUARE_SIZE / 2,
        ROUND_SQUARE_SIZE,
        ROUND_SQUARE_SIZE,
        ROUND_SQUARE_RADIUS
      )
      break
    case 'STREET_LAMP':
      // 五边形，顶点朝上。空路径上首个 lineTo 等价 moveTo，直接连线即可
      for (let i = 0; i < 5; i += 1) {
        const angle = -Math.PI / 2 + (i * 2 * Math.PI) / 5
        ctx.lineTo(
          CENTER + PENTAGON_RADIUS * Math.cos(angle),
          CENTER + PENTAGON_RADIUS * Math.sin(angle)
        )
      }
      break
    case 'MANHOLE':
      // 竖胶囊
      ctx.roundRect(
        CENTER - PILL_WIDTH / 2,
        CENTER - PILL_HEIGHT / 2,
        PILL_WIDTH,
        PILL_HEIGHT,
        PILL_WIDTH / 2
      )
      break
    case 'BUS_STOP':
      // 顶弧方牌：下半矩形 + 顶部半圆，形似公交站牌
      ctx.moveTo(CENTER - SIGN_WIDTH / 2, CENTER + SIGN_HEIGHT / 2)
      ctx.lineTo(CENTER - SIGN_WIDTH / 2, CENTER)
      ctx.arc(CENTER, CENTER, SIGN_WIDTH / 2, Math.PI, 0)
      ctx.lineTo(CENTER + SIGN_WIDTH / 2, CENTER + SIGN_HEIGHT / 2)
      break
    default:
      // 未知类型兜底为空心圆环：外圆正转、内圆反转，非零环绕规则下自然镂空，
      // 形状通道不与任何真实类型混淆；内圆先用 moveTo 另起子路径，避免两圆间出现描边连线
      ctx.arc(CENTER, CENTER, RING_OUTER_RADIUS, 0, Math.PI * 2)
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
 * 取设施图标 dataURL，同类型同状态复用同一张图
 *
 * @param facilityType 设施类型
 * @param status 运行状态
 * @param alarmLevel 告警级别，0 正常 1 预警 2 告警
 */
const iconStore = createIconStore((facilityType, status, alarmLevel) =>
  drawIcon(facilityType, status, alarmLevel, ICON_CONFIG)
)

export function getFacilityIcon(
  facilityType: string,
  status: string,
  alarmLevel: number
): string {
  return iconStore.get(facilityType, status, alarmLevel)
}

/**
 * 当前已缓存的图标张数，供验证用
 */
export function getFacilityIconCacheSize(): number {
  return iconStore.cacheSize()
}
