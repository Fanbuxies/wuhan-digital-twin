<script setup lang="ts">
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { reactive, ref, watch } from 'vue'

import {
  createBuilding,
  createDevice,
  deleteBuilding,
  deleteDevice,
  fetchBuildingPage,
  fetchDevicePage,
  fetchFacilityPage,
  updateBuilding,
  updateDevice,
  type BuildingPageItem,
  type DevicePageItem,
  type FacilityPageItem
} from '@/api/admin'
import { fetchBuildingDetail } from '@/api/building'
import {
  DEFAULT_PAGE_SIZE,
  useAdminStore,
  type AdminTab
} from '@/stores/admin'

const store = useAdminStore()

/**
 * 行点击上抛给页面接线飞行定位与高亮，本组件不直接操作场景；
 * 写操作后上抛 dataChanged 通知页面刷新地图图层与详情浮层
 */
const emit = defineEmits<{
  rowClick: [tab: AdminTab, id: number, lon: number, lat: number]
  dataChanged: [payload: { tab: 'building' | 'device'; deletedId: number | null }]
}>()

/** 搜索防抖间隔，连续敲字只发一次请求 */
const SEARCH_DEBOUNCE_MS = 300

/** 抽屉宽度，任务书建议 420~480 */
const DRAWER_WIDTH = '440px'

/** 经纬度保留小数位，约 0.1m 精度，再多没有意义 */
const LON_LAT_DECIMALS = 6

/** OSM 建筑类型到中文名，列表展示与筛选下拉共用 */
const BUILDING_TYPE_LABELS: Readonly<Record<string, string>> = {
  residential: '住宅',
  apartments: '公寓',
  house: '独栋',
  office: '办公',
  commercial: '商业',
  retail: '零售',
  industrial: '工业',
  school: '学校',
  hotel: '酒店',
  hospital: '医院',
  stadium: '体育',
  public: '公共',
  church: '教堂',
  yes: '通用'
}

/** 建筑类型下拉选项，与映射表同源 */
const BUILDING_TYPE_OPTIONS: ReadonlyArray<{ value: string; label: string }> =
  Object.entries(BUILDING_TYPE_LABELS).map(([value, label]) => ({ value, label }))

/** 建筑类型标签主色，表格类型列按类型着色的色板 */
const BUILDING_TYPE_COLORS: Readonly<Record<string, string>> = {
  residential: '#409eff',
  apartments: '#5c6bc0',
  house: '#909399',
  office: '#9c27b0',
  commercial: '#ff9800',
  retail: '#ff7043',
  industrial: '#607d8b',
  school: '#26a69a',
  hotel: '#e91e63',
  hospital: '#ef5350',
  stadium: '#66bb6a',
  public: '#00bcd4',
  church: '#8d6e63',
  yes: '#78909c'
}

/** 设备类型下拉选项，与后端 DeviceTypeEnum 保持一致 */
const DEVICE_TYPE_OPTIONS: ReadonlyArray<{ value: string; label: string }> = [
  { value: 'SMOKE', label: '烟感' },
  { value: 'WATER', label: '水浸' },
  { value: 'TEMP_HUMI', label: '温湿度' },
  { value: 'ELECTRIC', label: '电气' },
  { value: 'CAMERA', label: '摄像头' }
]

/** 设备类型标签主色，与 deviceIcon.ts 的类型色板一致，表格与地图图标同色系 */
const DEVICE_TYPE_COLORS: Readonly<Record<string, string>> = {
  SMOKE: '#8e44ad',
  WATER: '#1e88e5',
  TEMP_HUMI: '#17a2b8',
  ELECTRIC: '#5c6bc0',
  CAMERA: '#d6336c'
}

/** 设施类型下拉选项，与后端 FacilityTypeEnum 保持一致 */
const FACILITY_TYPE_OPTIONS: ReadonlyArray<{ value: string; label: string }> = [
  { value: 'CHARGING_PILE', label: '充电桩' },
  { value: 'STREET_LAMP', label: '路灯' },
  { value: 'MANHOLE', label: '井盖' },
  { value: 'BUS_STOP', label: '公交站' }
]

/** 设施类型标签主色，与 facilityIcon.ts 的类型色板一致 */
const FACILITY_TYPE_COLORS: Readonly<Record<string, string>> = {
  CHARGING_PILE: '#2f9e44',
  STREET_LAMP: '#8d6e63',
  MANHOLE: '#0ca678',
  BUS_STOP: '#845ef7'
}

/** 设备状态到中文与 el-tag 配色，与地图图标语义一致：在线绿 / 离线灰 / 故障橙 */
const STATUS_LABELS: Readonly<Record<string, { text: string; type: 'success' | 'info' | 'warning' }>> = {
  ONLINE: { text: '在线', type: 'success' },
  OFFLINE: { text: '离线', type: 'info' },
  FAULT: { text: '故障', type: 'warning' }
}

