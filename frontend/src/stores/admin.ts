import { defineStore } from 'pinia'
import { ref } from 'vue'

import {
  fetchBuildingPage,
  fetchDevicePage,
  fetchFacilityPage,
  type BuildingPageItem,
  type DevicePageItem,
  type FacilityPageItem
} from '@/api/admin'

/** 管理端页签：建筑 / 设备 / 设施 */
export type AdminTab = 'building' | 'device' | 'facility'

/** 每页条数，与后端分页默认值一致 */
export const DEFAULT_PAGE_SIZE = 20

export const useAdminStore = defineStore('admin', () => {
  /** 抽屉展开状态 */
  const open = ref(false)

  /** 当前页签 */
  const activeTab = ref<AdminTab>('building')

  /** 建筑列表：记录 / 总数 / 页码 / 关键字 / 类型筛选 / 加载中 */
  const buildingRecords = ref<BuildingPageItem[]>([])
  const buildingTotal = ref(0)
  const buildingCurrent = ref(1)
  const buildingKeyword = ref('')
  const buildingType = ref('')
  const buildingLoading = ref(false)

  /** 设备列表：记录 / 总数 / 页码 / 关键字 / 类型 / 状态筛选 / 加载中 */
  const deviceRecords = ref<DevicePageItem[]>([])
  const deviceTotal = ref(0)
  const deviceCurrent = ref(1)
  const deviceKeyword = ref('')
  const deviceType = ref('')
  const deviceStatus = ref('')
  const deviceLoading = ref(false)

  /** 设施列表：记录 / 总数 / 页码 / 关键字 / 类型 / 状态筛选 / 加载中 */
  const facilityRecords = ref<FacilityPageItem[]>([])
  const facilityTotal = ref(0)
  const facilityCurrent = ref(1)
  const facilityKeyword = ref('')
  const facilityType = ref('')
  const facilityStatus = ref('')
  const facilityLoading = ref(false)

  /**
   * 拉建筑分页，失败时清空列表以免表格残留上一页数据
   */
  async function loadBuildings(): Promise<void> {
    buildingLoading.value = true
    try {
      const page = await fetchBuildingPage({
        current: buildingCurrent.value,
        size: DEFAULT_PAGE_SIZE,
        keyword: emptyToUndefined(buildingKeyword.value),
        buildingType: emptyToUndefined(buildingType.value)
      })
      buildingRecords.value = page.records
      buildingTotal.value = page.total
    } catch {
      // 错误提示已由 request.ts 拦截器统一弹出
      buildingRecords.value = []
      buildingTotal.value = 0
    } finally {
      buildingLoading.value = false
    }
  }

  /**
   * 拉设备分页，失败时清空列表
   */
  async function loadDevices(): Promise<void> {
    deviceLoading.value = true
    try {
      const page = await fetchDevicePage({
        current: deviceCurrent.value,
        size: DEFAULT_PAGE_SIZE,
        keyword: emptyToUndefined(deviceKeyword.value),
        deviceType: emptyToUndefined(deviceType.value),
        status: emptyToUndefined(deviceStatus.value)
      })
      deviceRecords.value = page.records
      deviceTotal.value = page.total
    } catch {
      deviceRecords.value = []
      deviceTotal.value = 0
    } finally {
      deviceLoading.value = false
    }
  }

  /**
   * 拉设施分页，失败时清空列表
   */
  async function loadFacilities(): Promise<void> {
    facilityLoading.value = true
    try {
      const page = await fetchFacilityPage({
        current: facilityCurrent.value,
        size: DEFAULT_PAGE_SIZE,
        keyword: emptyToUndefined(facilityKeyword.value),
        facilityType: emptyToUndefined(facilityType.value),
        status: emptyToUndefined(facilityStatus.value)
      })
      facilityRecords.value = page.records
      facilityTotal.value = page.total
    } catch {
      facilityRecords.value = []
      facilityTotal.value = 0
    } finally {
      facilityLoading.value = false
    }
  }

  return {
    open,
    activeTab,
    buildingRecords,
    buildingTotal,
    buildingCurrent,
    buildingKeyword,
    buildingType,
    buildingLoading,
    deviceRecords,
    deviceTotal,
    deviceCurrent,
    deviceKeyword,
    deviceType,
    deviceStatus,
    deviceLoading,
    facilityRecords,
    facilityTotal,
    facilityCurrent,
    facilityKeyword,
    facilityType,
    facilityStatus,
    facilityLoading,
    loadBuildings,
    loadDevices,
    loadFacilities
  }
})

/**
 * 空白筛选值归一为 undefined，后端按未传处理
 */
function emptyToUndefined(value: string): string | undefined {
  const trimmed = value.trim()
  return trimmed === '' ? undefined : trimmed
}
