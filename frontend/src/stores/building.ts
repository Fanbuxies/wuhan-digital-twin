import { defineStore } from 'pinia'
import { ref } from 'vue'

import { fetchBuildingDetail, type BuildingDetail } from '@/api/building'

export const useBuildingStore = defineStore('building', () => {
  /** 当前选中建筑详情，未选中为 null */
  const detail = ref<BuildingDetail | null>(null)

  /** 详情加载中标记 */
  const loading = ref(false)

  /**
   * 拉取并缓存建筑详情，失败时清空面板
   */
  async function loadDetail(id: number): Promise<void> {
    loading.value = true
    try {
      detail.value = await fetchBuildingDetail(id)
    } catch {
      // 错误提示已由 request.ts 拦截器统一弹出，此处只需复位面板
      detail.value = null
    } finally {
      loading.value = false
    }
  }

  function clearDetail(): void {
    detail.value = null
  }

  return { detail, loading, loadDetail, clearDetail }
})