/** 设备状态下拉选项 */
const DEVICE_STATUS_OPTIONS: ReadonlyArray<{ value: string; label: string }> = [
  { value: 'ONLINE', label: '在线' },
  { value: 'OFFLINE', label: '离线' },
  { value: 'FAULT', label: '故障' }
]

/** 高度来源下拉选项，与 t_building.height_source 的 CHECK 约束一致 */
const HEIGHT_SOURCE_OPTIONS: ReadonlyArray<{ value: string; label: string }> = [
  { value: 'osm_height', label: 'OSM 高度' },
  { value: 'osm_levels', label: '按层数估算' },
  { value: 'default_by_type', label: '按类型默认值' }
]

/** 经纬度输入精度，与后端 DTO 允许的小数位一致 */
const LON_LAT_PRECISION = 6

/** 建筑高度/设备安装高度输入精度，numeric(6,2) 两位小数 */
const HEIGHT_PRECISION = 2

/** 设备表单所属建筑下拉的远程搜索条数 */
const BUILDING_SEARCH_SIZE = 20

/** 编辑对话框状态：dialogTab 决定渲染哪张表单，editingId 为 null 表示新增 */
const dialogVisible = ref(false)
const dialogTab = ref<'building' | 'device'>('building')
const editingId = ref<number | null>(null)
const saving = ref(false)

/** 建筑表单 */
const buildingFormRef = ref<FormInstance>()
const buildingForm = reactive({
  name: '',
  buildingType: '',
  levels: null as number | null,
  height: null as number | null,
  heightSource: '',
  lon: null as number | null,
  lat: null as number | null
})

/** 设备表单 */
const deviceFormRef = ref<FormInstance>()
const deviceForm = reactive({
  deviceCode: '',
  deviceName: '',
  deviceType: '',
  buildingId: null as number | null,
  floor: null as number | null,
  altitude: null as number | null,
  status: 'ONLINE',
  lon: null as number | null,
  lat: null as number | null
})

/** 设备表单所属建筑下拉的选项与远程搜索状态 */
const buildingOptions = ref<Array<{ id: number; label: string }>>([])
const buildingSearchLoading = ref(false)

/** 建筑表单校验：必填项，数值范围由 input-number 的 min/max 兜底 */
const buildingFormRules: FormRules = {
  height: [{ required: true, message: '请输入建筑高度', trigger: 'blur' }],
  heightSource: [{ required: true, message: '请选择高度来源', trigger: 'change' }],
  lon: [{ required: true, message: '请输入中心点经度', trigger: 'blur' }],
  lat: [{ required: true, message: '请输入中心点纬度', trigger: 'blur' }]
}

/** 设备表单校验 */
const deviceFormRules: FormRules = {
  deviceCode: [{ required: true, message: '请输入设备编号', trigger: 'blur' }],
  deviceType: [{ required: true, message: '请选择设备类型', trigger: 'change' }],
  buildingId: [{ required: true, message: '请选择所属建筑', trigger: 'change' }],
  status: [{ required: true, message: '请选择运行状态', trigger: 'change' }],
  lon: [{ required: true, message: '请输入点位经度', trigger: 'blur' }],
  lat: [{ required: true, message: '请输入点位纬度', trigger: 'blur' }]
}

/** 防抖计时器，按页签分开，互不打断 */
const debounceTimers: Record<AdminTab, number | null> = {
  building: null,
  device: null,
  facility: null
}

/** 关键字或筛选变化后防抖加载，并把页码重置回第一页 */
function debounceLoad(tab: AdminTab): void {
  const existing = debounceTimers[tab]
  if (existing !== null) {
    window.clearTimeout(existing)
  }
  debounceTimers[tab] = window.setTimeout(() => {
    debounceTimers[tab] = null
    if (tab === 'building') {
      store.buildingCurrent = 1
      void store.loadBuildings()
    } else if (tab === 'device') {
      store.deviceCurrent = 1
      void store.loadDevices()
    } else {
      store.facilityCurrent = 1
      void store.loadFacilities()
    }
  }, SEARCH_DEBOUNCE_MS)
}

/** 加载当前页签的列表 */
function loadActiveTab(): void {
  if (store.activeTab === 'building') {
    void store.loadBuildings()
  } else if (store.activeTab === 'device') {
    void store.loadDevices()
  } else {
    void store.loadFacilities()
  }
}

function handleBuildingRowClick(row: BuildingPageItem): void {
  emit('rowClick', 'building', row.id, row.lon, row.lat)
}

function handleDeviceRowClick(row: DevicePageItem): void {
  emit('rowClick', 'device', row.id, row.lon, row.lat)
}

