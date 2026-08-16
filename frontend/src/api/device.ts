import { get } from '@/api/request'

/** 设备台账条目，与 DeviceVO 对齐 */
export interface DeviceItem {
  id: number
  deviceCode: string
  deviceName: string
  deviceType: string
  deviceTypeLabel: string
  buildingId: number
  floor: number | null
  altitude: number
  status: string
  lon: number
  lat: number
}

/** 监测对象类型，与后端 ObjectTypeEnum 一致；deviceId 的语义由它决定 */
export type ObjectType = 'DEVICE' | 'FACILITY'

/** 设备实时值，与 DeviceRealtimeVO 对齐，metrics 键随设备类型而变 */
export interface DeviceRealtime {
  deviceId: number
  objectType: ObjectType
  metrics: Record<string, number>
  alarmLevel: number
  ts: string
}

/** 新增告警推送体，与 AlarmVO 对齐 */
export interface AlarmMessage {
  deviceId: number
  objectType: ObjectType
  alarmType: string
  alarmLevel: number
  alarmValue: Record<string, number>
  status: string
  occurTime: string
}

/**
 * 拉设备列表，buildingId 与 type 均选填
 */
export function fetchDeviceList(params?: {
  buildingId?: number
  type?: string
}): Promise<DeviceItem[]> {
  return get<DeviceItem[]>('/device/list', params)
}

/**
 * 拉单台设备实时值，设备暂无数据时后端返回 404，由拦截器统一提示
 */
export function fetchDeviceRealtime(id: number): Promise<DeviceRealtime> {
  return get<DeviceRealtime>(`/device/${id}/realtime`)
}
