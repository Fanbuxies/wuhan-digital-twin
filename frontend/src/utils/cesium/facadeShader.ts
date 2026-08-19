import {
  Cartesian3,
  CustomShader,
  CustomShaderMode,
  CustomShaderTranslucencyMode,
  Ellipsoid,
  Matrix4,
  UniformType,
  VaryingType,
  type Cesium3DTileset
} from 'cesium'

import { BUILDING_CATEGORY_RULES, HEIGHT_LIGHTNESS_LEVELS, pickBuildingCssColor } from '@/utils/cesium/buildingLayer'

/**
 * 建筑立面程序化着色器（CustomShader，MODIFY_MATERIAL）。
 *
 * 切片无 UV（顶点属性仅 POSITION/NORMAL/_FEATURE_ID_0），贴图路封死，
 * 图案全部由模型坐标 + 法线在片元着色器里算：楼层横带、竖向窗格、屋顶区分、
 * 底层底商整片通透、商业/住宅按用途族区分窗格样式。
 * MODIFY_MATERIAL 模式下 material.diffuse 初始即样式色/高亮色，
 * 本着色器只在其上乘明暗系数，不破坏用途配色与选中高亮（任务书 6.2 已实测）。
 */

/** 层高（米）：楼层数一律 height/3.2 推算（levels 仅 19% 覆盖，不可用） */
export const FACADE_FLOOR_HEIGHT_M = 3.2

/** 窗格水平周期（米）：窗与墙柱交替的间距 */
const FACADE_PANE_PERIOD_M = 1.6

/** 楼层线压暗幅度（0~1，越小越淡） */
const FACADE_BAND_STRENGTH = 0.22

/** 窗格明暗幅度（0~1，越小越淡） */
const FACADE_PANE_STRENGTH = 0.18

/** 屋顶整体压暗系数（0~1，1 为不变暗） */
const FACADE_ROOF_SHADE = 0.9

/** 窗带两侧柱宽占周期比例（0~0.5） */
const FACADE_PANE_EDGE = 0.12

/** 楼层线最小半宽（米），近距离下不至于细到消失 */
const FACADE_BAND_MIN_HALF_M = 0.05

/** 窗格边缘平滑区间下限（相位单位），防止远处周期混叠 */
const FACADE_PANE_MIN_PX = 0.004

/** 墙面切向向量最小长度（米），低于视为屋顶等退化情形 */
const FACADE_TANGENT_MIN_LEN_M = 1e-6

/** 底商高度（米）：约 4 m 以下按整片通透处理，不画小窗 */
const FACADE_PODIUM_HEIGHT_M = 4.0

/** 底商过渡带高度（米）：竖向窗格在该高度区间内平滑恢复 */
const FACADE_PODIUM_FADE_M = 0.8

/** 商业族幕墙分格周期（米）：玻璃模块更宽 */
const FACADE_CURTAIN_WALL_PERIOD_M = 2.6

/** 商业族幕墙窗柱占比（0~0.5）：玻璃面占比更大 */
const FACADE_CURTAIN_WALL_EDGE = 0.08

/** 商业族幕墙层线压暗幅度（0~1）：横向层线更明显 */
const FACADE_CURTAIN_WALL_BAND_STRENGTH = 0.3

/** 住宅族小窗周期（米）：窗更窄更密 */
const FACADE_SMALL_WINDOW_PERIOD_M = 1.2

/** 住宅族小窗窗柱占比（0~0.5）：墙柱更宽 */
const FACADE_SMALL_WINDOW_EDGE = 0.16

/**
 * 用途族样式色匹配容差：批纹理按 8bit 无损存储样式色，
 * 片元可见值 = 样式色 × FACADE_PBR_DIELECTRIC_SCALE（纯 float 运算，无二次量化），
 * 容差只需抗浮点误差
 */
const FACADE_COLOR_EPS = 0.01