function handleFacilityRowClick(row: FacilityPageItem): void {
  emit('rowClick', 'facility', row.id, row.lon, row.lat)
}

/** 切页时直接加载，不做防抖 */
function handleBuildingPageChange(page: number): void {
  store.buildingCurrent = page
  void store.loadBuildings()
}

function handleDevicePageChange(page: number): void {
  store.deviceCurrent = page
  void store.loadDevices()
}

function handleFacilityPageChange(page: number): void {
  store.facilityCurrent = page
  void store.loadFacilities()
}

/** 打开新增建筑对话框，表单复位为默认值 */
function openBuildingCreate(): void {
  dialogTab.value = 'building'
  editingId.value = null
  buildingForm.name = ''
  buildingForm.buildingType = ''
  buildingForm.levels = null
  buildingForm.height = null
  buildingForm.heightSource = ''
  buildingForm.lon = null
  buildingForm.lat = null
  buildingFormRef.value?.clearValidate()
  dialogVisible.value = true
}

/** 打开编辑建筑对话框，用行数据预填 */
function openBuildingEdit(row: BuildingPageItem): void {
  dialogTab.value = 'building'
  editingId.value = row.id
  buildingForm.name = row.name ?? ''
  buildingForm.buildingType = row.buildingType ?? ''
  buildingForm.levels = row.levels
  buildingForm.height = row.height
  buildingForm.heightSource = row.heightSource ?? ''
  buildingForm.lon = row.lon
  buildingForm.lat = row.lat
  buildingFormRef.value?.clearValidate()
  dialogVisible.value = true
}

/** 打开新增设备对话框，并预载所属建筑下拉的前 20 条 */
function openDeviceCreate(): void {
  dialogTab.value = 'device'
  editingId.value = null
  deviceForm.deviceCode = ''
  deviceForm.deviceName = ''
  deviceForm.deviceType = ''
  deviceForm.buildingId = null
  deviceForm.floor = null
  deviceForm.altitude = null
  deviceForm.status = 'ONLINE'
  deviceForm.lon = null
  deviceForm.lat = null
  deviceFormRef.value?.clearValidate()
  dialogVisible.value = true
  void loadBuildingOptions(null)
}

/** 打开编辑设备对话框，用行数据预填并保证当前建筑出现在下拉选项里 */
function openDeviceEdit(row: DevicePageItem): void {
  dialogTab.value = 'device'
  editingId.value = row.id
  deviceForm.deviceCode = row.deviceCode
  deviceForm.deviceName = row.deviceName ?? ''
  deviceForm.deviceType = row.deviceType
  deviceForm.buildingId = row.buildingId
  deviceForm.floor = row.floor
  deviceForm.altitude = row.altitude
  deviceForm.status = row.status
  deviceForm.lon = row.lon
  deviceForm.lat = row.lat
  deviceFormRef.value?.clearValidate()
  dialogVisible.value = true
  void loadBuildingOptions(row.buildingId)
}

/**
 * 预载所属建筑下拉：默认取分页前 20 条；编辑场景若当前建筑不在其中，
 * 单独拉一次建筑详情补到队首
 */
async function loadBuildingOptions(currentId: number | null): Promise<void> {
  buildingSearchLoading.value = true
  try {
    const page = await fetchBuildingPage({ current: 1, size: BUILDING_SEARCH_SIZE })
    buildingOptions.value = page.records.map((row) => ({
      id: row.id,
      label: row.name ? `${row.name}（#${row.id}）` : `#${row.id}`
    }))
  } catch {
    // 错误提示已由 request.ts 拦截器统一弹出
    buildingOptions.value = []
  } finally {
    buildingSearchLoading.value = false
  }
  if (currentId !== null && !buildingOptions.value.some((option) => option.id === currentId)) {
    try {
      const detail = await fetchBuildingDetail(currentId)
      buildingOptions.value.unshift({
        id: detail.id,
        label: detail.name ? `${detail.name}（#${detail.id}）` : `#${detail.id}`
      })
    } catch {
      buildingOptions.value.unshift({ id: currentId, label: `#${currentId}` })
    }
  }
}

/** 所属建筑远程搜索，按名称模糊匹配 */
async function searchBuildings(keyword: string): Promise<void> {
  buildingSearchLoading.value = true
  try {
    const page = await fetchBuildingPage({
      current: 1,
      size: BUILDING_SEARCH_SIZE,
      keyword: keyword.trim() || undefined
    })
    buildingOptions.value = page.records.map((row) => ({
      id: row.id,
      label: row.name ? `${row.name}（#${row.id}）` : `#${row.id}`
    }))
  } catch {
    buildingOptions.value = []
  } finally {
    buildingSearchLoading.value = false
  }
}

