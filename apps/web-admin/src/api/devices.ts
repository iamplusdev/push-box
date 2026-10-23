import request from '@/utils/request'

export function getDevices() {
  return request({
    url: '/admin/devices',
    method: 'GET'
  })
}

export function revokeDevice(deviceId: string) {
  return request({
    url: `/admin/devices/${deviceId}`,
    method: 'DELETE'
  })
}
