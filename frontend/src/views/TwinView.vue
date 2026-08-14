<script setup lang="ts">
import {
  Cartesian3,
  defined,
  Entity,
  Math as CesiumMath,
  ScreenSpaceEventType,
  type GeoJsonDataSource,
  type ScreenSpaceEventHandler
} from 'cesium'
import { ElMessage } from 'element-plus'
import { onBeforeUnmount, onMounted, computed, ref } from 'vue'

import { fetchBuildingGeoJson, fetchTilesetInfo } from '@/api/building'
import BuildingPanel from '@/components/BuildingPanel.vue'
import DevicePanel from '@/components/DevicePanel.vue'
import { useBuildingStore } from '@/stores/building'
import { useDeviceStore } from '@/stores/device'
import {
  clearHighlight,
  highlightBuilding,
  isBuildingEntity,
  loadBuildingLayer
} from '@/utils/buildingLayer'
import { isDeviceId, loadDeviceLayer, removeDeviceLayer } from '@/utils/deviceLayer'
import { connectRealtime, disconnectRealtime } from '@/utils/realtimeSocket'
import { createViewer, destroyViewer, getEventHandler, getViewer } from '@/utils/viewer'

const viewerContainer = ref<HTMLDivElement | null>(null)
const buildingStore = useBuildingStore()
const deviceStore = useDeviceStore()

/** 推送连接状态的中文说明 */
const REALTIME_STATUS_LABELS: Readonly<Record<string, string>> = {
  CONNECTING: '连接中',
  OPEN: '已连接',
  CLOSED: '已断开'
}

const realtimeStatusLabel = computed(
  () => REALTIME_STATUS_LABELS[deviceStore.realtimeStatus] ?? '未知'
)

/** 场景加载中标记 */
const sceneLoading = ref(true)

/** 已渲染建筑数量，用于自查数据是否完整 */
const buildingCount = ref(0)

/** 当前底座数据源模式说明 */
const sourceMode = ref('')

let buildingDataSource: GeoJsonDataSource | null = null

/**
 * 按后端下发的相机参数定位初始视角
 */
async function initCamera(): Promise<void> {
  const tilesetInfo = await fetchTilesetInfo()
  const { camera } = tilesetInfo
  getViewer().camera.setView({
    destination: Cartesian3.fromDegrees(camera.lon, camera.lat, camera.height),
    orientation: {
      heading: CesiumMath.toRadians(camera.heading),
      pitch: CesiumMath.toRadians(camera.pitch),
      roll: 0
    }
  })
  sourceMode.value =
    tilesetInfo.tilesetUrl === null ? 'GeoJSON 拉伸白模（3D Tiles 未生成）' : '3D Tiles'
}

/**
 * 载入建筑白模图层
 */
async function initBuildingLayer(): Promise<void> {
  const featureCollection = await fetchBuildingGeoJson()
  buildingDataSource = await loadBuildingLayer(featureCollection)
  await getViewer().dataSources.add(buildingDataSource)
  buildingCount.value = featureCollection.features.length
}

/**
 * 载入设备点位图层，用 Primitive 集合承载，250 个点位不逐个建 Entity
 */
async function initDeviceLayer(): Promise<void> {
  const devices = await deviceStore.loadDevices()
  loadDeviceLayer(devices)
}

/**
 * 接入实时推送，指标更新只改图标，不重建图元
 */
function initRealtime(): void {
  connectRealtime({
    onDeviceUpdate: (list) => deviceStore.applyDeviceUpdate(list),
    onAlarmNew: (alarm) => deviceStore.applyAlarmNew(alarm),
    onStatusChange: (status) => deviceStore.setRealtimeStatus(status)
  })
}

/**
 * 全局唯一事件句柄的分发入口，按拾取对象类型走不同分支
 */
function registerPickHandler(): void {
  const handler: ScreenSpaceEventHandler = getEventHandler()
  handler.setInputAction((movement: ScreenSpaceEventHandler.PositionedEvent) => {
    const picked = getViewer().scene.pick(movement.position)
    // 设备点位在建筑之上，故先判设备分支
    if (defined(picked) && isDeviceId(picked.id)) {
      clearHighlight()
      buildingStore.clearDetail()
      void deviceStore.selectDevice(picked.id.deviceId)
      return
    }
    if (defined(picked) && picked.id instanceof Entity && isBuildingEntity(picked.id)) {
      deviceStore.clearSelection()
      highlightBuilding(picked.id)
      void buildingStore.loadDetail(Number(picked.id.id))
      return
    }
    // 点空白处视为取消选中
    clearHighlight()
    buildingStore.clearDetail()
    deviceStore.clearSelection()
  }, ScreenSpaceEventType.LEFT_CLICK)
}

function handlePanelClose(): void {
  clearHighlight()
  buildingStore.clearDetail()
}

function handleDevicePanelClose(): void {
  deviceStore.clearSelection()
}

onMounted(async () => {
  if (viewerContainer.value === null) {
    return
  }
  createViewer(viewerContainer.value)
  registerPickHandler()
  try {
    await initCamera()
    await initBuildingLayer()
    await initDeviceLayer()
    initRealtime()
  } catch {
    // 具体错误已由请求拦截器提示，此处补充场景级说明
    ElMessage.error('三维底座数据载入失败')
  } finally {
    sceneLoading.value = false
  }
})

onBeforeUnmount(() => {
  // 先断推送再销毁 Viewer，避免回调里访问已销毁的图元
  disconnectRealtime()
  removeDeviceLayer()
  buildingDataSource = null
  destroyViewer()
})
</script>

<template>
  <div class="twin-view">
    <div ref="viewerContainer" class="viewer-container" />
    <el-card class="scene-info" shadow="hover" body-style="padding: 12px 16px">
      <div class="info-line">数据源：{{ sourceMode || '加载中' }}</div>
      <div class="info-line">建筑数：{{ buildingCount }}</div>
      <div class="info-line">
        设备数：{{ deviceStore.deviceCount }} / 在线 {{ deviceStore.onlineCount }}
      </div>
      <div class="info-line">告警数：{{ deviceStore.alarmCount }}</div>
      <div class="info-line">推送状态：{{ realtimeStatusLabel }}</div>
      <div class="info-tip">单击建筑或设备查看属性，单击空白处取消选中</div>
    </el-card>
    <BuildingPanel
      :detail="buildingStore.detail"
      :loading="buildingStore.loading"
      @close="handlePanelClose"
    />
    <DevicePanel
      :device="deviceStore.selectedDevice"
      :realtime="deviceStore.selectedRealtime"
      :loading="deviceStore.loading"
      @close="handleDevicePanelClose"
    />
    <div v-if="sceneLoading" class="scene-loading" v-loading="true" />
  </div>
</template>

<style scoped>
.twin-view {
  position: relative;
  width: 100%;
  height: 100%;
}

.viewer-container {
  width: 100%;
  height: 100%;
}

.scene-info {
  position: absolute;
  top: 16px;
  left: 16px;
  width: 260px;
  z-index: 10;
}

.info-line {
  font-size: 13px;
  line-height: 22px;
}

.info-tip {
  margin-top: 6px;
  font-size: 12px;
  color: #909399;
}

.scene-loading {
  position: absolute;
  inset: 0;
  z-index: 20;
}
</style>
