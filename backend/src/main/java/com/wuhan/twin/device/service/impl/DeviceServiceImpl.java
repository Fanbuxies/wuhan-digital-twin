package com.wuhan.twin.device.service.impl;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import com.wuhan.twin.common.exception.BizException;
import com.wuhan.twin.common.result.ResultCode;
import com.wuhan.twin.device.entity.DeviceDO;
import com.wuhan.twin.device.enums.DeviceTypeEnum;
import com.wuhan.twin.device.mapper.DeviceMapper;
import com.wuhan.twin.device.service.DeviceService;
import com.wuhan.twin.device.vo.DeviceVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 设备服务实现
 *
 * @author lvfan
 */
@Service
@RequiredArgsConstructor
public class DeviceServiceImpl implements DeviceService {

    private final DeviceMapper deviceMapper;

    @Override
    public List<DeviceVO> listDevices(Long buildingId, String deviceType) {
        String normalizedType = StringUtils.hasText(deviceType) ? deviceType.trim() : null;
        if (normalizedType != null && DeviceTypeEnum.of(normalizedType).isEmpty()) {
            String supported = Arrays.stream(DeviceTypeEnum.values())
                    .map(Enum::name)
                    .collect(Collectors.joining("/"));
            throw new BizException(ResultCode.PARAM_ERROR, "设备类型不支持，可选值：" + supported);
        }
        return deviceMapper.selectDeviceList(buildingId, normalizedType).stream()
                .map(DeviceServiceImpl::toVo)
                .collect(Collectors.toList());
    }

    private static DeviceVO toVo(DeviceDO device) {
        DeviceVO vo = new DeviceVO();
        BeanUtils.copyProperties(device, vo);
        DeviceTypeEnum.of(device.getDeviceType())
                .ifPresent(item -> vo.setDeviceTypeLabel(item.getLabel()));
        return vo;
    }
}
