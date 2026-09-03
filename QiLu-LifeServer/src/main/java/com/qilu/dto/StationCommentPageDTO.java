package com.qilu.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.util.List;

@Data
public class StationCommentPageDTO {

    private List<StationCommentDTO> list;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long nextCursor;

    private Double nextCursorScore;

    private Integer offset;

    private Boolean hasMore;
}