/**
 * PBR 电介质折算系数：切片材质 metallic=0（pg2b3dm 固定），
 * czm_pbrMetallicRoughnessMaterial 输出 diffuseColor = baseColor × (1 − f0) × (1 − metallic)，
 * 电介质 f0 = 0.04，故片元收到的 diffuse 恒为样式色 × 0.96（MaterialStageFS 在自定义阶段之前折算）
 */
const FACADE_PBR_DIELECTRIC_SCALE = 0.96

/** WGS84 半径与第一偏心率平方（由 Ellipsoid.WGS84 推导，不硬编码） */
const WGS84_RADII = Ellipsoid.WGS84.radii
const WGS84_ECCENTRICITY_SQUARED =
  (WGS84_RADII.x * WGS84_RADII.x - WGS84_RADII.z * WGS84_RADII.z) / (WGS84_RADII.x * WGS84_RADII.x)

/** GLSL 浮点字面量格式化，保证小数点（JS 模板把 4.0 渲染成 4 会被 GLSL 判为 int） */
function formatGlslFloat(value: number): string {
  return value.toFixed(7)
}

/** GLSL 具名常量（由上方 TS 常量注入，单一数据源） */
const FACADE_GLSL_CONSTANTS = `
const float FACADE_FLOOR_HEIGHT = ${formatGlslFloat(FACADE_FLOOR_HEIGHT_M)};
const float FACADE_PANE_PERIOD = ${formatGlslFloat(FACADE_PANE_PERIOD_M)};
const float FACADE_BAND_STRENGTH = ${formatGlslFloat(FACADE_BAND_STRENGTH)};
const float FACADE_PANE_STRENGTH = ${formatGlslFloat(FACADE_PANE_STRENGTH)};
const float FACADE_ROOF_SHADE = ${formatGlslFloat(FACADE_ROOF_SHADE)};
const float FACADE_PANE_EDGE = ${formatGlslFloat(FACADE_PANE_EDGE)};
const float FACADE_BAND_MIN_HALF = ${formatGlslFloat(FACADE_BAND_MIN_HALF_M)};
const float FACADE_PANE_MIN_PX = ${formatGlslFloat(FACADE_PANE_MIN_PX)};
const float FACADE_TANGENT_MIN_LEN = ${formatGlslFloat(FACADE_TANGENT_MIN_LEN_M)};
const float FACADE_PODIUM_HEIGHT = ${formatGlslFloat(FACADE_PODIUM_HEIGHT_M)};
const float FACADE_PODIUM_FADE = ${formatGlslFloat(FACADE_PODIUM_FADE_M)};
const float FACADE_CURTAIN_WALL_PERIOD = ${formatGlslFloat(FACADE_CURTAIN_WALL_PERIOD_M)};
const float FACADE_CURTAIN_WALL_EDGE = ${formatGlslFloat(FACADE_CURTAIN_WALL_EDGE)};
const float FACADE_CURTAIN_WALL_BAND_STRENGTH = ${formatGlslFloat(FACADE_CURTAIN_WALL_BAND_STRENGTH)};
const float FACADE_SMALL_WINDOW_PERIOD = ${formatGlslFloat(FACADE_SMALL_WINDOW_PERIOD_M)};
const float FACADE_SMALL_WINDOW_EDGE = ${formatGlslFloat(FACADE_SMALL_WINDOW_EDGE)};
`

/**
 * 椭球高计算 GLSL 片段：h = alongUp + s²/(2R)。
 * 以 RTC 在椭球面上的投影为原点做抛物面近似，与 Cesium 精确大地高毫米级一致
 * （任务书 4.1 实测：相机 259.997 vs 260.000，四角地表点均 0.000）。
 * 主 shader 与 6.5 临时验证 shader 共用本片段，保证验证的数学与渲染同一套
 */
export const FACADE_HEIGHT_GLSL = `
float facadeHeight(vec3 d) {
  float alongUp = dot(d, u_up);
  float s2 = dot(d, d) - alongUp * alongUp;
  return alongUp + s2 / (2.0 * u_radius);
}
`

