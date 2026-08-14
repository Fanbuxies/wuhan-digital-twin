<script setup lang="ts">
import { computed } from 'vue'

import type { BuildingDetail } from '@/api/building'

const props = defineProps<{
  detail: BuildingDetail | null
  loading: boolean
}>()

defineEmits<{
  close: []
}>()

/** height_source 取值到中文说明的映射 */
const HEIGHT_SOURCE_LABELS: Readonly<Record<string, string>> = {
  osm_height: 'OSM 高度标签',
  osm_levels: 'OSM 层数推算',
  default_by_type: '按建筑类型默认值'
}

const heightSourceLabel = computed(() => {
  const source = props.detail?.heightSource
  if (source === undefined) {
    return '-'
  }
  return HEIGHT_SOURCE_LABELS[source] ?? source
})

/**
 * 经纬度统一保留 6 位小数展示
 */
function formatCoordinate(value: number | undefined): string {
  return value === undefined ? '-' : value.toFixed(6)
}

function formatText(value: string | number | null | undefined): string {
  return value === null || value === undefined || value === '' ? '-' : String(value)
}
</script>

<template>
  <el-card v-if="detail !== null || loading" class="building-panel" shadow="always">
    <template #header>
      <div class="panel-header">
        <span>建筑属性</span>
        <el-button link type="primary" @click="$emit('close')">关闭</el-button>
      </div>
    </template>
    <el-skeleton v-if="loading" :rows="6" animated />
    <el-descriptions v-else-if="detail !== null" :column="1" border size="small">
      <el-descriptions-item label="名称">{{ formatText(detail.name) }}</el-descriptions-item>
      <el-descriptions-item label="建筑 id">{{ detail.id }}</el-descriptions-item>
      <el-descriptions-item label="OSM id">{{ detail.osmId }}</el-descriptions-item>
      <el-descriptions-item label="类型">{{ formatText(detail.buildingType) }}</el-descriptions-item>
      <el-descriptions-item label="层数">{{ formatText(detail.levels) }}</el-descriptions-item>
      <el-descriptions-item label="高度">{{ detail.height }} m</el-descriptions-item>
      <el-descriptions-item label="高度来源">{{ heightSourceLabel }}</el-descriptions-item>
      <el-descriptions-item label="地面基准">{{ detail.baseAltitude }} m</el-descriptions-item>
      <el-descriptions-item label="中心经度">{{ formatCoordinate(detail.lon) }}</el-descriptions-item>
      <el-descriptions-item label="中心纬度">{{ formatCoordinate(detail.lat) }}</el-descriptions-item>
    </el-descriptions>
  </el-card>
</template>

<style scoped>
.building-panel {
  position: absolute;
  top: 16px;
  right: 16px;
  width: 320px;
  max-height: calc(100% - 32px);
  overflow: auto;
  z-index: 10;
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
}
</style>
