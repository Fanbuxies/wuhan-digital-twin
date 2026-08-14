import type { AlarmMessage, DeviceRealtime } from '@/api/device'

/** 推送消息类型，与后端 PushMessageVO 常量一致 */
const TYPE_DEVICE_UPDATE = 'DEVICE_UPDATE'
const TYPE_ALARM_NEW = 'ALARM_NEW'

/** 推送端点，走 vite proxy 转发到后端 8080 */
const REALTIME_PATH = '/ws/realtime'

/** 重连初始间隔 */
const RECONNECT_BASE_DELAY = 1000

/** 重连最大间隔，指数退避的上限 */
const RECONNECT_MAX_DELAY = 30000

/** 退避倍率 */
const RECONNECT_FACTOR = 2

/** 连接状态，供状态卡展示 */
export type RealtimeStatus = 'CONNECTING' | 'OPEN' | 'CLOSED'

/** 推送回调 */
export interface RealtimeHandlers {
  onDeviceUpdate: (list: DeviceRealtime[]) => void
  onAlarmNew: (alarm: AlarmMessage) => void
  onStatusChange: (status: RealtimeStatus) => void
}

/** 全局唯一连接，禁止在组件内直接 new WebSocket */
let socket: WebSocket | null = null

/** 当前回调，重连时复用 */
let currentHandlers: RealtimeHandlers | null = null

/** 重连定时器句柄 */
let reconnectTimer: number | null = null

/** 下一次重连延迟 */
let reconnectDelay = RECONNECT_BASE_DELAY

/** 主动关闭标记，为 true 时不再重连 */
let manualClosed = false

/**
 * 拼接 ws 地址，页面用 https 时自动切 wss
 */
function buildUrl(): string {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}${REALTIME_PATH}`
}

/**
 * 分发单条推送报文，解析失败只记录不中断连接
 */
function dispatchMessage(raw: string, handlers: RealtimeHandlers): void {
  try {
    const message = JSON.parse(raw) as { type: string; data: unknown }
    if (message.type === TYPE_DEVICE_UPDATE) {
      handlers.onDeviceUpdate(message.data as DeviceRealtime[])
      return
    }
    if (message.type === TYPE_ALARM_NEW) {
      handlers.onAlarmNew(message.data as AlarmMessage)
      return
    }
    console.warn('未知推送类型', message.type)
  } catch (error) {
    console.warn('推送报文解析失败', error)
  }
}

/**
 * 安排一次指数退避重连
 */
function scheduleReconnect(): void {
  if (manualClosed || currentHandlers === null || reconnectTimer !== null) {
    return
  }
  const handlers = currentHandlers
  const delay = reconnectDelay
  reconnectTimer = window.setTimeout(() => {
    reconnectTimer = null
    openSocket(handlers)
  }, delay)
  reconnectDelay = Math.min(delay * RECONNECT_FACTOR, RECONNECT_MAX_DELAY)
}

/**
 * 建立连接并绑定事件
 */
function openSocket(handlers: RealtimeHandlers): void {
  currentHandlers = handlers
  handlers.onStatusChange('CONNECTING')
  socket = new WebSocket(buildUrl())
  socket.onopen = () => {
    // 连上后重置退避，下一次断线仍从 1 秒起算
    reconnectDelay = RECONNECT_BASE_DELAY
    handlers.onStatusChange('OPEN')
  }
  socket.onmessage = (event: MessageEvent<string>) => {
    dispatchMessage(event.data, handlers)
  }
  socket.onclose = () => {
    socket = null
    handlers.onStatusChange('CLOSED')
    scheduleReconnect()
  }
  socket.onerror = () => {
    // onerror 之后浏览器必然触发 onclose，重连逻辑统一放在 onclose
    handlers.onStatusChange('CLOSED')
  }
}

/**
 * 连接实时通道，重复调用直接复用已有连接
 */
export function connectRealtime(handlers: RealtimeHandlers): void {
  if (socket !== null) {
    return
  }
  manualClosed = false
  reconnectDelay = RECONNECT_BASE_DELAY
  openSocket(handlers)
}

/**
 * 主动断开，不触发重连
 */
export function disconnectRealtime(): void {
  manualClosed = true
  if (reconnectTimer !== null) {
    window.clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  currentHandlers = null
  if (socket !== null) {
    socket.close()
    socket = null
  }
}
