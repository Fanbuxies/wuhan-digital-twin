import { get } from '@/api/request'

/** 相机初始参数 */
export interface CameraParam {
  lon: number
  lat: number
  height: number
  heading: number
  pitch: number
}

/** 3D Tiles 数据源与初始视角 */
export interface TilesetInfo {
  /** 为 null 表示尚未生成 3D Tiles，改用 GeoJSON 拉伸白模 */
  tilesetUrl: string | null
  camera: CameraParam
}

/** 建筑详情 */
export interface BuildingDetail {
  id: number
  osmId: number
  name: string | null
  buildingType: string | null
  levels: number | null
  height: number
  heightSource: string
  baseAltitude: number
  lon: number
  lat: number
  footprint: unknown
}

/** GeoJSON 要素属性，与 BuildingMapper.xml 中 jsonb_build_object 的键一致 */
export interface BuildingFeatureProperties {
  name: string | null
  buildingType: string | null
  levels: number | null
  height: number
  heightSource: string
  baseAltitude: number
}

/** 建筑轮廓 FeatureCollection，几何一律为 Polygon */
export interface BuildingFeatureCollection {
  type: 'FeatureCollection'
  features: Array<{
    type: 'Feature'
    id: number
    geometry: unknown
    properties: BuildingFeatureProperties
  }>
}

export function fetchTilesetInfo(): Promise<TilesetInfo> {
  return get<TilesetInfo>('/building/tileset-info')
}

export function fetchBuildingDetail(id: number): Promise<BuildingDetail> {
  return get<BuildingDetail>(`/building/${id}`)
}

/**
 * 拉建筑轮廓，bbox 选填，格式 west,south,east,north，缺省返回全域
 */
export function fetchBuildingGeoJson(bbox?: string): Promise<BuildingFeatureCollection> {
  return get<BuildingFeatureCollection>('/building/geojson', bbox ? { bbox } : undefined)
}
