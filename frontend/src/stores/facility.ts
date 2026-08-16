import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import type { AlarmMessage, DeviceRealtime } from '@/api/device'
import { fetchFacilityList, fetchFacilityRealtime, type FacilityItem } from '@/api/facility'
import { updateFacilityState } from '@/utils/facilityLayer'

/** 在线状态标识 */
const STATUS_ONLINE = 'ONLINE'

/** 告警级别下限，超过即刷新图标 */
const ALARM_LEVEL_THRESHOLD = 0

/** 最近告警保留条数，仅供状态卡展示 */
const RECENT_ALARM_LIMIT = 20

export const useFacilityStore = defineStore('facility', () => {
  /** 设施台账 */
  const facilities = ref<FacilityItem[]>([])

  /** 设施实时值，键为设施主键 */
  const realtimeMap = ref(new Map<number, DeviceRealtime>())

  /** 当前选中设施 */
  const selectedFacility = ref<FacilityItem | null>(null)

  /** 选中设施的实时值，点选时单独拉一次首帧 */
  const selectedRealtime = ref<DeviceRealtime | null>(null)

  /** 详情加载中标记 */
  const loading = ref(false)

  /** 累计收到的设施告警条数 */
  const alarmCount = ref(0)

  /** 最近设施告警，新的插在最前 */
  const recentAlarms = ref<AlarmMessage[]>([])

  const facilityCount = computed(() => facilities.value.length)

  const onlineCount = computed(
    () => facilities.value.filter((item) => item.status === STATUS_ONLINE).length
  )

  /**
   * 拉设施台账，失败时清空以免图层渲染半截数据
   */
  async function loadFacilities(): Promise<FacilityItem[]> {
    try {
      facilities.value = await fetchFacilityList()
    } catch {
      // 错误提示已由 request.ts 拦截器统一弹出
      facilities.value = []
    }
    return facilities.value
  }

  /**
   * 应用一批实时值：刷新缓存并按告警级别更新图标
   */
  function applyFacilityUpdate(list: DeviceRealtime[]): void {
    for (const item of list) {
      realtimeMap.value.set(item.deviceId, item)
      updateFacilityState(item.deviceId, item.alarmLevel)
      if (selectedFacility.value?.id === item.deviceId) {
        selectedRealtime.value = item
      }
    }
  }

  /**
   * 应用一条新增设施告警
   */
  function applyAlarmNew(alarm: AlarmMessage): void {
    alarmCount.value += 1
    recentAlarms.value = [alarm, ...recentAlarms.value].slice(0, RECENT_ALARM_LIMIT)
    if (alarm.alarmLevel > ALARM_LEVEL_THRESHOLD) {
      updateFacilityState(alarm.deviceId, alarm.alarmLevel)
    }
  }

  /**
   * 选中设施：台账取自内存，实时值优先用推送缓存，无缓存再请求接口
   */
  async function selectFacility(facilityId: number): Promise<void> {
    const facility = facilities.value.find((entry) => entry.id === facilityId)
    if (facility === undefined) {
      return
    }
    selectedFacility.value = facility
    const cached = realtimeMap.value.get(facilityId)
    if (cached !== undefined) {
      selectedRealtime.value = cached
      return
    }
    loading.value = true
    try {
      selectedRealtime.value = await fetchFacilityRealtime(facilityId)
    } catch {
      // 离线设施无实时数据，后端返回 404，面板只展示台账信息
      selectedRealtime.value = null
    } finally {
      loading.value = false
    }
  }

  function clearSelection(): void {
    selectedFacility.value = null
    selectedRealtime.value = null
  }

  return {
    facilities,
    realtimeMap,
    selectedFacility,
    selectedRealtime,
    loading,
    alarmCount,
    recentAlarms,
    facilityCount,
    onlineCount,
    loadFacilities,
    applyFacilityUpdate,
    applyAlarmNew,
    selectFacility,
    clearSelection
  }
})
