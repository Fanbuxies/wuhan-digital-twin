import { del, get, post, put } from '@/api/request'

/**
 * 后端分页结果，与 com.wuhan.twin.common.result.PageResult 对齐
 */
export interface PageResult<T> {
  records: T[]
  total: number
  current: number
  size: number
}

/** 建筑分页记录，与 BuildingPageVO 对齐 */
export interface BuildingPageItem {
  id: number
  name: string | null
  buildingType: string | null
  levels: number | null
  height: number | null
  heightSource: string | null
  lon: number
  lat: number
}

/** 设备分页记录，与 DevicePageVO 对齐 */
export interface DevicePageItem {
  id: number
  deviceCode: string
  deviceName: string
  deviceType: string
  deviceTypeLabel: string | null
  buildingId: number | null
  floor: number | null
  altitude: number | null
  status: string
  lon: number
  lat: number
}

/** 建筑分页参数，type 别名以便获得隐式索引签名（接口没有） */
export type BuildingPageParams = {
  current: number
  size: number
  keyword?: string
  buildingType?: string
}

/** 设备分页参数 */
export type DevicePageParams = {
  current: number
  size: number
  keyword?: string
  deviceType?: string
  status?: string
  buildingId?: number
}

/** 设施分页记录，与 FacilityPageVO 对齐（字段与 FacilityItem 一致） */
export interface FacilityPageItem {
  id: number
  facilityCode: string
  facilityName: string | null
  facilityType: string
  facilityTypeLabel: string | null
  altitude: number | null
  status: string
  lon: number
  lat: number
}

/** 设施分页参数 */
export type FacilityPageParams = {
  current: number
  size: number
  keyword?: string
  facilityType?: string
  status?: string
}

/** 建筑新增/编辑入参，与 BuildingSaveDTO 对齐；可选字段允许传 null */
export interface BuildingSavePayload {
  name: string | null
  buildingType: string | null
  levels: number | null
  height: number
  heightSource: string
  lon: number
  lat: number
}

/** 设备新增/编辑入参，与 DeviceSaveDTO 对齐；可选字段允许传 null */
export interface DeviceSavePayload {
  deviceCode: string
  deviceName: string | null
  deviceType: string
  buildingId: number
  floor: number | null
  altitude: number | null
  status: string
  lon: number
  lat: number
}

/**
 * 拉建筑分页，keyword 按名称模糊匹配
 */
export function fetchBuildingPage(params: BuildingPageParams): Promise<PageResult<BuildingPageItem>> {
  return get<PageResult<BuildingPageItem>>('/building/page', params)
}

/**
 * 拉设备分页，keyword 按名称或编号模糊匹配
 */
export function fetchDevicePage(params: DevicePageParams): Promise<PageResult<DevicePageItem>> {
  return get<PageResult<DevicePageItem>>('/device/page', params)
}

/**
 * 拉设施分页，keyword 按名称或编号模糊匹配
 */
export function fetchFacilityPage(params: FacilityPageParams): Promise<PageResult<FacilityPageItem>> {
  return get<PageResult<FacilityPageItem>>('/facility/page', params)
}

/**
 * 新增建筑，返回新建筑主键
 */
export function createBuilding(payload: BuildingSavePayload): Promise<number> {
  return post<number>('/building', payload)
}

/**
 * 编辑建筑，整体更新表单可编辑字段
 */
export function updateBuilding(id: number, payload: BuildingSavePayload): Promise<void> {
  return put<void>(`/building/${id}`, payload)
}

/**
 * 删除建筑，建筑下仍有设备时后端拒绝
 */
export function deleteBuilding(id: number): Promise<void> {
  return del<void>(`/building/${id}`)
}

/**
 * 新增设备，返回新设备主键
 */
export function createDevice(payload: DeviceSavePayload): Promise<number> {
  return post<number>('/device', payload)
}

/**
 * 编辑设备，整体更新表单可编辑字段
 */
export function updateDevice(id: number, payload: DeviceSavePayload): Promise<void> {
  return put<void>(`/device/${id}`, payload)
}

/**
 * 删除设备，后端事务内清理实时/告警/遥测关联数据
 */
export function deleteDevice(id: number): Promise<void> {
  return del<void>(`/device/${id}`)
}
