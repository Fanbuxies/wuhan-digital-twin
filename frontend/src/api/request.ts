import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * 后端统一响应结构，与 com.wuhan.twin.common.result.R 对齐
 */
export interface ApiResult<T> {
  code: number
  msg: string
  data: T
}

/** 业务成功码 */
const SUCCESS_CODE = 200

/** 请求超时，GeoJSON 全域约 360KB，留足余量 */
const REQUEST_TIMEOUT = 20000

const instance = axios.create({
  baseURL: '/api',
  timeout: REQUEST_TIMEOUT
})

instance.interceptors.response.use(
  (response) => {
    const result = response.data as ApiResult<unknown>
    // 后端异常也返回 HTTP 200，业务成败只看 code
    if (result.code !== SUCCESS_CODE) {
      ElMessage.error(result.msg)
      return Promise.reject(new Error(result.msg))
    }
    return response
  },
  (error: unknown) => {
    ElMessage.error('网络请求失败，请检查后端服务')
    return Promise.reject(error)
  }
)

/**
 * GET 请求，直接返回业务数据部分
 */
export async function get<T>(url: string, params?: Record<string, unknown>): Promise<T> {
  const response = await instance.get<ApiResult<T>>(url, { params })
  return response.data.data
}

/**
 * POST 请求，请求体 JSON 序列化
 */
export async function post<T>(url: string, data?: unknown): Promise<T> {
  const response = await instance.post<ApiResult<T>>(url, data)
  return response.data.data
}

/**
 * PUT 请求
 */
export async function put<T>(url: string, data?: unknown): Promise<T> {
  const response = await instance.put<ApiResult<T>>(url, data)
  return response.data.data
}

/**
 * DELETE 请求。命名避开 delete 保留字
 */
export async function del<T>(url: string): Promise<T> {
  const response = await instance.delete<ApiResult<T>>(url)
  return response.data.data
}
