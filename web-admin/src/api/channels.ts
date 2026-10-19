import request from '@/utils/request'

export function getChannels() {
  return request({
    url: '/admin/channels',
    method: 'GET'
  })
}

export function getChannelDetail(channelNo: string) {
  return request({
    url: `/admin/channels/${channelNo}`,
    method: 'GET'
  })
}

export function deleteChannel(channelNo: string) {
  return request({
    url: `/admin/channels/${channelNo}`,
    method: 'DELETE'
  })
}
