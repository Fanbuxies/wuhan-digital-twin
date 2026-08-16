import { get } from '@/api/request'
import type { DeviceRealtime } from '@/api/device'

/** 市政设施台账条目，与 FacilityVO 对齐 */
export interface FacilityItem {
  id: number
  facilityCode: string
  facilityName: string | null
  facilityType: string
  facilityTypeLabel: string
  altitude: number
  status: string
  lon: number
  lat: number
}

/**
 * 拉设施列表，type 与 bbox 均选填
 */
export function fetchFacilityList(params?: {
  type?: string
  bbox?: string
}): Promise<FacilityItem[]> {
  return get<FacilityItem[]>('/facility/list', params)
}

/**
 * 拉单个设施实时值。与设备共用实时表与 VO，靠 objectType 区分
 */
export function fetchFacilityRealtime(id: number): Promise<DeviceRealtime> {
  return get<DeviceRealtime>(`/facility/${id}/realtime`)
}
