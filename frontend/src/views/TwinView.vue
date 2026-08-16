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

import { fetchBuildingGeoJson, fetchTilesetInfo, type TilesetInfo } from '@/api/building'
import AdminDrawer from '@/components/AdminDrawer.vue'
import BuildingPanel from '@/components/BuildingPanel.vue'
import DevicePanel from '@/components/DevicePanel.vue'
import FacilityPanel from '@/components/FacilityPanel.vue'
import { useBuildingStore } from '@/stores/building'
import { useDeviceStore } from '@/stores/device'
import { useFacilityStore } from '@/stores/facility'
import {
  clearHighlight,
  highlightBuilding,
  isBuildingEntity,
  loadBuildingLayer
} from '@/utils/buildingLayer'
import {
  clearDeviceHighlight,
  highlightDevice,
  isDeviceId,
  loadDeviceLayer,
  removeDeviceLayer
} from '@/utils/deviceLayer'
import { DEVICE_TYPE_META, getDeviceIcon } from '@/utils/deviceIcon'
import { isFacilityId, loadFacilityLayer, removeFacilityLayer } from '@/utils/facilityLayer'
import { connectRealtime, disconnectRealtime } from '@/utils/realtimeSocket'
import {
  clearTilesetHighlight,
  getBuildingIdFromFeature,
  highlightBuildingById,
  highlightBuildingFeature,
  isBuildingFeature,
  loadBuildingTileset,
  removeBuildingTileset
} from '@/utils/tilesetLayer'
import {
  createViewer,
  destroyViewer,
  flyToDestination,
  getEventHandler,
  getViewer
} from '@/utils/viewer'
import { useAdminStore, type AdminTab } from '@/stores/admin'

const viewerContainer = ref<HTMLDivElement | null>(null)
const buildingStore = useBuildingStore()
const deviceStore = useDeviceStore()
const facilityStore = useFacilityStore()
const adminStore = useAdminStore()

/** 管理列表点行后的飞行参数：建筑取建议区间中值 400m，设备 200m，设施与设备同量级，过渡 1.5s */
const BUILDING_FLY_HEIGHT = 400
const DEVICE_FLY_HEIGHT = 200
const FACILITY_FLY_HEIGHT = 200
const FLY_DURATION = 1.5

/** 推送连接状态的中文说明 */
const REALTIME_STATUS_LABELS: Readonly<Record<string, string>> = {
  CONNECTING: '连接中',
  OPEN: '已连接',
  CLOSED: '已断开'
}

/**
 * 图例两段：类型图例直接消费设备类型登记表，状态图例以烟感为代表设备。
 * 图标在 setup 时预生成并命中缓存，模板只取 dataURL
 */
const legendSections: ReadonlyArray<{
  title: string
  items: ReadonlyArray<{ key: string; icon: string; label: string }>
}> = [
  {
    title: '设备类型',
    items: DEVICE_TYPE_META.map((meta) => ({
      key: meta.type,
      icon: getDeviceIcon(meta.type, 'ONLINE', 0),
      label: meta.label
    }))
  },
  {
    title: '设备状态',
    items: [
      { status: 'OFFLINE', level: 0, label: '离线' },
      { status: 'FAULT', level: 0, label: '故障' },
      { status: 'ONLINE', level: 1, label: '预警' },
      { status: 'ONLINE', level: 2, label: '告警' }
    ].map((item) => ({
      key: `${item.status}_${item.level}`,
      icon: getDeviceIcon('SMOKE', item.status, item.level),
      label: item.label
    }))
  }
]

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
 * 按后端下发的相机参数定位初始视角，并记录当前底座数据源模式
 */
