package com.aimall.server.common;

import java.util.List;

public class PageResult<T> {

    private final List<T> list;
    private final long total;
    private final long page;
    private final long size;

    private PageResult(List<T> list, long total, long page, long size) {
        this.list = list;
        this.total = total;
        this.page = page;
        this.size = size;
    }

    public static <T> PageResult<T> of(List<T> list, long total, long page, long size) {
        return new PageResult<>(list, total, page, size);
    }

    public List<T> getList() {
        return list;
    }

    public long getTotal() {
        return total;
    }

    public long getPage() {
        return page;
    }

    public long getSize() {
        return size;
    }
}
