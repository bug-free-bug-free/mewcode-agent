// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.config;

public class HookConfig {

    private String id;
    private String event;
    private String condition;
    private String type;
    private String command;

    private String message;
    private boolean reject;

    public String getId() { return id; }

    public void setId(String id) { this.id = id; }

    public String getEvent() { return event; }

    public void setEvent(String event) { this.event = event; }

    public String getCondition() { return condition; }
    public void setCondition(String condition) { this.condition = condition; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public boolean isReject() { return reject; }
    public void setReject(boolean reject) { this.reject = reject; }
}
