package com.wuhan.twin.device.service.impl;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wuhan.twin.alarm.mapper.AlarmMapper;
import com.wuhan.twin.building.entity.BuildingDO;
import com.wuhan.twin.building.mapper.BuildingMapper;
import com.wuhan.twin.common.enums.ObjectTypeEnum;
import com.wuhan.twin.common.exception.BizException;
import com.wuhan.twin.common.result.PageResult;
import com.wuhan.twin.common.result.ResultCodeEnum;
import com.wuhan.twin.device.dto.DevicePageQuery;
import com.wuhan.twin.device.dto.DeviceSaveDTO;
import com.wuhan.twin.device.entity.DeviceDO;
import com.wuhan.twin.device.enums.DeviceTypeEnum;
import com.wuhan.twin.device.mapper.DeviceMapper;
import com.wuhan.twin.device.mapper.DeviceRealtimeMapper;
import com.wuhan.twin.device.mapper.DeviceTelemetryMapper;
import com.wuhan.twin.device.service.DeviceService;
import com.wuhan.twin.device.vo.DevicePageVO;
import com.wuhan.twin.device.vo.DeviceVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 设备服务实现
 *
 * @author lvfan
 */
@Service
@RequiredArgsConstructor
public class DeviceServiceImpl implements DeviceService {

    /** 在线状态，与 t_device.status 的 CHECK 约束一致 */
    private static final String STATUS_ONLINE = "ONLINE";

    /** 离线状态 */
    private static final String STATUS_OFFLINE = "OFFLINE";

    /** 故障状态 */
    private static final String STATUS_FAULT = "FAULT";

    private final DeviceMapper deviceMapper;

    private final BuildingMapper buildingMapper;

    private final DeviceRealtimeMapper deviceRealtimeMapper;

    private final AlarmMapper alarmMapper;

    private final DeviceTelemetryMapper deviceTelemetryMapper;

    @Override
    public List<DeviceVO> listDevices(Long buildingId, String deviceType) {
        return deviceMapper.selectDeviceList(buildingId, normalizeType(deviceType)).stream()
                .map(DeviceServiceImpl::toVo)
                .collect(Collectors.toList());
    }

