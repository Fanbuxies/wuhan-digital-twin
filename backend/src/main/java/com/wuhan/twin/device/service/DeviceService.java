package com.wuhan.twin.device.service;

import java.util.List;

import com.wuhan.twin.common.result.PageResult;
import com.wuhan.twin.device.dto.DevicePageQuery;
import com.wuhan.twin.device.dto.DeviceSaveDTO;
import com.wuhan.twin.device.vo.DevicePageVO;
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

    /**
     * 设备分页查询，供管理端列表使用
     *
     * @param query 分页与筛选参数
     * @return 当页设备，含类型中文名与点位经纬度
     * @throws com.wuhan.twin.common.exception.BizException 设备类型或状态非法时抛出
     */
    PageResult<DevicePageVO> pageDevices(DevicePageQuery query);

    /**
     * 新增设备，location 按点位经纬度在 SQL 侧构造
     *
     * @param dto 设备入参
     * @return 新设备主键
     * @throws com.wuhan.twin.common.exception.BizException 类型/状态非法、建筑不存在或编号重复时抛出
     */
    Long createDevice(DeviceSaveDTO dto);

    /**
     * 整体更新设备表单可编辑字段，location 按新点位重建
     *
     * @param id  设备主键
     * @param dto 设备入参
     * @throws com.wuhan.twin.common.exception.BizException 设备不存在、类型/状态非法、建筑不存在或编号重复时抛出
     */
    void updateDevice(Long id, DeviceSaveDTO dto);

    /**
     * 删除设备，事务内连带清理实时状态、告警与历史遥测
     *
     * @param id 设备主键
     * @throws com.wuhan.twin.common.exception.BizException 设备不存在时抛出
     */
    void deleteDevice(Long id);
}
