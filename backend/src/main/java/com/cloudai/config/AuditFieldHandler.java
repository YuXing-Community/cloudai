package com.cloudai.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * updatedAt 自动维护：实体从库里读出后 updatedAt 带旧值，updateById 会原样写回，
 * MySQL 的 ON UPDATE CURRENT_TIMESTAMP 因“显式赋值”而不触发，所以这里每次更新都覆盖为当前时间。
 */
@Component
public class AuditFieldHandler implements MetaObjectHandler {
    @Override
    public void insertFill(MetaObject metaObject) {
        if (metaObject.hasSetter("updatedAt") && getFieldValByName("updatedAt", metaObject) == null) {
            setFieldValByName("updatedAt", LocalDateTime.now(), metaObject);
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        if (metaObject.hasSetter("updatedAt")) setFieldValByName("updatedAt", LocalDateTime.now(), metaObject);
    }
}
