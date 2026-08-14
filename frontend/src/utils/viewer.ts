import {
  ImageryLayer,
  OpenStreetMapImageryProvider,
  ScreenSpaceEventHandler,
  Viewer
} from 'cesium'

/** 全局唯一 Viewer，禁止在组件内直接 new */
let viewerInstance: Viewer | null = null

/** 全局唯一交互事件句柄，所有拾取逻辑在此分发 */
let eventHandler: ScreenSpaceEventHandler | null = null

/** OSM 栅格底图地址，仅作地面参考，坐标系与业务数据统一为 WGS84 */
const OSM_TILE_URL = 'https://tile.openstreetmap.org/'

/** OSM 官方瓦片最深只到 19 级，不限制会请求 20 级并报 CORS 错误 */
const OSM_MAX_LEVEL = 19

/**
 * 创建 Viewer 单例，重复调用直接返回已有实例
 */
export function createViewer(container: HTMLElement): Viewer {
  if (viewerInstance !== null) {
    return viewerInstance
  }
  viewerInstance = new Viewer(container, {
    // 不使用 Cesium Ion 资源，底图与地形均本地指定，避免无 token 时报错
    baseLayer: new ImageryLayer(
      new OpenStreetMapImageryProvider({ url: OSM_TILE_URL, maximumLevel: OSM_MAX_LEVEL }),
      {}
    ),
    baseLayerPicker: false,
    geocoder: false,
    homeButton: false,
    sceneModePicker: false,
    navigationHelpButton: false,
    fullscreenButton: false,
    animation: false,
    timeline: false,
    // 属性展示走自研面板，屏蔽 Cesium 自带气泡与选中框
    infoBox: false,
    selectionIndicator: false
  })
  return viewerInstance
}

/**
 * 获取已创建的 Viewer，未创建时抛错而非隐式新建
 */
export function getViewer(): Viewer {
  if (viewerInstance === null) {
    throw new Error('Viewer 尚未创建，请先调用 createViewer')
  }
  return viewerInstance
}

/**
 * 获取全局唯一事件句柄
 */
export function getEventHandler(): ScreenSpaceEventHandler {
  if (eventHandler === null) {
    eventHandler = new ScreenSpaceEventHandler(getViewer().scene.canvas)
  }
  return eventHandler
}

/**
 * 销毁 Viewer 与事件句柄，供组件卸载时调用
 */
export function destroyViewer(): void {
  if (eventHandler !== null && !eventHandler.isDestroyed()) {
    eventHandler.destroy()
  }
  eventHandler = null
  if (viewerInstance !== null && !viewerInstance.isDestroyed()) {
    viewerInstance.destroy()
  }
  viewerInstance = null
}