/**
 * 用途族判定依据：Cesium 1.115 管线不把 b3dm 批表（property table）暴露进 shader，
 * fsInput.metadata 读不到 building_type（实测编译报 no such field in structure），
 * 只能按片元收到的样式色精确匹配——样式色由「用途 × 高度」唯一决定（8 类 × 3 档共 24 色），
 * 颜色常量由取色函数派生，与 tileset 样式同源，改色板无需改本文件。
 * 场景 highDynamicRange=false，czm_gammaCorrect 为空操作，样式色以原始 sRGB 到达片元；
 * 再经 PBR 电介质折算（×0.96）后即为匹配目标。
 * 被高亮的建筑样式色被高亮色覆盖，匹配不到任何族，立面临时退为通用窗格。
 */

/** 样式色 8bit 通道 → 片元实际可见值：sRGB 原值 × PBR 电介质折算 */
function fragmentDiffuseChannel(channel: number): number {
  return (channel / 255) * FACADE_PBR_DIELECTRIC_SCALE
}

/** 取用途族的全部可见样式色（三档高度） */
function familyVisibleColors(category: string): number[][] {
  const rule = BUILDING_CATEGORY_RULES.find((item) => item.category === category)
  if (rule === undefined) {
    return []
  }
  return HEIGHT_LIGHTNESS_LEVELS.map((level) => {
    const cssColor = pickBuildingCssColor(rule.types[0], level.maxHeight)
    return [1, 3, 5].map((index) => fragmentDiffuseChannel(parseInt(cssColor.slice(index, index + 2), 16)))
  })
}

/** 生成单族判定 GLSL：包围盒快速剔除 + 逐色精确匹配 */
function buildFamilyClassifyGlsl(category: string, functionName: string): string {
  const colors = familyVisibleColors(category)
  if (colors.length === 0) {
    return `bool ${functionName}(vec3 c) { return false; }`
  }
  const minByChannel = [0, 1, 2].map(
    (channel) => Math.min(...colors.map((color) => color[channel])) - FACADE_COLOR_EPS
  )
  const maxByChannel = [0, 1, 2].map(
    (channel) => Math.max(...colors.map((color) => color[channel])) + FACADE_COLOR_EPS
  )
  const exactChecks = colors
    .map((color) => `facadeColorNear(c, vec3(${color.map(formatGlslFloat).join(', ')}))`)
    .join('\n      || ')
  return `bool ${functionName}(vec3 c) {
  // 包围盒快速剔除：非本族色相的建筑一步退出，避免逐色比较
  if (any(lessThan(c, vec3(${minByChannel.map(formatGlslFloat).join(', ')})))
      || any(greaterThan(c, vec3(${maxByChannel.map(formatGlslFloat).join(', ')})))) {
    return false;
  }
  return ${exactChecks};
}`
}

/** 用途族判定 GLSL（导出供验收脚本复用）：容差常量 + 商业/住宅判定函数 */
export const FACADE_CLASSIFY_GLSL = `const float FACADE_COLOR_EPS = ${FACADE_COLOR_EPS};
bool facadeColorNear(vec3 a, vec3 b) {
  return all(lessThan(abs(a - b), vec3(FACADE_COLOR_EPS)));
}
${buildFamilyClassifyGlsl('商业', 'facadeIsCommercialFamily')}
${buildFamilyClassifyGlsl('住宅', 'facadeIsResidentialFamily')}
`

/** 主着色器片元源码。u_up/u_originMC/u_radius 与 v_normalMC 由 uniforms/varyings
 *  字典自动注入声明，文本内禁止重复声明 */
