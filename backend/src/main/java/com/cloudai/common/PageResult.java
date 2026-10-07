package com.cloudai.common;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.function.Function;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {
    private List<T> records;
    private long total;
    private long page;
    private long size;

    public static <T> PageResult<T> of(IPage<T> p) {
        return new PageResult<>(p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize());
    }

    public static <S, T> PageResult<T> of(IPage<S> p, Function<S, T> mapper) {
        return new PageResult<>(p.getRecords().stream().map(mapper).toList(), p.getTotal(), p.getCurrent(), p.getSize());
    }

    public static <T> PageResult<T> ofList(List<T> all, long page, long size) {
        int from = (int) Math.min(all.size(), (page - 1) * size);
        int to = (int) Math.min(all.size(), from + size);
        return new PageResult<>(all.subList(from, to), all.size(), page, size);
    }
}
