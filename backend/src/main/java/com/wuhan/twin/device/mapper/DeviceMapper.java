package com.wuhan.twin.device.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuhan.twin.device.entity.DeviceDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 设备 mapper
 *
 * @author lvfan
 */
@Mapper
public interface DeviceMapper extends BaseMapper<DeviceDO> {

    /**
     * 按建筑与类型查设备列表，附带点位经纬度
     *
     * @param buildingId 所属建筑主键，为 null 表示不限
     * @param deviceType 设备类型，为 null 表示不限
     * @return 设备列表
     */
    List<DeviceDO> selectDeviceList(@Param("buildingId") Long buildingId,
                                    @Param("deviceType") String deviceType);

    /**
     * 查询在线设备的主键与类型，供模拟器遍历。离线与故障设备不产生实时数据
     *
     * @return 在线设备列表，仅填充 id 与 deviceType
     */
    List<DeviceDO> selectOnlineDevices();
}
