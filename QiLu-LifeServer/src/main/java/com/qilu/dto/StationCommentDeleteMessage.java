package com.qilu.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StationCommentDeleteMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private String messageId;

    private Long stationId;

    private Long rootCommentId;

    private Long deletedBy;
}