function applyCamera(tilesetInfo: TilesetInfo): void {
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
 * 载入建筑图层：3D Tiles 就绪走 tileset，未生成走 GeoJSON 拉伸降级
 */
async function initBuildingLayer(tilesetInfo: TilesetInfo): Promise<void> {
  buildingCount.value = tilesetInfo.buildingCount
  if (tilesetInfo.tilesetUrl !== null) {
    await loadBuildingTileset(tilesetInfo.tilesetUrl)
    return
  }
  const featureCollection = await fetchBuildingGeoJson()
  buildingDataSource = await loadBuildingLayer(featureCollection)
  await getViewer().dataSources.add(buildingDataSource)
  buildingCount.value = featureCollection.features.length
}

/**
 * 同时还原两条建筑路径的高亮（tileset 要素与 GeoJSON 实体）
 */
function clearAllHighlight(): void {
  clearHighlight()
  clearTilesetHighlight()
}

/**
 * 载入设备点位图层，用 Primitive 集合承载，250 个点位不逐个建 Entity
 */
async function initDeviceLayer(): Promise<void> {
  const devices = await deviceStore.loadDevices()
  loadDeviceLayer(devices)
}

/**
 * 载入市政设施点位图层，同样走 Primitive 集合，千级点位不逐个建 Entity
 */
async function initFacilityLayer(): Promise<void> {
  const facilities = await facilityStore.loadFacilities()
  loadFacilityLayer(facilities)
}

/**
 * 接入实时推送，指标更新只改图标，不重建图元
 */
function initRealtime(): void {
  connectRealtime({
    onDeviceUpdate: (list) => deviceStore.applyDeviceUpdate(list),
    onFacilityUpdate: (list) => facilityStore.applyFacilityUpdate(list),
    onAlarmNew: (alarm) => {
      // 告警走同一推送类型，按监测对象类型交给对应 store
      if (alarm.objectType === 'FACILITY') {
        facilityStore.applyAlarmNew(alarm)
        return
      }
      deviceStore.applyAlarmNew(alarm)
    },
    onStatusChange: (status) => deviceStore.setRealtimeStatus(status)
  })
}

/**
 * 管理抽屉行点击联动：飞行定位 + 高亮 + 打开对应详情面板。
 * 设施行与地图点选行为一致：飞行 + 弹面板，无图标高亮（设施层无选中态）
 */
function handleAdminRowClick(tab: AdminTab, id: number, lon: number, lat: number): void {
  if (tab === 'building') {
    flyToDestination(lon, lat, BUILDING_FLY_HEIGHT, FLY_DURATION)
    void highlightBuildingById(id)
    void buildingStore.loadDetail(id)
    return
  }
  if (tab === 'device') {
    flyToDestination(lon, lat, DEVICE_FLY_HEIGHT, FLY_DURATION)
    highlightDevice(id)
    void deviceStore.selectDevice(id)
    return
  }
  flyToDestination(lon, lat, FACILITY_FLY_HEIGHT, FLY_DURATION)
  buildingStore.clearDetail()
  void facilityStore.selectFacility(id)
}

/**
 * 全局唯一事件句柄的分发入口，按拾取对象类型走不同分支
 */
function registerPickHandler(): void {
  const handler: ScreenSpaceEventHandler = getEventHandler()
  handler.setInputAction((movement: ScreenSpaceEventHandler.PositionedEvent) => {
    // 地图点击接管选择，先清掉管理列表联动残留的设备高亮
    clearDeviceHighlight()
    const picked = getViewer().scene.pick(movement.position)
    // 设备点位在建筑之上，故先判设备分支
    if (defined(picked) && isDeviceId(picked.id)) {
      clearAllHighlight()
      buildingStore.clearDetail()
      facilityStore.clearSelection()
      void deviceStore.selectDevice(picked.id.deviceId)
      return
    }
    // 设施在地面、会被楼体遮挡，但图元仍先于建筑面判定
    if (defined(picked) && isFacilityId(picked.id)) {
      clearAllHighlight()
      buildingStore.clearDetail()
      deviceStore.clearSelection()
      void facilityStore.selectFacility(picked.id.facilityId)
      return
    }
    // 3D Tiles 建筑分支：拾取到的是 Cesium3DTileFeature，与 Entity 降级路径类型不同
    if (defined(picked) && isBuildingFeature(picked)) {
      deviceStore.clearSelection()
      facilityStore.clearSelection()
      highlightBuildingFeature(picked)
      const buildingId = getBuildingIdFromFeature(picked)
      if (buildingId !== null) {
        void buildingStore.loadDetail(buildingId)
      }
      return
    }
    // GeoJSON 降级路径的建筑分支
    if (defined(picked) && picked.id instanceof Entity && isBuildingEntity(picked.id)) {
      deviceStore.clearSelection()
      facilityStore.clearSelection()
      highlightBuilding(picked.id)
      void buildingStore.loadDetail(Number(picked.id.id))
      return
    }
    // 点空白处视为取消选中
    clearAllHighlight()
    buildingStore.clearDetail()
    deviceStore.clearSelection()
    facilityStore.clearSelection()
  }, ScreenSpaceEventType.LEFT_CLICK)
}

/**
 * 管理抽屉写操作后的联动：设备增删改后重载点位图层并刷新选中面板；
 * 建筑删除后关闭对应详情浮层与高亮
 */
async function handleAdminDataChanged(payload: {
  tab: 'building' | 'device'
  deletedId: number | null
}): Promise<void> {
  if (payload.tab === 'device') {
    const devices = await deviceStore.loadDevices()
    loadDeviceLayer(devices)
    const selectedId = deviceStore.selectedDevice?.id
    if (selectedId === undefined) {
      return
    }
    const fresh = devices.find((item) => item.id === selectedId)
    if (fresh === undefined) {
      // 选中的设备已被删除，面板与高亮一并清掉
      clearDeviceHighlight()
      deviceStore.clearSelection()
      return
    }
    // 编辑场景：用最新台账刷新面板展示
    await deviceStore.selectDevice(fresh.id)
    return
  }
  if (payload.deletedId !== null && buildingStore.detail?.id === payload.deletedId) {
    clearTilesetHighlight()
    buildingStore.clearDetail()
  }
}

function handlePanelClose(): void {
  clearAllHighlight()
  buildingStore.clearDetail()
}

function handleDevicePanelClose(): void {
  clearDeviceHighlight()
  deviceStore.clearSelection()
}

function handleFacilityPanelClose(): void {
  facilityStore.clearSelection()
}

onMounted(async () => {
  if (viewerContainer.value === null) {
    return
  }
  createViewer(viewerContainer.value)
  registerPickHandler()
  try {
    const tilesetInfo = await fetchTilesetInfo()
    applyCamera(tilesetInfo)
    await initBuildingLayer(tilesetInfo)
    await initDeviceLayer()
    await initFacilityLayer()
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
  removeFacilityLayer()
  removeBuildingTileset()
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
      <div class="info-line">
        设施数：{{ facilityStore.facilityCount }} / 在线 {{ facilityStore.onlineCount }}
      </div>
      <div class="info-line">
        告警数：设备 {{ deviceStore.alarmCount }} / 设施 {{ facilityStore.alarmCount }}
      </div>
      <div class="info-line">推送状态：{{ realtimeStatusLabel }}</div>
      <div class="legend-block">
        <template v-for="section in legendSections" :key="section.title">
          <div class="legend-title">{{ section.title }}</div>
          <div class="legend-grid">
            <span v-for="item in section.items" :key="item.key" class="legend-item">
              <img class="legend-icon" :src="item.icon" alt="" />
              {{ item.label }}
            </span>
          </div>
        </template>
      </div>
      <div class="info-tip">单击建筑、设备或设施查看属性，单击空白处取消选中</div>
    </el-card>
    <div class="detail-panels" :class="{ 'drawer-open': adminStore.open }">
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
      <FacilityPanel
        :facility="facilityStore.selectedFacility"
        :realtime="facilityStore.selectedRealtime"
        :loading="facilityStore.loading"
        @close="handleFacilityPanelClose"
      />
    </div>
    <AdminDrawer
      @row-click="handleAdminRowClick"
      @data-changed="handleAdminDataChanged"
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

/* 三个详情浮层在管理抽屉展开时左移让位（440px 抽屉 + 16px 边距） */
.detail-panels :deep(.building-panel),
.detail-panels :deep(.device-panel),
.detail-panels :deep(.facility-panel) {
  transition: right 0.3s ease;
}

.detail-panels.drawer-open :deep(.building-panel),
.detail-panels.drawer-open :deep(.device-panel),
.detail-panels.drawer-open :deep(.facility-panel) {
  right: 456px;
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

.legend-block {
  margin-top: 8px;
  border-top: 1px solid #ebeef5;
  padding-top: 6px;
}

.legend-title {
  font-size: 12px;
  color: #909399;
  line-height: 20px;
}

.legend-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 2px 10px;
  padding-bottom: 4px;
}

.legend-item {
  display: inline-flex;
  align-items: center;
  font-size: 12px;
  color: #606266;
}

.legend-icon {
  width: 16px;
  height: 16px;
  margin-right: 3px;
  flex: none;
}

.scene-loading {
  position: absolute;
  inset: 0;
  z-index: 20;
}
</style>
