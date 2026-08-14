package com.wuhan.twin.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置
 *
 * <p>mapper 接口各自使用 @Mapper 注解，故不在此处声明 @MapperScan。</p>
 *
 * @author lvfan
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 单页最大条数，超出后强制截断，防止恶意大分页
     */
    private static final long MAX_LIMIT = 500L;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.POSTGRE_SQL);
        // 页码超出总页数时返回空列表，不回到首页
        pagination.setOverflow(false);
        pagination.setMaxLimit(MAX_LIMIT);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