/** 提交编辑对话框，按页签分发到建筑或设备的创建/更新 */
async function submitDialog(): Promise<void> {
  const formRef = dialogTab.value === 'building' ? buildingFormRef.value : deviceFormRef.value
  if (formRef === undefined) {
    return
  }
  const valid = await formRef.validate().catch(() => false)
  if (!valid) {
    return
  }
  saving.value = true
  try {
    const tab = dialogTab.value
    if (tab === 'building') {
      if (buildingForm.height === null || buildingForm.lon === null || buildingForm.lat === null) {
        return
      }
      const payload = {
        name: buildingForm.name.trim() || null,
        buildingType: buildingForm.buildingType || null,
        levels: buildingForm.levels,
        height: buildingForm.height,
        heightSource: buildingForm.heightSource,
        lon: buildingForm.lon,
        lat: buildingForm.lat
      }
      if (editingId.value === null) {
        await createBuilding(payload)
        ElMessage.success('建筑已新增')
      } else {
        await updateBuilding(editingId.value, payload)
        ElMessage.success('建筑已更新')
      }
    } else {
      if (deviceForm.buildingId === null || deviceForm.lon === null || deviceForm.lat === null) {
        return
      }
      const payload = {
        deviceCode: deviceForm.deviceCode.trim(),
        deviceName: deviceForm.deviceName.trim() || null,
        deviceType: deviceForm.deviceType,
        buildingId: deviceForm.buildingId,
        floor: deviceForm.floor,
        altitude: deviceForm.altitude,
        status: deviceForm.status,
        lon: deviceForm.lon,
        lat: deviceForm.lat
      }
      if (editingId.value === null) {
        await createDevice(payload)
        ElMessage.success('设备已新增')
      } else {
        await updateDevice(editingId.value, payload)
        ElMessage.success('设备已更新')
      }
    }
    dialogVisible.value = false
    await loadActiveTab()
    emit('dataChanged', { tab, deletedId: null })
  } catch {
    // 错误提示已由 request.ts 拦截器统一弹出
  } finally {
    saving.value = false
  }
}

