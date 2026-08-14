package com.wuhan.twin.common.result;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分页结果包装，作为 R 的 data 使用
 *
 * @param <T> 记录类型
 * @author lvfan
 */
@Data
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 当页记录
     */
    private List<T> records;

    /**
     * 总记录数
     */
    private long total;

    /**
     * 当前页码，从 1 开始
     */
    private long current;

    /**
     * 每页条数
     */
    private long size;

    /**
     * 由 MyBatis-Plus 分页对象转换
     */
    public static <T> PageResult<T> of(IPage<T> page) {
        return new PageResult<>(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * 由已有集合手工组装，用于非 MyBatis-Plus 分页场景
     */
    public static <T> PageResult<T> of(List<T> records, long total, long current, long size) {
        return new PageResult<>(records == null ? Collections.emptyList() : records, total, current, size);
    }
}
