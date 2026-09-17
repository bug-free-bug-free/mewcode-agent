// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com


package com.mewcode.permission;

import com.mewcode.tool.ToolCategory;

public enum PermissionMode {

    DEFAULT,
    ACCEPT_EDITS,
    PLAN,
    BYPASS;

    public Decision decide(ToolCategory category) {
        return switch (this) {
            case DEFAULT -> switch (category) {
                case READ -> Decision.ALLOW;
                case WRITE, COMMAND -> Decision.ASK;
            };
            case ACCEPT_EDITS -> switch (category) {
                case READ, WRITE -> Decision.ALLOW;
                case COMMAND -> Decision.ASK;
            };
            case PLAN -> DEFAULT.decide(category);
            case BYPASS -> Decision.ALLOW;
        };
    }

    public enum Decision {
        ALLOW, DENY, ASK
    }
}