/** 删除建筑：二次确认后调接口并刷新列表 */
async function handleDeleteBuilding(row: BuildingPageItem): Promise<void> {
  const label = row.name ?? `#${String(row.id)}`
  try {
    await ElMessageBox.confirm(
      `确认删除建筑「${label}」？删除后不可恢复。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteBuilding(row.id)
    ElMessage.success('建筑已删除')
    await store.loadBuildings()
    emit('dataChanged', { tab: 'building', deletedId: row.id })
  } catch {
    // 错误提示已由 request.ts 拦截器统一弹出
  }
}

/** 删除设备：二次确认后调接口并刷新列表与地图图层 */
async function handleDeleteDevice(row: DevicePageItem): Promise<void> {
  const label = row.deviceName || row.deviceCode
  try {
    await ElMessageBox.confirm(
      `确认删除设备「${label}」？实时数据与告警记录将一并清理。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteDevice(row.id)
    ElMessage.success('设备已删除')
    await store.loadDevices()
    emit('dataChanged', { tab: 'device', deletedId: row.id })
  } catch {
    // 错误提示已由 request.ts 拦截器统一弹出
  }
}

/** 对话框标题：按页签与新增/编辑拼装 */
function dialogTitle(): string {
  const prefix = editingId.value === null ? '新增' : '编辑'
  return `${prefix}${dialogTab.value === 'building' ? '建筑' : '设备'}`
}

/** 抽屉展开时加载当前页签；切页签时加载目标页签 */
watch(() => store.open, (open) => {
  if (open) {
    loadActiveTab()
  }
})
watch(() => store.activeTab, () => {
  loadActiveTab()
})

/** 建筑列表：关键字与类型筛选变化，防抖重查 */
watch(
  [() => store.buildingKeyword, () => store.buildingType],
  () => debounceLoad('building')
)

/** 设备列表：关键字、类型与状态筛选变化，防抖重查 */
watch(
  [() => store.deviceKeyword, () => store.deviceType, () => store.deviceStatus],
  () => debounceLoad('device')
)

/** 设施列表：关键字、类型与状态筛选变化，防抖重查 */
watch(
  [() => store.facilityKeyword, () => store.facilityType, () => store.facilityStatus],
  () => debounceLoad('facility')
)

function formatText(value: string | number | null | undefined): string {
  return value === null || value === undefined || value === '' ? '-' : String(value)
}

function formatLonLat(lon: number, lat: number): string {
  return `${lon.toFixed(LON_LAT_DECIMALS)}, ${lat.toFixed(LON_LAT_DECIMALS)}`
}

function formatBuildingType(type: string | null): string {
  if (type === null || type === '') {
    return '-'
  }
  return BUILDING_TYPE_LABELS[type] ?? type
}

function statusTag(status: string): { text: string; type: 'success' | 'info' | 'warning' } {
  return STATUS_LABELS[status] ?? { text: status, type: 'info' }
}

/** CSV 全量导出用的每页条数，取后端分页上限减少请求次数 */
const EXPORT_PAGE_SIZE = 500

/** CSV 的 UTF-8 BOM，Excel 打开中文不乱码 */
const CSV_BOM = '﻿'

/** 导出按钮防重复点击 */
const exporting = ref(false)

/**
 * 导出当前页签 + 当前筛选条件下的全量列表为 CSV。
 * 服务端分页一次最多 500 条，循环拉取直到拉完 total
 */
async function exportCsv(): Promise<void> {
  if (exporting.value) {
    return
  }
  exporting.value = true
  try {
    const header: string[] = []
    const rows: string[][] = []
    if (store.activeTab === 'building') {
      header.push('ID', '名称', '类型', '层数', '高度(m)', '经度', '纬度')
      let current = 1
      let total = 1
      while ((current - 1) * EXPORT_PAGE_SIZE < total) {
        const page = await fetchBuildingPage({
          current,
          size: EXPORT_PAGE_SIZE,
          keyword: store.buildingKeyword.trim() || undefined,
          buildingType: store.buildingType || undefined
        })
        total = page.total
        for (const row of page.records) {
          rows.push([
            String(row.id),
            formatText(row.name),
            formatBuildingType(row.buildingType),
            formatText(row.levels),
            formatText(row.height),
            row.lon.toFixed(LON_LAT_DECIMALS),
            row.lat.toFixed(LON_LAT_DECIMALS)
          ])
        }
        current += 1
      }
    } else if (store.activeTab === 'device') {
      header.push('ID', '编号', '名称', '类型', '所属建筑', '楼层', '安装高度(m)', '状态', '经度', '纬度')
      let current = 1
      let total = 1
      while ((current - 1) * EXPORT_PAGE_SIZE < total) {
        const page = await fetchDevicePage({
          current,
          size: EXPORT_PAGE_SIZE,
          keyword: store.deviceKeyword.trim() || undefined,
          deviceType: store.deviceType || undefined,
          status: store.deviceStatus || undefined
        })
        total = page.total
        for (const row of page.records) {
          rows.push([
            String(row.id),
            formatText(row.deviceCode),
            formatText(row.deviceName),
            formatText(row.deviceTypeLabel ?? row.deviceType),
            formatText(row.buildingId),
            formatText(row.floor),
            formatText(row.altitude),
            statusTag(row.status).text,
            row.lon.toFixed(LON_LAT_DECIMALS),
            row.lat.toFixed(LON_LAT_DECIMALS)
          ])
        }
        current += 1
      }
    } else {
      header.push('ID', '编号', '名称', '类型', '安装高度(m)', '状态', '经度', '纬度')
      let current = 1
      let total = 1
      while ((current - 1) * EXPORT_PAGE_SIZE < total) {
        const page = await fetchFacilityPage({
          current,
          size: EXPORT_PAGE_SIZE,
          keyword: store.facilityKeyword.trim() || undefined,
          facilityType: store.facilityType || undefined,
          status: store.facilityStatus || undefined
        })
        total = page.total
        for (const row of page.records) {
          rows.push([
            String(row.id),
            formatText(row.facilityCode),
            formatText(row.facilityName),
            formatText(row.facilityTypeLabel ?? row.facilityType),
            formatText(row.altitude),
            statusTag(row.status).text,
            row.lon.toFixed(LON_LAT_DECIMALS),
            row.lat.toFixed(LON_LAT_DECIMALS)
          ])
        }
        current += 1
      }
    }
    const csv = CSV_BOM + [header, ...rows].map((line) => line.map(csvEscape).join(',')).join('\r\n')
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `${store.activeTab}-${new Date().toISOString().slice(0, 10)}.csv`
    anchor.click()
    URL.revokeObjectURL(url)
  } finally {
    exporting.value = false
  }
}

/**
 * CSV 字段转义：含逗号、引号或换行时用引号包裹，内部引号翻倍
 */
function csvEscape(value: string): string {
  return /[",\n\r]/.test(value) ? `"${value.replace(/"/g, '""')}"` : value
}

/** 类型标签的浅色底、主色字样式，主色透明度叠加出浅底色 */
function tagStyle(color: string): Record<string, string> {
  return { color, backgroundColor: `${color}1a`, borderColor: `${color}40` }
}
</script>

<template>
  <el-button v-if="!store.open" class="drawer-trigger" @click="store.open = true">
    管理
  </el-button>

  <el-drawer
    v-model="store.open"
    title="管理"
    direction="rtl"
    :size="DRAWER_WIDTH"
    :modal="false"
    class="admin-drawer"
  >
    <template #header>
      <span class="drawer-title">管理</span>
      <el-button
        :loading="exporting"
        size="small"
        class="export-btn"
        @click="exportCsv"
      >
        导出 CSV
      </el-button>
    </template>
    <el-tabs v-model="store.activeTab" class="admin-tabs">
      <el-tab-pane label="建筑" name="building">
        <div class="filter-row">
          <el-input
            v-model="store.buildingKeyword"
            placeholder="按名称搜索"
            clearable
            class="filter-keyword"
          />
          <el-select
            v-model="store.buildingType"
            placeholder="全部类型"
            clearable
            filterable
            class="filter-select"
          >
            <el-option
              v-for="option in BUILDING_TYPE_OPTIONS"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
          <el-button type="primary" size="small" class="add-btn" @click="openBuildingCreate">
            新增建筑
          </el-button>
        </div>
        <div class="table-wrap">
          <el-table
            :data="store.buildingRecords"
            v-loading="store.buildingLoading"
            size="small"
            stripe
            @row-click="handleBuildingRowClick"
          >
            <el-table-column label="名称" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">{{ formatText(row.name) }}</template>
            </el-table-column>
            <el-table-column label="类型" width="88">
              <template #default="{ row }">
                <el-tag
                  v-if="row.buildingType && BUILDING_TYPE_COLORS[row.buildingType]"
                  size="small"
                  effect="plain"
                  :style="tagStyle(BUILDING_TYPE_COLORS[row.buildingType])"
                  disable-transitions
                >
                  {{ formatBuildingType(row.buildingType) }}
                </el-tag>
                <span v-else>{{ formatBuildingType(row.buildingType) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="层数" width="60">
              <template #default="{ row }">{{ formatText(row.levels) }}</template>
            </el-table-column>
            <el-table-column label="高度(m)" width="75">
              <template #default="{ row }">{{ formatText(row.height) }}</template>
            </el-table-column>
            <el-table-column label="经纬度" width="175">
              <template #default="{ row }">{{ formatLonLat(row.lon, row.lat) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="110" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click.stop="openBuildingEdit(row)">
                  编辑
                </el-button>
                <el-button link type="danger" size="small" @click.stop="handleDeleteBuilding(row)">
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
        <el-pagination
          :current-page="store.buildingCurrent"
          :page-size="DEFAULT_PAGE_SIZE"
          :total="store.buildingTotal"
          layout="total, prev, pager, next"
          class="admin-pagination"
          @current-change="handleBuildingPageChange"
        />
      </el-tab-pane>

      <el-tab-pane label="设备" name="device">
        <div class="filter-row">
          <el-input
            v-model="store.deviceKeyword"
            placeholder="按名称或编号搜索"
            clearable
            class="filter-keyword"
          />
          <el-select
            v-model="store.deviceType"
            placeholder="全部类型"
            clearable
            class="filter-select"
          >
            <el-option
              v-for="option in DEVICE_TYPE_OPTIONS"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
          <el-select
            v-model="store.deviceStatus"
            placeholder="全部状态"
            clearable
            class="filter-select"
          >
            <el-option
              v-for="option in DEVICE_STATUS_OPTIONS"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
          <el-button type="primary" size="small" class="add-btn" @click="openDeviceCreate">
            新增设备
          </el-button>
        </div>
        <div class="table-wrap">
          <el-table
            :data="store.deviceRecords"
            v-loading="store.deviceLoading"
            size="small"
            stripe
            @row-click="handleDeviceRowClick"
          >
            <el-table-column label="编号" width="110" show-overflow-tooltip>
              <template #default="{ row }">{{ formatText(row.deviceCode) }}</template>
            </el-table-column>
            <el-table-column label="名称" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">{{ formatText(row.deviceName) }}</template>
            </el-table-column>
            <el-table-column label="类型" width="88">
              <template #default="{ row }">
                <el-tag
                  v-if="DEVICE_TYPE_COLORS[row.deviceType]"
                  size="small"
                  effect="plain"
                  :style="tagStyle(DEVICE_TYPE_COLORS[row.deviceType])"
                  disable-transitions
                >
                  {{ formatText(row.deviceTypeLabel ?? row.deviceType) }}
                </el-tag>
                <span v-else>{{ formatText(row.deviceTypeLabel ?? row.deviceType) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="70">
              <template #default="{ row }">
                <el-tag :type="statusTag(row.status).type" size="small" disable-transitions>
                  {{ statusTag(row.status).text }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="经纬度" width="175">
              <template #default="{ row }">{{ formatLonLat(row.lon, row.lat) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="110" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click.stop="openDeviceEdit(row)">
                  编辑
                </el-button>
                <el-button link type="danger" size="small" @click.stop="handleDeleteDevice(row)">
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
        <el-pagination
          :current-page="store.deviceCurrent"
          :page-size="DEFAULT_PAGE_SIZE"
          :total="store.deviceTotal"
          layout="total, prev, pager, next"
          class="admin-pagination"
          @current-change="handleDevicePageChange"
        />
      </el-tab-pane>

      <el-tab-pane label="设施" name="facility">
        <div class="filter-row">
          <el-input
            v-model="store.facilityKeyword"
            placeholder="按名称或编号搜索"
            clearable
            class="filter-keyword"
          />
          <el-select
            v-model="store.facilityType"
            placeholder="全部类型"
            clearable
            class="filter-select"
          >
            <el-option
              v-for="option in FACILITY_TYPE_OPTIONS"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
          <el-select
            v-model="store.facilityStatus"
            placeholder="全部状态"
            clearable
            class="filter-select"
          >
            <el-option
              v-for="option in DEVICE_STATUS_OPTIONS"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </div>
        <div class="table-wrap">
          <el-table
            :data="store.facilityRecords"
            v-loading="store.facilityLoading"
            size="small"
            stripe
            @row-click="handleFacilityRowClick"
          >
            <el-table-column label="编号" width="110" show-overflow-tooltip>
              <template #default="{ row }">{{ formatText(row.facilityCode) }}</template>
            </el-table-column>
            <el-table-column label="名称" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">{{ formatText(row.facilityName) }}</template>
            </el-table-column>
            <el-table-column label="类型" width="88">
              <template #default="{ row }">
                <el-tag
                  v-if="FACILITY_TYPE_COLORS[row.facilityType]"
                  size="small"
                  effect="plain"
                  :style="tagStyle(FACILITY_TYPE_COLORS[row.facilityType])"
                  disable-transitions
                >
                  {{ formatText(row.facilityTypeLabel ?? row.facilityType) }}
                </el-tag>
                <span v-else>{{ formatText(row.facilityTypeLabel ?? row.facilityType) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="70">
              <template #default="{ row }">
                <el-tag :type="statusTag(row.status).type" size="small" disable-transitions>
                  {{ statusTag(row.status).text }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="经纬度" width="175">
              <template #default="{ row }">{{ formatLonLat(row.lon, row.lat) }}</template>
            </el-table-column>
          </el-table>
        </div>
        <el-pagination
          :current-page="store.facilityCurrent"
          :page-size="DEFAULT_PAGE_SIZE"
          :total="store.facilityTotal"
          layout="total, prev, pager, next"
          class="admin-pagination"
          @current-change="handleFacilityPageChange"
        />
      </el-tab-pane>
    </el-tabs>
  </el-drawer>

  <el-dialog
    v-model="dialogVisible"
    :title="dialogTitle()"
    width="480px"
    :close-on-click-modal="false"
    append-to-body
  >
    <el-form
      v-if="dialogTab === 'building'"
      ref="buildingFormRef"
      :model="buildingForm"
      :rules="buildingFormRules"
      label-width="100px"
    >
      <el-form-item label="名称" prop="name">
        <el-input v-model="buildingForm.name" maxlength="128" placeholder="选填，多数建筑无名" clearable />
      </el-form-item>
      <el-form-item label="类型" prop="buildingType">
        <el-select v-model="buildingForm.buildingType" placeholder="未分类" clearable style="width: 100%">
          <el-option
            v-for="option in BUILDING_TYPE_OPTIONS"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="层数" prop="levels">
        <el-input-number
          v-model="buildingForm.levels"
          :min="1"
          :max="500"
          :controls="false"
          placeholder="选填"
          style="width: 100%"
        />
      </el-form-item>
      <el-form-item label="高度(m)" prop="height">
        <el-input-number
          v-model="buildingForm.height"
          :min="0.1"
          :max="9999.99"
          :precision="HEIGHT_PRECISION"
          :step="1"
          :controls="false"
          placeholder="必填，单位米"
          style="width: 100%"
        />
      </el-form-item>
      <el-form-item label="高度来源" prop="heightSource">
        <el-select v-model="buildingForm.heightSource" placeholder="请选择" style="width: 100%">
          <el-option
            v-for="option in HEIGHT_SOURCE_OPTIONS"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="中心点经度" prop="lon">
        <el-input-number
          v-model="buildingForm.lon"
          :min="-180"
          :max="180"
          :precision="LON_LAT_PRECISION"
          :step="0.0001"
          :controls="false"
          placeholder="必填，-180~180"
          style="width: 100%"
        />
      </el-form-item>
      <el-form-item label="中心点纬度" prop="lat">
        <el-input-number
          v-model="buildingForm.lat"
          :min="-90"
          :max="90"
          :precision="LON_LAT_PRECISION"
          :step="0.0001"
          :controls="false"
          placeholder="必填，-90~90"
          style="width: 100%"
        />
      </el-form-item>
    </el-form>
    <el-form
      v-else
      ref="deviceFormRef"
      :model="deviceForm"
      :rules="deviceFormRules"
      label-width="100px"
    >
      <el-form-item label="编号" prop="deviceCode">
        <el-input v-model="deviceForm.deviceCode" maxlength="64" placeholder="全局唯一" clearable />
      </el-form-item>
      <el-form-item label="名称" prop="deviceName">
        <el-input v-model="deviceForm.deviceName" maxlength="128" placeholder="选填" clearable />
      </el-form-item>
      <el-form-item label="类型" prop="deviceType">
        <el-select v-model="deviceForm.deviceType" placeholder="请选择" style="width: 100%">
          <el-option
            v-for="option in DEVICE_TYPE_OPTIONS"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="所属建筑" prop="buildingId">
        <el-select
          v-model="deviceForm.buildingId"
          placeholder="输入建筑名搜索"
          filterable
          remote
          :remote-method="searchBuildings"
          :loading="buildingSearchLoading"
          style="width: 100%"
        >
          <el-option
            v-for="option in buildingOptions"
            :key="option.id"
            :label="option.label"
            :value="option.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="楼层" prop="floor">
        <el-input-number
          v-model="deviceForm.floor"
          :min="-50"
          :max="500"
          :controls="false"
          placeholder="选填，地下层为负数"
          style="width: 100%"
        />
      </el-form-item>
      <el-form-item label="安装高度(m)" prop="altitude">
        <el-input-number
          v-model="deviceForm.altitude"
          :min="0"
          :max="9999.99"
          :precision="HEIGHT_PRECISION"
          :step="1"
          :controls="false"
          placeholder="选填"
          style="width: 100%"
        />
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-select v-model="deviceForm.status" style="width: 100%">
          <el-option
            v-for="option in DEVICE_STATUS_OPTIONS"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="点位经度" prop="lon">
        <el-input-number
          v-model="deviceForm.lon"
          :min="-180"
          :max="180"
          :precision="LON_LAT_PRECISION"
          :step="0.0001"
          :controls="false"
          placeholder="必填，-180~180"
          style="width: 100%"
        />
      </el-form-item>
      <el-form-item label="点位纬度" prop="lat">
        <el-input-number
          v-model="deviceForm.lat"
          :min="-90"
          :max="90"
          :precision="LON_LAT_PRECISION"
          :step="0.0001"
          :controls="false"
          placeholder="必填，-90~90"
          style="width: 100%"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submitDialog">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.drawer-trigger {
  position: absolute;
  top: 50%;
  right: 0;
  transform: translateY(-50%);
  z-index: 10;
  height: 120px;
  padding: 0 10px;
  border-radius: 10px 0 0 10px;
  writing-mode: vertical-rl;
  letter-spacing: 6px;
  font-size: 16px;
  font-weight: 600;
  color: #fff;
  background: linear-gradient(180deg, #409eff, #2b7cd6);
  border: none;
  box-shadow: -2px 0 10px rgba(0, 0, 0, 0.2);
}

.drawer-trigger:hover {
  background: linear-gradient(180deg, #66b1ff, #409eff);
}

.drawer-title {
  font-size: 16px;
  font-weight: 600;
}

.export-btn {
  margin-right: 4px;
}

.admin-tabs {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.filter-row {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
  flex: none;
}

.filter-keyword {
  flex: 1;
  min-width: 0;
}

.filter-select {
  width: 110px;
}

.add-btn {
  flex: none;
}

.table-wrap {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

.admin-pagination {
  flex: none;
  justify-content: flex-end;
  margin-top: 10px;
}
</style>

<!-- el-drawer 多根节点 + Teleport 渲染，不继承组件 scoped 的 data-v，
     其内部元素样式只能走非 scoped 块，用 .admin-drawer 前缀限定作用域 -->
<style>
.admin-drawer .el-drawer__header {
  margin-bottom: 4px;
  padding-bottom: 8px;
}

.admin-drawer .el-drawer__body {
  padding: 0 16px 16px;
}

.admin-drawer .el-tabs__content {
  flex: 1;
  overflow: hidden;
}

.admin-drawer .el-tab-pane {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.admin-drawer .el-table {
  --el-table-header-bg-color: #f2f6fc;
  border-radius: 6px;
}

.admin-drawer .el-table th.el-table__cell {
  color: #303133;
  font-weight: 600;
}

.admin-drawer .el-table .el-table__row {
  cursor: pointer;
}

.admin-drawer .el-table .el-table__row:hover > td.el-table__cell {
  background-color: #ecf5ff;
}
</style>
