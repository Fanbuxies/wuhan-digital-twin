import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import {
  fetchDeviceList,
  fetchDeviceRealtime,
  type AlarmMessage,
  type DeviceItem,
  type DeviceRealtime
} from '@/api/device'
import { updateDeviceState } from '@/utils/cesium/deviceLayer'
import type { RealtimeStatus } from '@/utils/realtimeSocket'

/** 在线状态标识 */
const STATUS_ONLINE = 'ONLINE'

/** 告警级别下限，超过即计入告警数 */
const ALARM_LEVEL_THRESHOLD = 0

/** 最近告警保留条数，仅供状态卡展示，历史列表留给第 6 步 */
const RECENT_ALARM_LIMIT = 20

export const useDeviceStore = defineStore('device', () => {
  /** 设备台账 */
  const devices = ref<DeviceItem[]>([])

  /** 设备实时值，键为设备主键 */
  const realtimeMap = ref(new Map<number, DeviceRealtime>())

  /** 当前选中设备 */
  const selectedDevice = ref<DeviceItem | null>(null)

  /** 选中设备的实时值，点选时单独拉一次首帧 */
  const selectedRealtime = ref<DeviceRealtime | null>(null)

  /** 详情加载中标记 */
  const loading = ref(false)

  /** 累计收到的告警条数 */
  const alarmCount = ref(0)

  /** 最近告警，新的插在最前 */
  const recentAlarms = ref<AlarmMessage[]>([])

  /** 推送连接状态 */
  const realtimeStatus = ref<RealtimeStatus>('CLOSED')

  const deviceCount = computed(() => devices.value.length)

  const onlineCount = computed(
    () => devices.value.filter((item) => item.status === STATUS_ONLINE).length
  )

  /**
   * 拉设备台账，失败时清空以免图层渲染半截数据
   */
  async function loadDevices(): Promise<DeviceItem[]> {
    try {
      devices.value = await fetchDeviceList()
    } catch {
      // 错误提示已由 request.ts 拦截器统一弹出
      devices.value = []
    }
    return devices.value
  }

  /**
   * 应用一批实时值：刷新缓存并按告警级别更新图标
   */
  function applyDeviceUpdate(list: DeviceRealtime[]): void {
    for (const item of list) {
      realtimeMap.value.set(item.deviceId, item)
      updateDeviceState(item.deviceId, item.alarmLevel)
      if (selectedDevice.value?.id === item.deviceId) {
        selectedRealtime.value = item
      }
    }
  }

  /**
   * 应用一条新增告警
   */
  function applyAlarmNew(alarm: AlarmMessage): void {
    alarmCount.value += 1
    recentAlarms.value = [alarm, ...recentAlarms.value].slice(0, RECENT_ALARM_LIMIT)
    if (alarm.alarmLevel > ALARM_LEVEL_THRESHOLD) {
      updateDeviceState(alarm.deviceId, alarm.alarmLevel)
    }
  }

  /**
   * 选中设备：台账取自内存，实时值优先用推送缓存，无缓存再请求接口
   */
  async function selectDevice(deviceId: number): Promise<void> {
    const device = devices.value.find((item) => item.id === deviceId)
    if (device === undefined) {
      return
    }
    selectedDevice.value = device
    const cached = realtimeMap.value.get(deviceId)
    if (cached !== undefined) {
      selectedRealtime.value = cached
      return
    }
    loading.value = true
    try {
      selectedRealtime.value = await fetchDeviceRealtime(deviceId)
    } catch {
      // 离线设备无实时数据，后端返回 404，面板只展示台账信息
      selectedRealtime.value = null
    } finally {
      loading.value = false
    }
  }

  function clearSelection(): void {
    selectedDevice.value = null
    selectedRealtime.value = null
  }

  function setRealtimeStatus(status: RealtimeStatus): void {
    realtimeStatus.value = status
  }

  return {
    devices,
    realtimeMap,
    selectedDevice,
    selectedRealtime,
    loading,
    alarmCount,
    recentAlarms,
    realtimeStatus,
    deviceCount,
    onlineCount,
    loadDevices,
    applyDeviceUpdate,
    applyAlarmNew,
    selectDevice,
    clearSelection,
    setRealtimeStatus
  }
})
