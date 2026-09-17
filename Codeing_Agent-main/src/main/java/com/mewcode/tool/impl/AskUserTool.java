// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.tool.impl;

import com.mewcode.agent.AgentEvent;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolResult;
import com.mewcode.tui.dialog.AskUserDialog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class AskUserTool implements Tool {

    private static final String DESCRIPTION = """
            Ask the user a question with structured multiple-choice options. Use this to:
            - Gather user preferences or requirements
            - Clarify ambiguous instructions
            - Get decisions on implementation choices
            - Offer choices about direction to take

            Each question has 2-4 options. An "Other" option for custom input is automatically provided.
            Use multiSelect: true when choices are not mutually exclusive.""";

    private BlockingQueue<AgentEvent> eventQueue;

    public void setEventQueue(BlockingQueue<AgentEvent> queue) {
        this.eventQueue = queue;
    }

    @Override
    public String name() {
        return "AskUserQuestion";
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    @Override
    public boolean shouldDefer() {
        return true;
    }

    @Override
    public Map<String, Object> schema() {
        var optionProperties = new LinkedHashMap<String, Object>();
        optionProperties.put("label", Map.of("type", "string", "description", "Option display text (1-5 words)"));
        optionProperties.put("description", Map.of("type", "string", "description", "What this option means"));

        var optionSchema = new LinkedHashMap<String, Object>();
        optionSchema.put("type", "object");
        optionSchema.put("properties", optionProperties);
        optionSchema.put("required", List.of("label", "description"));

        var optionsArray = new LinkedHashMap<String, Object>();
        optionsArray.put("type", "array");
        optionsArray.put("items", optionSchema);
        optionsArray.put("minItems", 2);
        optionsArray.put("maxItems", 4);

        var questionProperties = new LinkedHashMap<String, Object>();
        questionProperties.put("question", Map.of("type", "string", "description", "The question to ask the user"));
        questionProperties.put("header", Map.of("type", "string", "description", "Short label (max 12 chars)"));
        questionProperties.put("options", optionsArray);
        questionProperties.put("multiSelect", Map.of("type", "boolean", "default", false));

        var questionSchema = new LinkedHashMap<String, Object>();
        questionSchema.put("type", "object");
        questionSchema.put("properties", questionProperties);
        questionSchema.put("required", List.of("question", "header", "options", "multiSelect"));

        var questionsArray = new LinkedHashMap<String, Object>();
        questionsArray.put("type", "array");
        questionsArray.put("items", questionSchema);
        questionsArray.put("minItems", 1);
        questionsArray.put("maxItems", 4);

        var inputSchemaProperties = new LinkedHashMap<String, Object>();
        inputSchemaProperties.put("questions", questionsArray);

        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", inputSchemaProperties,
                        "required", List.of("questions")
                )
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(Map<String, Object> args) {
        if (eventQueue == null) {
            return ToolResult.error("AskUser tool not wired to event queue");
        }

        var rawQuestions = (List<Map<String, Object>>) args.get("questions");
        if (rawQuestions == null || rawQuestions.isEmpty()) {
            return ToolResult.error("No questions provided");
        }

        var questions = new ArrayList<AskUserDialog.Question>();
        for (var rq : rawQuestions) {
            String text = (String) rq.get("question");
            String header = (String) rq.get("header");
            boolean multiSelect = Boolean.TRUE.equals(rq.get("multiSelect"));

            var rawOpts = (List<Map<String, Object>>) rq.get("options");
            var options = new ArrayList<AskUserDialog.Option>();
            if (rawOpts != null) {
                for (var ro : rawOpts) {
                    options.add(new AskUserDialog.Option(
                            (String) ro.get("label"),
                            (String) ro.get("description")));
                }
            }
            questions.add(new AskUserDialog.Question(text, header, options, multiSelect));
        }

        var future = new CompletableFuture<Map<String, String>>();
        try {
            eventQueue.put(new AgentEvent.AskUserRequestEvent(questions, future));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Interrupted while sending ask-user request");
        }

        Map<String, String> answers;
        try {
            answers = future.get(5, TimeUnit.MINUTES);
        } catch (Exception e) {
            return ToolResult.error("User did not respond in time");
        }

        if (answers.containsKey("_declined")) {
            return ToolResult.error("User declined to answer");
        }

        var sb = new StringBuilder();
        sb.append("User answers:\n");
        for (var entry : answers.entrySet()) {
            sb.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        return ToolResult.success(sb.toString());
    }
}
