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

import type { DeviceItem } from '@/api/device'
import { getDeviceIcon } from '@/utils/deviceIcon'
import { getViewer } from '@/utils/viewer'

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

/** 标签随距离淡出：1500 m 内全不透明，4000 m 外完全透明。250 个标签常显会糊屏 */
const LABEL_TRANSLUCENCY_NEAR = 1500
const LABEL_TRANSLUCENCY_FAR = 4000

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
 * 按告警级别刷新单台设备图标，仅替换 image 属性
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
  const icon = getDeviceIcon(state.deviceType, state.status, alarmLevel)
  if (billboard.image !== icon) {
    billboard.image = icon
  }
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
