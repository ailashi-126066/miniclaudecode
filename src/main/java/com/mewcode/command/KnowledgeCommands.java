package com.mewcode.command;

import com.mewcode.command.Command.CommandType;
import com.mewcode.rag.KnowledgeRagService;
import com.mewcode.rag.tool.KnowledgeSearchTool;
import java.nio.file.Path;
import java.util.Locale;

/** Registers the user-controlled private knowledge-base command. */
public final class KnowledgeCommands {
    private KnowledgeCommands() {}

    public static void register(CommandRegistry registry) {
        if (registry.find("knowledge").isPresent()) return;
        registry.register(new Command(
                "knowledge",
                "Index, inspect or search private knowledge documents",
                new String[] {"kb"},
                CommandType.LOCAL,
                false),
                KnowledgeCommands::execute);
    }

    private static String execute(CommandContext context) {
        KnowledgeRagService knowledge = new KnowledgeRagService(Path.of(context.workDir()));
        String args = context.args() == null ? "" : context.args().strip();
        String command = args.isEmpty() ? "status" : args.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        try {
            return switch (command) {
                case "index", "sync" -> "Knowledge index synchronized: " + knowledge.synchronize();
                case "status" -> formatStatus(knowledge.status());
                default -> search(knowledge, args);
            };
        } catch (Exception error) {
            return "Knowledge command failed: " + error.getMessage();
        }
    }

    private static String formatStatus(KnowledgeRagService.Status status) {
        if (!status.indexed()) {
            return "Knowledge index missing. Add documents to " + status.knowledgeRoot()
                    + " and run /knowledge index.";
        }
        return "Knowledge index: " + (status.stale() ? "STALE" : "READY")
                + "\nDocuments: " + status.documents()
                + "\nChunks: " + status.chunks()
                + "\nSource: " + status.knowledgeRoot();
    }

    private static String search(KnowledgeRagService knowledge, String query) throws Exception {
        var status = knowledge.status();
        if (!status.indexed()) return formatStatus(status);
        if (status.stale()) return "Knowledge index is stale. Run /knowledge index before searching.";
        var response = knowledge.search(query);
        return response.results().isEmpty() ? "No relevant knowledge found."
                : KnowledgeSearchTool.render(response.results());
    }
}
