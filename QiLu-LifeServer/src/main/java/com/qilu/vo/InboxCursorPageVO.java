package com.qilu.vo;

import lombok.Data;

import java.util.List;

@Data
public class InboxCursorPageVO<T> {

    private List<T> records;
    private Long nextCursor;
    private Boolean hasMore;

    public InboxCursorPageVO(List<T> records, Long nextCursor, Boolean hasMore) {
        this.records = records;
        this.nextCursor = nextCursor;
        this.hasMore = hasMore;
    }
}
