package com.example.codereview.agent.orchestration.steps;

import com.example.codereview.agent.error.AgentFailureType;
import com.example.codereview.agent.orchestration.AgentAnalysisContextRepository;
import com.example.codereview.agent.orchestration.AgentChangeAnalysisCheckpoint;
import com.example.codereview.agent.orchestration.AgentStepExecutionContext;
import com.example.codereview.agent.orchestration.AgentStepExecutor;
import com.example.codereview.agent.orchestration.AgentStepResult;
import com.example.codereview.agent.queue.AgentStepExecutionException;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.language.ChangeAnalysis;
import com.example.codereview.language.ChangeSet;
import com.example.codereview.language.LanguagePlugin;
import com.example.codereview.language.LanguagePluginSelector;
import com.example.codereview.language.RepositoryProfile;
import com.example.codereview.language.LanguageToolFindingNormalizer;
import com.example.codereview.agent.tool.AgentToolRegistry;
import com.example.codereview.agent.tool.ToolContext;
import com.example.codereview.agent.tool.ToolResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class AnalyzingChangeStepExecutor implements AgentStepExecutor {

    private final AgentAnalysisContextRepository contexts;
    private final LanguagePluginSelector plugins;
    private final ObjectMapper mapper;
    private final AgentToolRegistry tools;
    private final LanguageToolFindingNormalizer normalizer;

    @Autowired
    public AnalyzingChangeStepExecutor(
            AgentAnalysisContextRepository contexts,
            LanguagePluginSelector plugins,
            ObjectMapper mapper,
            AgentToolRegistry tools,
            LanguageToolFindingNormalizer normalizer
    ) {
        this.contexts = contexts;
        this.plugins = plugins;
        this.mapper = mapper;
        this.tools = tools;
        this.normalizer = normalizer;
    }

    public AnalyzingChangeStepExecutor(
            AgentAnalysisContextRepository contexts,
            LanguagePluginSelector plugins,
            ObjectMapper mapper
    ) {
        this(contexts, plugins, mapper, null, null);
    }

    public AnalyzingChangeStepExecutor() {
        this.contexts = null;
        this.plugins = null;
        this.mapper = null;
        this.tools = null;
        this.normalizer = null;
    }

    @Override
    public AgentRunStatus state() {
        return AgentRunStatus.ANALYZING_CHANGE;
    }

    @Override
    public AgentStepResult execute(AgentStepExecutionContext context) {
        if (context.stepType() != state()) {
            throw new IllegalArgumentException("Executor state does not match context");
        }
        if (contexts == null) {
            return AgentStepResult.checkpoint(state());
        }
        var analysisContext = contexts.findByAgentRunId(context.agentRunId())
                .orElseThrow(() -> new AgentStepExecutionException(
                        AgentFailureType.ENVIRONMENT_INCOMPLETE, "Repository preparation checkpoint is missing"
                ));
        analysisContext.requireHead(context.headSha());
        try {
            RepositoryProfile profile = mapper.readValue(
                    analysisContext.getRepositoryProfileJson(), RepositoryProfile.class
            );
            ChangeSet changeSet = new ChangeSet(
                    analysisContext.getBaseSha(),
                    context.headSha(),
                    parseChanges(analysisContext.getChangedDiff())
            );
            List<LanguagePlugin> selected = plugins.select(profile, changeSet);
            List<ChangeAnalysis> declared = selected.stream()
                    .map(plugin -> plugin.analyze(profile, changeSet))
                    .toList();
            List<ChangeAnalysis> analyses = executeCommands(
                    declared, analysisContext.getArchiveRef(), context
            );
            AgentChangeAnalysisCheckpoint checkpoint = new AgentChangeAnalysisCheckpoint(
                    "agent-change-analysis-v1", changeSet,
                    selected.stream().map(LanguagePlugin::id).toList(), analyses
            );
            analysisContext.changeAnalyzed(mapper.writeValueAsString(checkpoint));
            contexts.save(analysisContext);
            return new AgentStepResult(
                    "agent-step-result-v1", state(), AgentStepResult.Disposition.ADVANCE,
                    AgentRunStatus.PLANNING,
                    Map.of("plugins", checkpoint.pluginIds(), "changedFiles", changeSet.files().size())
            );
        } catch (JsonProcessingException ex) {
            throw new AgentStepExecutionException(
                    AgentFailureType.INTERNAL_ERROR, "Change analysis checkpoint is invalid", ex
            );
        }
    }

    private List<ChangeAnalysis> executeCommands(
            List<ChangeAnalysis> declared,
            String archiveRef,
            AgentStepExecutionContext context
    ) {
        if (tools == null) {
            return declared;
        }
        List<ChangeAnalysis> completed = new ArrayList<>();
        for (ChangeAnalysis analysis : declared) {
            List<com.example.codereview.finding.FindingCandidate> candidates = new ArrayList<>();
            List<String> environment = new ArrayList<>(analysis.environmentResults());
            for (com.example.codereview.language.ToolCommand command : analysis.commands()) {
                com.fasterxml.jackson.databind.node.ObjectNode input = mapper.createObjectNode()
                        .put("archiveRef", archiveRef)
                        .put("commandId", command.commandId());
                input.set("arguments", mapper.valueToTree(command.arguments()));
                input.put("imageDigest", command.imageDigest());
                ToolResult<?> result = tools.execute(
                        "language.command", input,
                        new ToolContext(
                                context.agentRunId(), context.agentStepId(),
                                "analysis:" + context.agentRunId() + ":" + command.commandId()
                                        + ":" + context.headSha(),
                                false, context.traceId()
                        )
                );
                if (!result.success()) {
                    environment.add(command.commandId() + ":" + result.error());
                    continue;
                }
                com.fasterxml.jackson.databind.JsonNode data = mapper.valueToTree(result.data());
                String output = data.path("output").asText("");
                try {
                    candidates.addAll(normalizer.normalize(
                            command.commandId(), output, context.headSha()
                    ));
                } catch (IllegalArgumentException invalidOutput) {
                    environment.add(command.commandId() + ":INVALID_OUTPUT");
                }
            }
            completed.add(new ChangeAnalysis(
                    analysis.pluginId(), analysis.commands(), candidates, environment
            ));
        }
        return List.copyOf(completed);
    }

    static List<ChangeSet.FileChange> parseChanges(String diff) {
        List<ChangeSet.FileChange> changes = new ArrayList<>();
        String previous = null;
        for (String line : diff.lines().toList()) {
            if (line.startsWith("--- ")) {
                previous = clean(line.substring(4));
            } else if (line.startsWith("+++ ")) {
                String current = clean(line.substring(4));
                if ("/dev/null".equals(current) && previous != null && !"/dev/null".equals(previous)) {
                    changes.add(new ChangeSet.FileChange(previous, ChangeSet.ChangeType.DELETED));
                } else if (!"/dev/null".equals(current)) {
                    ChangeSet.ChangeType type = "/dev/null".equals(previous)
                            ? ChangeSet.ChangeType.ADDED : ChangeSet.ChangeType.MODIFIED;
                    changes.add(new ChangeSet.FileChange(current, type));
                }
                previous = null;
            }
        }
        return changes.stream().distinct().toList();
    }

    private static String clean(String value) {
        String path = value.trim().split("\\t", 2)[0];
        return path.startsWith("a/") || path.startsWith("b/") ? path.substring(2) : path;
    }

}