    @Override
    public PageResult<DevicePageVO> pageDevices(DevicePageQuery query) {
        Page<DeviceDO> page = new Page<>(query.getCurrent(), query.getSize());
        IPage<DeviceDO> result = deviceMapper.selectDevicePage(
                page, trimToNull(query.getKeyword()), normalizeType(query.getDeviceType()),
                normalizeStatus(query.getStatus()), query.getBuildingId());
        List<DevicePageVO> records = result.getRecords().stream()
                .map(DeviceServiceImpl::toPageVo)
                .collect(Collectors.toList());
        return PageResult.of(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    private static DeviceVO toVo(DeviceDO device) {
        DeviceVO vo = new DeviceVO();
        BeanUtils.copyProperties(device, vo);
        vo.setDeviceTypeLabel(resolveTypeLabel(device));
        return vo;
    }

    @Override
    public Long createDevice(DeviceSaveDTO dto) {
        DeviceDO device = toSaveDo(dto);
        requireBuildingExists(dto.getBuildingId());
        checkDeviceCodeUnique(dto.getDeviceCode(), null);
        try {
            deviceMapper.insertDevice(device);
        } catch (DuplicateKeyException e) {
            // 预查与插入之间仍有并发窗口，唯一约束冲突统一转为业务异常
            throw new BizException("设备编号已存在：" + dto.getDeviceCode());
        }
        return device.getId();
    }

    @Override
    public void updateDevice(Long id, DeviceSaveDTO dto) {
        requireDeviceExists(id);
        requireBuildingExists(dto.getBuildingId());
        checkDeviceCodeUnique(dto.getDeviceCode(), id);
        DeviceDO device = toSaveDo(dto);
        device.setId(id);
        try {
            if (deviceMapper.updateDevice(device) == 0) {
                // 存在性检查与更新之间的并发删除窗口极小，防御性兜底
                throw new BizException(ResultCodeEnum.NOT_FOUND, "设备不存在：" + id);
            }
        } catch (DuplicateKeyException e) {
            throw new BizException("设备编号已存在：" + dto.getDeviceCode());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDevice(Long id) {
        requireDeviceExists(id);
        String objectType = ObjectTypeEnum.DEVICE.name();
        // 无外键约束，关联数据按 object_type 限定后在事务内逐表清理
        deviceRealtimeMapper.deleteByDeviceId(id, objectType);
        alarmMapper.deleteByDeviceId(id, objectType);
        deviceTelemetryMapper.deleteByDeviceId(id, objectType);
        deviceMapper.deleteById(id);
    }

    /**
     * 组装保存用 DO：拷贝入参并手动转换 lon/lat（BeanUtils 不做 BigDecimal 到 Double）
     */
    private static DeviceDO toSaveDo(DeviceSaveDTO dto) {
        DeviceDO device = new DeviceDO();
        BeanUtils.copyProperties(dto, device);
        device.setDeviceType(normalizeType(dto.getDeviceType()));
        device.setStatus(normalizeStatus(dto.getStatus()));
        device.setLon(dto.getLon().doubleValue());
        device.setLat(dto.getLat().doubleValue());
        return device;
    }

    /**
     * 设备存在性检查，不存在抛 404
     */
    private void requireDeviceExists(Long id) {
        if (deviceMapper.selectById(id) == null) {
            throw new BizException(ResultCodeEnum.NOT_FOUND, "设备不存在：" + id);
        }
    }

    /**
     * 所属建筑存在性检查，不存在抛参数错误（入参引用了一个不存在的建筑）
     */
    private void requireBuildingExists(Long buildingId) {
        if (buildingMapper.selectById(buildingId) == null) {
            throw new BizException(ResultCodeEnum.PARAM_ERROR, "所属建筑不存在：" + buildingId);
        }
    }

    /**
     * 设备编号唯一性预查，excludeId 非空时排除自身（编辑场景）
     */
    private void checkDeviceCodeUnique(String deviceCode, Long excludeId) {
        Long count = deviceMapper.selectCount(Wrappers.<DeviceDO>lambdaQuery()
                .eq(DeviceDO::getDeviceCode, deviceCode)
                .ne(excludeId != null, DeviceDO::getId, excludeId));
        if (count != null && count > 0) {
            throw new BizException("设备编号已存在：" + deviceCode);
        }
    }

    private static DevicePageVO toPageVo(DeviceDO device) {
        DevicePageVO vo = new DevicePageVO();
        BeanUtils.copyProperties(device, vo);
        vo.setDeviceTypeLabel(resolveTypeLabel(device));
        return vo;
    }

    /**
     * 设备类型合法时返回中文名，未知类型返回 null 交给前端兜底
     */
    private static String resolveTypeLabel(DeviceDO device) {
        return DeviceTypeEnum.of(device.getDeviceType())
                .map(DeviceTypeEnum::getLabel)
                .orElse(null);
    }

    /**
     * 校验设备类型并去空，非法时抛出参数错误
     */
    private static String normalizeType(String deviceType) {
        String normalized = trimToNull(deviceType);
        if (normalized != null && DeviceTypeEnum.of(normalized).isEmpty()) {
            String supported = Arrays.stream(DeviceTypeEnum.values())
                    .map(Enum::name)
                    .collect(Collectors.joining("/"));
            throw new BizException(ResultCodeEnum.PARAM_ERROR, "设备类型不支持，可选值：" + supported);
        }
        return normalized;
    }

    /**
     * 校验运行状态并去空，非法时抛出参数错误
     */
    private static String normalizeStatus(String status) {
        String normalized = trimToNull(status);
        if (normalized != null && !STATUS_ONLINE.equals(normalized)
                && !STATUS_OFFLINE.equals(normalized) && !STATUS_FAULT.equals(normalized)) {
            throw new BizException(ResultCodeEnum.PARAM_ERROR, "设备状态不支持，可选值：ONLINE/OFFLINE/FAULT");
        }
        return normalized;
    }

    /**
     * 空白参数统一归一为 null，交给 SQL 的 if 判断
     */
    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