const FRAGMENT_SHADER_TEXT = `${FACADE_GLSL_CONSTANTS}
${FACADE_HEIGHT_GLSL}
${FACADE_CLASSIFY_GLSL}
void fragmentMain(FragmentInput fsInput, inout czm_modelMaterial material) {
  vec3 d = fsInput.attributes.positionMC - u_originMC;
  float h = facadeHeight(d);

  // 用途族立面样式：商业走横向幕墙分格、住宅走规则小窗，其余走通用窗格参数。
  // 判定用 material.diffuse（样式色由「用途 × 高度」唯一决定），被高亮的建筑退为通用窗格
  float panePeriod = FACADE_PANE_PERIOD;
  float paneEdge = FACADE_PANE_EDGE;
  float bandStrength = FACADE_BAND_STRENGTH;
  if (facadeIsCommercialFamily(material.diffuse)) {
    panePeriod = FACADE_CURTAIN_WALL_PERIOD;
    paneEdge = FACADE_CURTAIN_WALL_EDGE;
    bandStrength = FACADE_CURTAIN_WALL_BAND_STRENGTH;
  } else if (facadeIsResidentialFamily(material.diffuse)) {
    panePeriod = FACADE_SMALL_WINDOW_PERIOD;
    paneEdge = FACADE_SMALL_WINDOW_EDGE;
  }

  // 楼层横带：h 每 3.2m 一周期，在周期边界画一道细暗线，fwidth 抗锯齿
  float floorPhase = fract(h / FACADE_FLOOR_HEIGHT);
  float bandHalf = max(FACADE_BAND_MIN_HALF, fwidth(h)) / FACADE_FLOOR_HEIGHT;
  float band = clamp(2.0 - smoothstep(0.0, bandHalf, floorPhase)
      - smoothstep(0.0, bandHalf, 1.0 - floorPhase), 0.0, 1.0);

  // 屋顶判定：法线接近竖直的面不画窗格与横带，整体略暗，避免屋顶出现竖条纹
  vec3 n = normalize(v_normalMC);
  float roofMix = smoothstep(0.5, 0.7, abs(dot(n, u_up)));

  // 竖向窗格：沿墙面水平方向周期性明暗，窗（中段）略暗、柱（两侧）保持本色
  vec3 t = cross(n, u_up);
  float tangentLen = length(t);
  vec3 tangent = tangentLen > FACADE_TANGENT_MIN_LEN ? t / tangentLen : vec3(0.0);
  float panePhase = fract(dot(d, tangent) / panePeriod);
  float panePx = max(fwidth(panePhase), FACADE_PANE_MIN_PX);
  float windowMask = smoothstep(paneEdge, paneEdge + 2.0 * panePx, panePhase)
      * (1.0 - smoothstep(1.0 - paneEdge - 2.0 * panePx, 1.0 - paneEdge, panePhase));
  // 底商：约 4 m 以下整片通透（不画竖向窗格），过渡带内窗格平滑恢复
  windowMask *= smoothstep(FACADE_PODIUM_HEIGHT, FACADE_PODIUM_HEIGHT + FACADE_PODIUM_FADE, h);

  float wallShade = 1.0 - bandStrength * band - FACADE_PANE_STRENGTH * windowMask;
  material.diffuse = material.diffuse * mix(wallShade, FACADE_ROOF_SHADE, roofMix);
}
`

/** 顶点阶段：把模型坐标法线经 varying 传给片元（1.115 片元侧没有 normalMC 属性；
 *  varying 以全局变量形式注入，直接赋值即可）。
 *  瓦片 transform 旋转为单位阵（任务书 4.1 实测），法线无需 inverse-transpose */
const VERTEX_SHADER_TEXT = `
void vertexMain(VertexInput vsInput, inout czm_modelVertexOutput vsOutput) {
  v_normalMC = vsInput.attributes.normalMC;
}
`

/** 着色器原点三参：椭球面投影点 O、其外法线（上方向）、高斯平均曲率半径 */
interface FacadeOrigin {
  up: Cartesian3
  originMC: Cartesian3
  radius: number
}

/** 由共享 RTC 计算着色器原点：O 为 RTC 在椭球面上的投影，u_originMC = O - RTC */
function computeFacadeOrigin(rtcCenter: Cartesian3): FacadeOrigin {
  const ellipsoid = Ellipsoid.WGS84
  const surfacePoint = ellipsoid.scaleToGeodeticSurface(rtcCenter, new Cartesian3())
  const up = ellipsoid.geodeticSurfaceNormal(surfacePoint, new Cartesian3())
  const cartographic = ellipsoid.cartesianToCartographic(surfacePoint)
  const sinLatitude = Math.sin(cartographic.latitude)
  const oneMinusE2Sin2 = 1 - WGS84_ECCENTRICITY_SQUARED * sinLatitude * sinLatitude
  const primeVertical = WGS84_RADII.x / Math.sqrt(oneMinusE2Sin2)
  const meridian = (WGS84_RADII.x * (1 - WGS84_ECCENTRICITY_SQUARED)) / Math.pow(oneMinusE2Sin2, 1.5)
  const radius = Math.sqrt(primeVertical * meridian)
  const originMC = Cartesian3.subtract(surfacePoint, rtcCenter, new Cartesian3())
  return { up, originMC, radius }
}

