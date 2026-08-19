import {
  BillboardCollection,
  Cartesian2,
  Cartesian3,
  Color,
  DistanceDisplayCondition,
  HorizontalOrigin,
  LabelCollection,
  LabelStyle,
  NearFarScalar,
  VerticalOrigin,
  type Billboard,
  type Label
} from 'cesium'

import type { DeviceItem } from '@/api/device'
import { getDeviceIcon, getDeviceSelectedIcon } from '@/utils/deviceIcon'
import { getViewer } from '@/utils/cesium/viewer'

/** 拾取标识的种类，用于与建筑分支区分 */
const DEVICE_ID_KIND = 'device'

/** billboard.id 的结构，pick 后据此分发 */
export interface DevicePickId {
  kind: typeof DEVICE_ID_KIND
  deviceId: number
}

/** 图标显示边长，与 deviceIcon 的画布尺寸解耦 */
const ICON_WIDTH = 28

/** 图标显示高度 */
const ICON_HEIGHT = 28

/** 关闭深度检测的距离阈值，设为极大值即始终不被楼体遮挡 */
const NO_DEPTH_TEST_DISTANCE = Number.POSITIVE_INFINITY

/** 图标随距离缩放：近处原尺寸，远处缩到 0.5 */
const ICON_SCALE_NEAR = 800
const ICON_SCALE_NEAR_VALUE = 1
const ICON_SCALE_FAR = 6000
const ICON_SCALE_FAR_VALUE = 0.5

/**
 * 标签随距离淡出：400 m 内全不透明，1200 m 外完全透明。
 * 早先 1500/4000 是按 250 台标定的，设备扩到 2000 台后该区间在初始 4200 m 视角下
 * 会让近千个标签同屏常显，文字互相压盖且拖帧，故整体收紧到能读清的距离再显示
 */
const LABEL_TRANSLUCENCY_NEAR = 400
const LABEL_TRANSLUCENCY_FAR = 1200

/**
 * 标签显示距离硬上限（米）：超出即完全不参与渲染。
 * translucencyByDistance 只是把 alpha 降到 0，标签仍会走布局与文字纹理开销；
 * 2000 台规模下必须再加 distanceDisplayCondition 才能真正把远处标签摘掉
 */
const LABEL_VISIBLE_MAX_DISTANCE = 1200

/** 标签显示距离下限，0 表示近处不设限 */
const LABEL_VISIBLE_MIN_DISTANCE = 0

/** 标签字体 */
const LABEL_FONT = '12px sans-serif'

/** 标签描边宽度 */
const LABEL_OUTLINE_WIDTH = 2

/** 标签相对图标的像素偏移，压在图标下方 */
const LABEL_PIXEL_OFFSET = new Cartesian2(0, 18)

/** 安装高度缺省值 */
const FALLBACK_ALTITUDE = 0

/** 图层集合与索引，updateDeviceState 只改 billboard.image，不 remove/re-add */
let billboardCollection: BillboardCollection | null = null
let labelCollection: LabelCollection | null = null
const billboardMap = new Map<number, Billboard>()
const labelMap = new Map<number, Label>()

/** 设备当前状态缓存，用于告警推送时算新图标 */
const deviceStateMap = new Map<number, { deviceType: string; status: string }>()

/** 当前高亮的设备 billboard 与其被覆盖前的图标，清除时原样还原 */
let highlightedBillboard: Billboard | null = null
let previousDeviceImage: string | null = null

/**
 * 载入设备点位图层。重复调用先清空旧集合，避免图元重复叠加
 */
