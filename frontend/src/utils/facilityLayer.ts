import {
  BillboardCollection,
  Cartesian2,
  Cartesian3,
  Color,
  HorizontalOrigin,
  LabelCollection,
  LabelStyle,
  NearFarScalar,
  VerticalOrigin,
  type Billboard,
  type Label
} from 'cesium'

import type { FacilityItem } from '@/api/facility'
import { getFacilityIcon } from '@/utils/facilityIcon'
import { getViewer } from '@/utils/viewer'

/** 拾取标识的种类，用于与设备、建筑分支区分 */
const FACILITY_ID_KIND = 'facility'

/** billboard.id 的结构，pick 后据此分发 */
export interface FacilityPickId {
  kind: typeof FACILITY_ID_KIND
  facilityId: number
}

/** 图标显示边长，比设备略小，设施数量约为设备的两倍 */
const ICON_WIDTH = 24

/** 图标显示高度 */
const ICON_HEIGHT = 24

/**
 * 深度检测阈值取 0，即完全参与深度测试。
 * 与设备层刻意相反：设施都在地面，若关掉深度检测，江对岸的路灯会浮在近处楼顶上
 */
const DEPTH_TEST_DISTANCE = 0

/** 图标随距离缩放：近处原尺寸，远处缩到 0.4 */
const ICON_SCALE_NEAR = 600
const ICON_SCALE_NEAR_VALUE = 1
const ICON_SCALE_FAR = 4000
const ICON_SCALE_FAR_VALUE = 0.4

/** 标签随距离淡出，比设备层收紧一档：800 m 内可见，2000 m 外全透明 */
const LABEL_TRANSLUCENCY_NEAR = 800
const LABEL_TRANSLUCENCY_FAR = 2000

/** 标签字体 */
const LABEL_FONT = '12px sans-serif'

/** 标签描边宽度 */
const LABEL_OUTLINE_WIDTH = 2

/** 标签相对图标的像素偏移，压在图标下方 */
const LABEL_PIXEL_OFFSET = new Cartesian2(0, 16)

/** 安装高度缺省值 */
const FALLBACK_ALTITUDE = 0

/**
 * 需要标签的设施类型：路灯与井盖数量在千级，全部挂标签必然糊屏，且它们没有可读名称
 */
const LABELED_TYPES: ReadonlySet<string> = new Set(['CHARGING_PILE', 'BUS_STOP'])

/** 图层集合与索引，updateFacilityState 只改 billboard.image，不 remove/re-add */
let billboardCollection: BillboardCollection | null = null
let labelCollection: LabelCollection | null = null
const billboardMap = new Map<number, Billboard>()
const labelMap = new Map<number, Label>()

/** 设施当前状态缓存，用于告警推送时算新图标 */
const facilityStateMap = new Map<number, { facilityType: string; status: string }>()

/**
 * 载入设施点位图层。重复调用先清空旧集合，避免图元重复叠加
 */
export function loadFacilityLayer(facilities: FacilityItem[]): void {
  const scene = getViewer().scene
  removeFacilityLayer()
  billboardCollection = scene.primitives.add(
    new BillboardCollection({ scene })
  ) as BillboardCollection
  labelCollection = scene.primitives.add(new LabelCollection({ scene })) as LabelCollection

  for (const facility of facilities) {
    const position = Cartesian3.fromDegrees(
      facility.lon,
      facility.lat,
      Number(facility.altitude ?? FALLBACK_ALTITUDE)
    )
    const pickId: FacilityPickId = { kind: FACILITY_ID_KIND, facilityId: facility.id }
    const billboard = billboardCollection.add({
      position,
      image: getFacilityIcon(facility.facilityType, facility.status, 0),
      width: ICON_WIDTH,
      height: ICON_HEIGHT,
      verticalOrigin: VerticalOrigin.BOTTOM,
      horizontalOrigin: HorizontalOrigin.CENTER,
      disableDepthTestDistance: DEPTH_TEST_DISTANCE,
      scaleByDistance: new NearFarScalar(
        ICON_SCALE_NEAR,
        ICON_SCALE_NEAR_VALUE,
        ICON_SCALE_FAR,
        ICON_SCALE_FAR_VALUE
      ),
      id: pickId
    })
    billboardMap.set(facility.id, billboard)
    facilityStateMap.set(facility.id, {
      facilityType: facility.facilityType,
      status: facility.status
    })
    if (!LABELED_TYPES.has(facility.facilityType)) {
      continue
    }
    const label = labelCollection.add({
      position,
      text: facility.facilityName ?? facility.facilityCode,
      font: LABEL_FONT,
      fillColor: Color.WHITE,
      outlineColor: Color.BLACK,
      outlineWidth: LABEL_OUTLINE_WIDTH,
      style: LabelStyle.FILL_AND_OUTLINE,
      verticalOrigin: VerticalOrigin.TOP,
      horizontalOrigin: HorizontalOrigin.CENTER,
      pixelOffset: LABEL_PIXEL_OFFSET,
      disableDepthTestDistance: DEPTH_TEST_DISTANCE,
      translucencyByDistance: new NearFarScalar(
        LABEL_TRANSLUCENCY_NEAR,
        1,
        LABEL_TRANSLUCENCY_FAR,
        0
      ),
      id: pickId
    })
    labelMap.set(facility.id, label)
  }
}

/**
 * 按告警级别刷新单个设施图标，仅替换 image 属性
 *
 * @param facilityId 设施主键
 * @param alarmLevel 告警级别，0 正常 1 预警 2 告警
 */
export function updateFacilityState(facilityId: number, alarmLevel: number): void {
  const billboard = billboardMap.get(facilityId)
  const state = facilityStateMap.get(facilityId)
  if (billboard === undefined || state === undefined) {
    return
  }
  const icon = getFacilityIcon(state.facilityType, state.status, alarmLevel)
  if (billboard.image !== icon) {
    billboard.image = icon
  }
}

/**
 * 移除设施图层，供重载与卸载时调用
 */
export function removeFacilityLayer(): void {
  const scene = getViewer().scene
  if (billboardCollection !== null && !billboardCollection.isDestroyed()) {
    scene.primitives.remove(billboardCollection)
  }
  if (labelCollection !== null && !labelCollection.isDestroyed()) {
    scene.primitives.remove(labelCollection)
  }
  billboardCollection = null
  labelCollection = null
  billboardMap.clear()
  labelMap.clear()
  facilityStateMap.clear()
}

/**
 * 判断拾取结果是否为设施点位
 */
export function isFacilityId(id: unknown): id is FacilityPickId {
  return (
    typeof id === 'object' &&
    id !== null &&
    (id as FacilityPickId).kind === FACILITY_ID_KIND &&
    typeof (id as FacilityPickId).facilityId === 'number'
  )
}

/**
 * 当前图层中的点位数量，供验证用
 */
export function getFacilityBillboardCount(): number {
  return billboardMap.size
}

/**
 * 取指定设施的当前图标，供验证图标是否随告警变化
 */
export function getFacilityIconUrl(facilityId: number): string | null {
  const billboard = billboardMap.get(facilityId)
  return billboard === undefined ? null : String(billboard.image)
}