/** 构造立面着色器。原点在 setFacadeShaderRtc 前为占位值，避免用错原点渲染出错位横带 */
export function createFacadeShader(): CustomShader {
  return new CustomShader({
    mode: CustomShaderMode.MODIFY_MATERIAL,
    translucencyMode: CustomShaderTranslucencyMode.OPAQUE,
    uniforms: {
      u_up: { type: UniformType.VEC3, value: new Cartesian3(0.0, 0.0, 1.0) },
      u_originMC: { type: UniformType.VEC3, value: new Cartesian3() },
      u_radius: { type: UniformType.FLOAT, value: WGS84_RADII.x }
    },
    varyings: { v_normalMC: VaryingType.VEC3 },
    vertexShaderText: VERTEX_SHADER_TEXT,
    fragmentShaderText: FRAGMENT_SHADER_TEXT
  })
}

/** 按瓦片共享 RTC 设置着色器原点，楼层线随后从各楼楼底起算 */
export function setFacadeShaderRtc(shader: CustomShader, rtcCenter: Cartesian3): void {
  const origin = computeFacadeOrigin(rtcCenter)
  shader.setUniform('u_up', origin.up)
  shader.setUniform('u_originMC', origin.originMC)
  shader.setUniform('u_radius', origin.radius)
}

/**
 * 取 tileset 的共享 RTC：pg2b3dm 把 RTC 写入根 transform（平移、旋转单位阵），
 * 31 个内容瓦片全部共用（任务书 4.1 实测）。根瓦片变换未就绪时返回 null
 */
export function getTilesetSharedRtc(tileset: Cesium3DTileset): Cartesian3 | null {
  const rootTransform = tileset.root?.computedTransform
  if (rootTransform === undefined) {
    return null
  }
  return Matrix4.getTranslation(rootTransform, new Cartesian3())
}

/**
 * 6.5 临时验证 shader：把片元算出的楼层序号（h/3.2 的小数部分）输出到 emissive 通道，
 * 与主 shader 共用同一套高度数学与原点三参。仅验收脚本使用，验完恢复原 shader
 */
export function createFacadeFloorDebugShader(source: CustomShader): CustomShader {
  const sourceUniforms = source.uniforms
  const cloneVec3 = (value: unknown): Cartesian3 => {
    const v = value as Cartesian3
    return new Cartesian3(v.x, v.y, v.z)
  }
  return new CustomShader({
    mode: CustomShaderMode.MODIFY_MATERIAL,
    translucencyMode: CustomShaderTranslucencyMode.OPAQUE,
    uniforms: {
      u_up: { type: UniformType.VEC3, value: cloneVec3(sourceUniforms.u_up.value) },
      u_originMC: { type: UniformType.VEC3, value: cloneVec3(sourceUniforms.u_originMC.value) },
      u_radius: { type: UniformType.FLOAT, value: sourceUniforms.u_radius.value }
    },
    fragmentShaderText: `${FACADE_GLSL_CONSTANTS}
${FACADE_HEIGHT_GLSL}
void fragmentMain(FragmentInput fsInput, inout czm_modelMaterial material) {
  vec3 d = fsInput.attributes.positionMC - u_originMC;
  float h = facadeHeight(d);
  float floorNumber = h / FACADE_FLOOR_HEIGHT;
  float phase01 = floorNumber - floor(floorNumber);
  material.diffuse = vec3(0.0);
  material.emissive = vec3(phase01);
}
`
  })
}
