package com.wuhan.twin.building.vo;

import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 3D Tiles 数据源与初始视角
 *
 * @author lvfan
 */
@Data
@Schema(description = "3D Tiles 数据源与初始视角")
public class TilesetInfoVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "tileset.json 地址。为 null 表示尚未生成 3D Tiles，"
            + "前端改用 GeoJSON 拉伸白模")
    private String tilesetUrl;

    @Schema(description = "初始相机参数")
    private CameraVO camera;

    /**
     * 相机初始参数
     *
     * @author lvfan
     */
    @Data
    @Schema(description = "相机初始参数")
    public static class CameraVO implements Serializable {

        private static final long serialVersionUID = 1L;

        @Schema(description = "经度")
        private Double lon;

        @Schema(description = "纬度")
        private Double lat;

        @Schema(description = "视高，单位米")
        private Double height;

        @Schema(description = "方位角，单位度")
        private Double heading;

        @Schema(description = "俯仰角，单位度，负值表示俯视")
        private Double pitch;
    }
}
