package com.wuhan.twin.device.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
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

    /**
     * 设备分页查询，附带点位经纬度。keyword / deviceType / status 为 null
     * 表示不限，buildingId 为 null 表示不限，由 service 层统一去空
     *
     * @param page       分页对象，由分页拦截器处理 count 与 limit
     * @param keyword    关键字，按设备名称或编号模糊匹配
     * @param deviceType 设备类型
     * @param status     运行状态
     * @param buildingId 所属建筑主键
     * @return 当页设备，仅填充列表所需字段
     */
    IPage<DeviceDO> selectDevicePage(IPage<DeviceDO> page,
                                     @Param("keyword") String keyword,
                                     @Param("deviceType") String deviceType,
                                     @Param("status") String status,
                                     @Param("buildingId") Long buildingId);

    /**
     * 新增设备，location 在 SQL 侧按点位经纬度构造
     *
     * @param device 设备入参，lon/lat 必填，id 由数据库 bigserial 回填
     * @return 影响行数
     */
    int insertDevice(@Param("device") DeviceDO device);

    /**
     * 按主键整体更新表单可编辑字段，location 按新点位重建。
     * install_time 与 created_at 不在此处更新
     *
     * @param device 设备入参，lon/lat 必填
     * @return 影响行数
     */
    int updateDevice(@Param("device") DeviceDO device);
}
