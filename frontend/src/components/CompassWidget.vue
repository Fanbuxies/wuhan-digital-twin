<script setup lang="ts">
import { Cartesian3, Math as CesiumMath } from 'cesium'
import { onBeforeUnmount, onMounted, ref } from 'vue'

import { getViewer } from '@/utils/cesium/viewer'

/** heading 变化小于该角度（度）不刷新表盘，避免每帧触发响应式更新 */
const HEADING_EPSILON = 0.1

/** 表盘当前旋转角（度）。相机 heading 为「视线朝向」，
 *  表盘要反向转才能让 N 始终指向真北，故取负值 */
const dialRotation = ref(0)

/** 相机朝向的中文方位描述，供无障碍读屏与 title 提示 */
const headingLabel = ref('正北')

/** 方位分档：取相机 heading 归一化到 [0,360) 后落在哪一档 */
const DIRECTION_LABELS: ReadonlyArray<{ maxDegree: number; label: string }> = [
  { maxDegree: 22.5, label: '正北' },
  { maxDegree: 67.5, label: '东北' },
  { maxDegree: 112.5, label: '正东' },
  { maxDegree: 157.5, label: '东南' },
  { maxDegree: 202.5, label: '正南' },
  { maxDegree: 247.5, label: '西南' },
  { maxDegree: 292.5, label: '正西' },
  { maxDegree: 337.5, label: '西北' },
  { maxDegree: 360, label: '正北' }
]

/** postRender 监听的移除函数，卸载时调用 */
let removeListener: (() => void) | null = null

/** 上一次同步到表盘的 heading（度），用于跳过无变化的帧 */
let lastHeadingDegree = Number.NaN

/**
 * 把弧度制 heading 归一化成 [0,360) 的度数
 */
function toNormalizedDegree(headingRadian: number): number {
  const degree = CesiumMath.toDegrees(headingRadian) % 360
  return degree < 0 ? degree + 360 : degree
}

/**
 * 按角度取中文方位名
 */
function resolveDirectionLabel(degree: number): string {
  for (const item of DIRECTION_LABELS) {
    if (degree < item.maxDegree) {
      return item.label
    }
  }
  return '正北'
}

/**
 * 同步表盘角度与方位文案，挂在 scene.postRender 上逐帧调用。
 * heading 变化不足阈值时直接返回，避免每帧写 ref 触发无谓的组件更新。
 * 359° → 1° 实际只转了 2°，故比较时要处理绕零回绕
 */
function syncDial(): void {
  const degree = toNormalizedDegree(getViewer().camera.heading)
  const rawDelta = Math.abs(degree - lastHeadingDegree)
  const delta = Math.min(rawDelta, 360 - rawDelta)
  if (!Number.isNaN(lastHeadingDegree) && delta < HEADING_EPSILON) {
    return
  }

  lastHeadingDegree = degree
  dialRotation.value = -degree
  headingLabel.value = resolveDirectionLabel(degree)
}

/**
 * 单击复位到正北：只改 heading，保留当前位置与俯仰角，
 * 避免用户辛苦调好的视角被整个重置。
 * destination 必须显式传当前位置的副本 —— 只传 orientation 时 Cesium 会按
 * 新朝向反推相机位置，实测会漂掉约 2.5 km 高度与 1.3 km 水平距离
 */
function resetToNorth(): void {
  const camera = getViewer().camera
  camera.setView({
    destination: Cartesian3.clone(camera.position, new Cartesian3()),
    orientation: {
      heading: 0,
      pitch: camera.pitch,
      roll: camera.roll
    }
  })
}

onMounted(() => {
  // 不用 camera.changed：它需要把 percentageChanged 调到 0 才能实时跟随，
  // 而那是共享 viewer 上的全局状态，改了会影响其他功能。
  // postRender 逐帧回调，配合 HEADING_EPSILON 过滤后开销可忽略
  removeListener = getViewer().scene.postRender.addEventListener(syncDial)
  syncDial()
})

onBeforeUnmount(() => {
  if (removeListener !== null) {
    removeListener()
    removeListener = null
  }
})
</script>

<template>
  <button
    class="compass-widget"
    type="button"
    :title="`当前朝向：${headingLabel}，单击复位到正北`"
    :aria-label="`指南针，当前朝向${headingLabel}，单击复位到正北`"
    @click="resetToNorth"
  >
    <svg class="compass-dial" viewBox="0 0 100 100" aria-hidden="true">
      <!-- 表盘底：随相机 heading 反向旋转，N 恒指真北 -->
      <g :style="{ transform: `rotate(${dialRotation}deg)` }" class="compass-rotor">
        <circle class="dial-bg" cx="50" cy="50" r="46" />
        <!-- 四个主方向刻度 -->
        <line class="tick-major" x1="50" y1="8" x2="50" y2="16" />
        <line class="tick-major" x1="92" y1="50" x2="84" y2="50" />
        <line class="tick-major" x1="50" y1="92" x2="50" y2="84" />
        <line class="tick-major" x1="8" y1="50" x2="16" y2="50" />
        <!-- 四个次方向刻度 -->
        <line class="tick-minor" x1="79.7" y1="20.3" x2="75.5" y2="24.5" />
        <line class="tick-minor" x1="79.7" y1="79.7" x2="75.5" y2="75.5" />
        <line class="tick-minor" x1="20.3" y1="79.7" x2="24.5" y2="75.5" />
        <line class="tick-minor" x1="20.3" y1="20.3" x2="24.5" y2="24.5" />
        <!-- 指北针：北半针填红，南半针填灰，与常规罗盘一致 -->
        <polygon class="needle-north" points="50,20 57,52 50,47 43,52" />
        <polygon class="needle-south" points="50,80 43,48 50,53 57,48" />
        <text class="dial-label" x="50" y="30">N</text>
      </g>
    </svg>
  </button>
</template>

<style scoped>
.compass-widget {
  position: absolute;
  right: 16px;
  bottom: 16px;
  width: 56px;
  height: 56px;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.9);
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.15);
  cursor: pointer;
  z-index: 10;
  transition: box-shadow 0.2s ease, background 0.2s ease;
}

.compass-widget:hover {
  background: #fff;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.22);
}

.compass-dial {
  display: block;
  width: 100%;
  height: 100%;
}

/* 表盘整体旋转，圆心为中心点 */
.compass-rotor {
  transform-origin: 50% 50%;
  transition: transform 0.15s linear;
}

.dial-bg {
  fill: none;
  stroke: #dcdfe6;
  stroke-width: 2;
}

.tick-major {
  stroke: #909399;
  stroke-width: 3;
}

.tick-minor {
  stroke: #c0c4cc;
  stroke-width: 2;
}

.needle-north {
  fill: #f56c6c;
}

.needle-south {
  fill: #c0c4cc;
}

.dial-label {
  fill: #303133;
  font-size: 14px;
  font-weight: 600;
  text-anchor: middle;
  dominant-baseline: middle;
}
</style>
