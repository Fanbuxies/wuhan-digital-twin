package com.wuhan.twin.device.service;

import java.util.List;

import com.wuhan.twin.device.vo.DeviceVO;

/**
 * 设备服务
 *
 * @author lvfan
 */
public interface DeviceService {

    /**
     * 查询设备列表
     *
     * @param buildingId 所属建筑主键，为 null 表示不限
     * @param deviceType 设备类型，为空表示不限
     * @return 设备列表，无数据返回空集合
     * @throws com.wuhan.twin.common.exception.BizException 设备类型非法时抛出
     */
    List<DeviceVO> listDevices(Long buildingId, String deviceType);
}
