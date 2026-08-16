<script setup lang="ts">
import { computed } from 'vue'

import type { DeviceRealtime } from '@/api/device'
import type { FacilityItem } from '@/api/facility'

const props = defineProps<{
  facility: FacilityItem | null
  realtime: DeviceRealtime | null
  loading: boolean
}>()

defineEmits<{
  close: []
}>()

/** 运行状态到中文与标签配色的映射 */
const STATUS_LABELS: Readonly<Record<string, { text: string; type: string }>> = {
  ONLINE: { text: '在线', type: 'success' },
  OFFLINE: { text: '离线', type: 'info' },
  FAULT: { text: '故障', type: 'warning' }
}

/** 告警级别到中文与标签配色的映射 */
const ALARM_LEVEL_LABELS: Readonly<Record<number, { text: string; type: string }>> = {
  0: { text: '正常', type: 'success' },
  1: { text: '预警', type: 'warning' },
  2: { text: '告警', type: 'danger' }
}

/** 指标键到中文名与单位的映射，键与 FacilityMetricsGenerator 生成的字段一致 */
const METRIC_LABELS: Readonly<Record<string, { label: string; unit: string }>> = {
  power: { label: '充电功率', unit: 'kW' },
  plugged: { label: '插枪状态', unit: '' },
  temperature: { label: '机身温度', unit: '℃' },
  brightness: { label: '亮度', unit: '%' },
  energy: { label: '周期耗电', unit: 'kWh' },
  tilt: { label: '倾角', unit: '°' },
  waterLevel: { label: '井内水位', unit: 'cm' },
  passengerFlow: { label: '客流', unit: '人/时' },
  screenOn: { label: '电子站牌', unit: '' }
}

/** 插枪指标值：1 表示已插枪 */
const PLUG_CONNECTED = 1

/** 站牌指标值：1 表示正常显示 */
const SCREEN_ON = 1

const statusTag = computed(() => {
  const status = props.facility?.status
  if (status === undefined) {
    return { text: '-', type: 'info' }
  }
  return STATUS_LABELS[status] ?? { text: status, type: 'info' }
})

const alarmTag = computed(() => {
  const level = props.realtime?.alarmLevel
  if (level === undefined || level === null) {
    return null
  }
  return ALARM_LEVEL_LABELS[level] ?? { text: String(level), type: 'info' }
})

/** 实时指标展开为「中文名 + 值 + 单位」列表 */
const metricRows = computed(() => {
  const metrics = props.realtime?.metrics
  if (metrics === undefined || metrics === null) {
    return []
  }
  return Object.entries(metrics).map(([key, value]) => {
    const meta = METRIC_LABELS[key]
    return {
      key,
      label: meta === undefined ? key : meta.label,
      value: formatMetricValue(key, value),
      unit: meta === undefined ? '' : meta.unit
    }
  })
})

/**
 * 插枪、站牌这类布尔语义的指标转成中文，其余原样展示
 */
function formatMetricValue(key: string, value: number): string {
  if (key === 'plugged') {
    return value === PLUG_CONNECTED ? '已插枪' : '空闲'
  }
  if (key === 'screenOn') {
    return value === SCREEN_ON ? '正常显示' : '灭屏'
  }
  return String(value)
}

/**
 * 后端下发带时区偏移的 ISO 时间，转本地时间展示
 */
function formatTime(value: string | undefined): string {
  if (value === undefined) {
    return '-'
  }
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function formatText(value: string | number | null | undefined): string {
  return value === null || value === undefined || value === '' ? '-' : String(value)
}
</script>

<template>
  <el-card v-if="facility !== null" class="facility-panel" shadow="always">
    <template #header>
      <div class="panel-header">
        <span>市政设施监测</span>
        <el-button link type="primary" @click="$emit('close')">关闭</el-button>
      </div>
    </template>
    <el-descriptions :column="1" border size="small">
      <el-descriptions-item label="编号">
        {{ formatText(facility.facilityCode) }}
      </el-descriptions-item>
      <el-descriptions-item label="名称">
        {{ formatText(facility.facilityName) }}
      </el-descriptions-item>
      <el-descriptions-item label="类型">
        {{ formatText(facility.facilityTypeLabel) }}
      </el-descriptions-item>
      <el-descriptions-item label="安装高度">{{ facility.altitude }} m</el-descriptions-item>
      <el-descriptions-item label="经纬度">
        {{ facility.lon.toFixed(6) }}, {{ facility.lat.toFixed(6) }}
      </el-descriptions-item>
      <el-descriptions-item label="运行状态">
        <el-tag :type="statusTag.type" size="small" disable-transitions>
          {{ statusTag.text }}
        </el-tag>
      </el-descriptions-item>
      <el-descriptions-item v-if="alarmTag !== null" label="告警级别">
        <el-tag :type="alarmTag.type" size="small" disable-transitions>
          {{ alarmTag.text }}
        </el-tag>
      </el-descriptions-item>
    </el-descriptions>

    <div class="metrics-title">实时指标</div>
    <el-skeleton v-if="loading" :rows="3" animated />
    <el-descriptions v-else-if="metricRows.length > 0" :column="1" border size="small">
      <el-descriptions-item v-for="row in metricRows" :key="row.key" :label="row.label">
        {{ row.value }} {{ row.unit }}
      </el-descriptions-item>
    </el-descriptions>
    <el-empty v-else description="该设施暂无实时数据" :image-size="60" />
    <div v-if="realtime !== null" class="update-time">
      更新时间：{{ formatTime(realtime.ts) }}
    </div>
  </el-card>
</template>

<style scoped>
.facility-panel {
  position: absolute;
  top: 16px;
  right: 16px;
  width: 320px;
  max-height: calc(100% - 32px);
  overflow: auto;
  z-index: 11;
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
}

.metrics-title {
  margin: 12px 0 8px;
  font-size: 13px;
  font-weight: 600;
}

.update-time {
  margin-top: 8px;
  font-size: 12px;
  color: #909399;
}
</style>
