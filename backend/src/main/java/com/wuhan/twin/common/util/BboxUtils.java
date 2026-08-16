package com.wuhan.twin.common.util;

import com.wuhan.twin.common.exception.BizException;
import com.wuhan.twin.common.result.ResultCodeEnum;
import org.springframework.util.StringUtils;

/**
 * bbox 查询参数解析工具
 *
 * <p>建筑 GeoJSON 与市政设施列表都按视口范围过滤，校验规则完全一致，故集中在此，
 * 避免同一段边界校验在多个 service 里各写一份。</p>
 *
 * @author lvfan
 */
public final class BboxUtils {

    /**
     * bbox 参数的分段数：west,south,east,north
     */
    private static final int BBOX_PART_COUNT = 4;

    private static final String BBOX_SEPARATOR = ",";

    private static final double LON_MIN = -180.0D;

    private static final double LON_MAX = 180.0D;

    private static final double LAT_MIN = -90.0D;

    private static final double LAT_MAX = 90.0D;

    private BboxUtils() {
    }

    /**
     * 解析并校验 bbox
     *
     * @param bbox 视口范围，格式 west,south,east,north，为空表示不限范围
     * @return 四个边界，入参为空时四值均为 null
     * @throws BizException 分段数、数值格式、取值范围或大小关系非法时抛出
     */
    public static Bbox parse(String bbox) {
        if (!StringUtils.hasText(bbox)) {
            return new Bbox(null, null, null, null);
        }
        String[] parts = bbox.split(BBOX_SEPARATOR);
        if (parts.length != BBOX_PART_COUNT) {
            throw new BizException(ResultCodeEnum.PARAM_ERROR, "bbox 需为 west,south,east,north 四个数值");
        }
        double[] values = new double[BBOX_PART_COUNT];
        for (int i = 0; i < BBOX_PART_COUNT; i++) {
            try {
                values[i] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException e) {
                throw new BizException(ResultCodeEnum.PARAM_ERROR, "bbox 含非数值内容：" + parts[i].trim());
            }
        }
        double west = values[0];
        double south = values[1];
        double east = values[2];
        double north = values[3];
        boolean lonInRange = west >= LON_MIN && west <= LON_MAX && east >= LON_MIN && east <= LON_MAX;
        boolean latInRange = south >= LAT_MIN && south <= LAT_MAX && north >= LAT_MIN && north <= LAT_MAX;
        if (!lonInRange || !latInRange) {
            throw new BizException(ResultCodeEnum.PARAM_ERROR, "bbox 经纬度超出取值范围");
        }
        if (west >= east || south >= north) {
            throw new BizException(ResultCodeEnum.PARAM_ERROR, "bbox 需满足 west < east 且 south < north");
        }
        return new Bbox(west, south, east, north);
    }

    /**
     * bbox 四个边界，四值同时有效或同时为 null
     *
     * @param west  西边界经度
     * @param south 南边界纬度
     * @param east  东边界经度
     * @param north 北边界纬度
     */
    public record Bbox(Double west, Double south, Double east, Double north) {
    }
}
