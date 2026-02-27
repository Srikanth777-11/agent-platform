package com.agentplatform.analysis.agent;

import com.agentplatform.common.model.AnalysisResult;
import com.agentplatform.common.model.Context;

public interface AnalysisAgent {
    AnalysisResult analyze(Context context);
    String agentName();
}