export function loadDeviceLayer(devices: DeviceItem[]): void {
  const scene = getViewer().scene
  removeDeviceLayer()
  billboardCollection = scene.primitives.add(new BillboardCollection({ scene })) as BillboardCollection
  labelCollection = scene.primitives.add(new LabelCollection({ scene })) as LabelCollection

  for (const device of devices) {
    const position = Cartesian3.fromDegrees(
      device.lon,
      device.lat,
      Number(device.altitude ?? FALLBACK_ALTITUDE)
    )
    const pickId: DevicePickId = { kind: DEVICE_ID_KIND, deviceId: device.id }
    const billboard = billboardCollection.add({
      position,
      image: getDeviceIcon(device.deviceType, device.status, 0),
      width: ICON_WIDTH,
      height: ICON_HEIGHT,
      verticalOrigin: VerticalOrigin.BOTTOM,
      horizontalOrigin: HorizontalOrigin.CENTER,
      disableDepthTestDistance: NO_DEPTH_TEST_DISTANCE,
      scaleByDistance: new NearFarScalar(
        ICON_SCALE_NEAR,
        ICON_SCALE_NEAR_VALUE,
        ICON_SCALE_FAR,
        ICON_SCALE_FAR_VALUE
      ),
      id: pickId
    })
    const label = labelCollection.add({
      position,
      text: device.deviceCode,
      font: LABEL_FONT,
      fillColor: Color.WHITE,
      outlineColor: Color.BLACK,
      outlineWidth: LABEL_OUTLINE_WIDTH,
      style: LabelStyle.FILL_AND_OUTLINE,
      verticalOrigin: VerticalOrigin.TOP,
      horizontalOrigin: HorizontalOrigin.CENTER,
      pixelOffset: LABEL_PIXEL_OFFSET,
      disableDepthTestDistance: NO_DEPTH_TEST_DISTANCE,
      // 远处标签直接不参与渲染，仅靠 translucency 降 alpha 省不掉文字布局开销
      distanceDisplayCondition: new DistanceDisplayCondition(
        LABEL_VISIBLE_MIN_DISTANCE,
        LABEL_VISIBLE_MAX_DISTANCE
      ),
      translucencyByDistance: new NearFarScalar(
        LABEL_TRANSLUCENCY_NEAR,
        1,
        LABEL_TRANSLUCENCY_FAR,
        0
      ),
      id: pickId
    })
    billboardMap.set(device.id, billboard)
    labelMap.set(device.id, label)
    deviceStateMap.set(device.id, { deviceType: device.deviceType, status: device.status })
  }
}

/**
 * 按告警级别刷新单台设备图标，仅替换 image 属性。
 * 正在高亮的设备保持选中态图标，避免推送覆盖选中视觉
 *
 * @param deviceId 设备主键
 * @param alarmLevel 告警级别，0 正常 1 预警 2 告警
 */
export function updateDeviceState(deviceId: number, alarmLevel: number): void {
  const billboard = billboardMap.get(deviceId)
  const state = deviceStateMap.get(deviceId)
  if (billboard === undefined || state === undefined) {
    return
  }
  const icon =
    billboard === highlightedBillboard
      ? getDeviceSelectedIcon(state.deviceType, state.status, alarmLevel)
      : getDeviceIcon(state.deviceType, state.status, alarmLevel)
  if (billboard.image !== icon) {
    billboard.image = icon
  }
}

/**
 * 高亮指定设备：仅换 image 为选中态图标，不重建图元
 */
export function highlightDevice(deviceId: number): void {
  clearDeviceHighlight()
  const billboard = billboardMap.get(deviceId)
  const state = deviceStateMap.get(deviceId)
  if (billboard === undefined || state === undefined) {
    return
  }
  previousDeviceImage = String(billboard.image)
  billboard.image = getDeviceSelectedIcon(state.deviceType, state.status, 0)
  highlightedBillboard = billboard
}

/**
 * 还原设备高亮前的图标
 */
export function clearDeviceHighlight(): void {
  if (highlightedBillboard === null) {
    return
  }
  if (previousDeviceImage !== null) {
    highlightedBillboard.image = previousDeviceImage
  }
  highlightedBillboard = null
  previousDeviceImage = null
}

/**
 * 移除设备图层，供重载与卸载时调用
 */
export function removeDeviceLayer(): void {
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
  deviceStateMap.clear()
  highlightedBillboard = null
  previousDeviceImage = null
}

/**
 * 判断拾取结果是否为设备点位
 */
export function isDeviceId(id: unknown): id is DevicePickId {
  return (
    typeof id === 'object' &&
    id !== null &&
    (id as DevicePickId).kind === DEVICE_ID_KIND &&
    typeof (id as DevicePickId).deviceId === 'number'
  )
}

/**
 * 当前图层中的点位数量，供验证用
 */
export function getDeviceBillboardCount(): number {
  return billboardMap.size
}

/**
 * 取指定设备的当前图标，供验证图标是否随告警变化
 */
export function getDeviceIconUrl(deviceId: number): string | null {
  const billboard = billboardMap.get(deviceId)
  return billboard === undefined ? null : String(billboard.image)
}
