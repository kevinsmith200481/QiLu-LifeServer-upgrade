package com.qilu.dto.ai;

import com.qilu.ai.api.dto.CampusMemoryDTO;
import com.qilu.ai.api.dto.CampusMemoryDiagnosticsDTO;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** 主服务内部的 Memory 与无正文诊断组合。 */
@Getter
@AllArgsConstructor
public class AiMemoryBuildResult {

    private final CampusMemoryDTO memory;
    private final CampusMemoryDiagnosticsDTO diagnostics;
}
