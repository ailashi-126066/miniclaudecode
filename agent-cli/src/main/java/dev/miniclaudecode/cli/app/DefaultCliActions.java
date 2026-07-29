package dev.miniclaudecode.cli.app;

import dev.miniclaudecode.cli.AgentCompleter;
import dev.miniclaudecode.cli.CliActions;
import dev.miniclaudecode.cli.Repl;
import dev.miniclaudecode.cli.Repl.TurnOutcome;
import dev.miniclaudecode.cli.ReplHeader;
import dev.miniclaudecode.cli.SessionCommandHandler;
import dev.miniclaudecode.cli.StreamingRenderer.RenderEvent;
import dev.miniclaudecode.cli.StreamingRenderer.RenderEvent.Completed;
import dev.miniclaudecode.cli.StreamingRenderer.RenderEvent.Error;
import dev.miniclaudecode.cli.StreamingRenderer.RenderEvent.Progress;
import dev.miniclaudecode.cli.StreamingRenderer.RenderEvent.Text;
import dev.miniclaudecode.cli.StreamingRenderer.RenderEvent.Thinking;
import dev.miniclaudecode.domain.approval.ApprovalRequest;
import dev.miniclaudecode.domain.runtime.CancellationToken;
import dev.miniclaudecode.persistence.config.ProviderProfile;
import dev.miniclaudecode.persistence.config.UserConfigWriter;
import dev.miniclaudecode.persistence.path.UserDataLayout;
import dev.miniclaudecode.rag.eval.RagEvaluator;
import dev.miniclaudecode.rag.eval.RagEvaluator.EvaluationReport;
import dev.miniclaudecode.rag.index.LuceneCodeIndex.IndexStats;
import dev.miniclaudecode.rag.index.LuceneCodeIndex.UpdateReport;
import dev.miniclaudecode.rag.search.HybridCodeSearcher.SearchOptions;
import dev.miniclaudecode.rag.search.HybridCodeSearcher.SearchResponse;
import dev.miniclaudecode.rag.search.RetrievalHit;
import dev.miniclaudecode.rag.search.SearchResult;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

public final class DefaultCliActions implements CliActions {
  public static final String FAKE_RESPONSE_ENV = "MINICLAUDE_FAKE_RESPONSE";

  // WHY: `rag eval` must compare bm25, vector and hybrid at the SAME effective top-k or the
  // published comparison is meaningless. The bm25 and vector routes ask their retrievers for 10
  // hits, but hybrid used SearchOptions.defaults() (topK 8 plus a 6000-token budget), so
  // RagEvaluator.recallAt(..., 10) capped hybrid Recall@10 at 0.8 by construction while BM25 could
  // reach 1.0, and the token budget could truncate hybrid further. All three routes now get 10, and
  // the eval budget is deliberately large enough never to truncate. Eval only: the interactive
  // code_search tool still passes its own caller-supplied SearchOptions.
  private static final int EVAL_TOP_K = 10;
  private static final int EVAL_TOKEN_BUDGET = 1_000_000;
  private static final int EVAL_CANDIDATE_LIMIT = 40;

  private final UserDataLayout layout;
  private final Map<String, String> environment;
  private final PrintWriter output;
  private final PrintWriter error;

  public DefaultCliActions() {
    this(
        UserDataLayout.systemDefault(),
        System.getenv(),
        new PrintWriter(System.out, true, StandardCharsets.UTF_8),
        new PrintWriter(System.err, true, StandardCharsets.UTF_8));
  }

  DefaultCliActions(
      UserDataLayout layout,
      Map<String, String> environment,
      PrintWriter output,
      PrintWriter error) {
    this.layout = layout;
    this.environment = Map.copyOf(environment);
    this.output = output;
    this.error = error;
  }

  public int configure() {
    try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
      LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
      String result = this.configurationWizard().run(reader);
      terminal.writer().println(result);
      terminal.flush();
      return 0;
    } catch (RuntimeException | IOException var7) {
      return this.failed(var7);
    }
  }

  public int interactive(Path workspace) {
    try (WorkspaceComponents components = this.components(workspace)) {
      AtomicReference<SessionCommandHandler> commands = new AtomicReference<>();
      ApplicationSession session =
          new ApplicationSession(
              components, () -> selection(commands.get(), components), Clock.systemUTC());
      SessionCommandHandler commandHandler =
          new SessionCommandHandler(
              components.providerModels(),
              components.config().activeProvider(),
              components.config().activeProfile().model(),
              components.config().activeProfile().thinking(),
              toolNames(components),
              session::status,
              session::usage,
              session::sessions,
              components::mcpStatus,
              () -> skills(components),
              session::compact,
              session::switchTo,
              this.layout.configFile());
      commands.set(commandHandler);

      try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
        AgentCompleter completer =
            new AgentCompleter(
                components.workspace(),
                () -> components.providerModels().keySet(),
                () -> components.providerModels().get(commandHandler.activeProvider()),
                () -> toolNames(components));
        Repl.create(
                terminal,
                this.layout.historyFile(),
                completer,
                commandHandler,
                session,
                reader -> this.configurationWizard().run(reader))
            .withHeader(
                ReplHeader.render(
                    terminal,
                    commandHandler.activeProvider(),
                    commandHandler.activeModel(),
                    commandHandler.thinkingEnabled(),
                    components.workspace().toString()))
            .run();
      }
      return 0;
    } catch (RuntimeException | IOException var13) {
      return this.failed(var13);
    }
  }

  public int run(Path workspace, String prompt) {
    try (WorkspaceComponents components = this.components(workspace)) {
      ProviderProfile profile = components.config().activeProfile();
      ApplicationSession session =
          new ApplicationSession(
              components,
              () ->
                  new ApplicationSession.TurnSelection(
                      components.config().activeProvider(), profile.model(), profile.thinking()),
              Clock.systemUTC());
      AtomicReference<ApprovalRequest> approval = new AtomicReference<>();
      TurnOutcome outcome =
          session
              .start(prompt, new CancellationToken(), this::renderNonInteractive)
              .toCompletableFuture()
              .join();
      outcome.approvalRequest().ifPresent(approval::set);
      if (approval.get() != null) {
        this.error.println("Approval required: " + approval.get().reason());
        this.error.println("Target: " + approval.get().target());
        return 3;
      }
      return exitCode(outcome);
    } catch (CompletionException var11) {
      return this.failed((Throwable) (var11.getCause() == null ? var11 : var11.getCause()));
    } catch (RuntimeException | IOException var12) {
      return this.failed(var12);
    }
  }

  public int index(Path workspace) {
    try (WorkspaceComponents components = this.components(workspace)) {
      UpdateReport report = components.codeIndex().synchronize(components.workspace());
      this.output.printf(
          "Indexed %d files: %d updated, %d unchanged, %d deleted, %d chunks written%n",
          report.files(),
          report.updatedFiles(),
          report.unchangedFiles(),
          report.deletedFiles(),
          report.writtenChunks());
      return 0;
    } catch (RuntimeException | IOException var7) {
      return this.failed(var7);
    }
  }

  public int rag(Path workspace, String query) {
    try (WorkspaceComponents components = this.components(workspace)) {
      components.codeIndex().synchronize(components.workspace());
      String trimmed = query.trim();
      if (trimmed.equalsIgnoreCase("stats")) {
        IndexStats stats = components.codeIndex().stats();
        this.output.printf(
            "files=%d chunks=%d vectorDimensions=%d%n",
            stats.files(), stats.chunks(), stats.vectorDimensions());
        return 0;
      }

      if (trimmed.toLowerCase(Locale.ROOT).startsWith("eval ")) {
        return this.evaluate(components, Path.of(trimmed.substring(5).trim()));
      }

      boolean explain = trimmed.toLowerCase(Locale.ROOT).startsWith("explain ");
      String actualQuery = explain ? trimmed.substring(8).trim() : trimmed;
      SearchResponse response = components.searcher().search(actualQuery);
      if (explain) {
        this.output.println(response.explain());
      } else if (response.results().isEmpty()) {
        this.output.println("No relevant code found.");
      } else {
        response.results().forEach(this::printResult);
      }
      return 0;
    } catch (RuntimeException | IOException var11) {
      return this.failed(var11);
    }
  }

  private int evaluate(WorkspaceComponents components, Path fixture) throws IOException {
    RagEvaluator evaluator = new RagEvaluator();
    EvaluationReport report =
        evaluator.evaluate(
            evaluator.load(fixture),
            Map.of(
                "bm25",
                query -> results(components.bm25().search(query, EVAL_TOP_K)),
                "vector",
                query -> results(components.vector().search(query, EVAL_TOP_K)),
                "hybrid",
                query -> components.searcher().search(query, evalSearchOptions()).results()));
    report
        .strategies()
        .forEach(
            (name, metrics) ->
                this.output.printf(
                    "%s recall@5=%.3f recall@10=%.3f MRR=%.3f p50=%dms p95=%dms cases=%d%n",
                    name,
                    metrics.recallAt5(),
                    metrics.recallAt10(),
                    metrics.meanReciprocalRank(),
                    metrics.p50LatencyMillis(),
                    metrics.p95LatencyMillis(),
                    metrics.cases()));
    return 0;
  }

  private static SearchOptions evalSearchOptions() {
    return new SearchOptions(EVAL_TOP_K, EVAL_TOKEN_BUDGET, EVAL_CANDIDATE_LIMIT);
  }

  private static List<SearchResult> results(List<RetrievalHit> hits) {
    return hits.stream()
        .map(
            hit ->
                new SearchResult(
                    hit.chunk(),
                    Math.max(0.0, hit.score()),
                    Map.of(hit.route(), hit.rank()),
                    Map.of(hit.route(), hit.score())))
        .toList();
  }

  private void printResult(SearchResult result) {
    this.output.printf(
        "%s:%d-%d %s [%s]%n%s%n%n",
        result.chunk().path(),
        result.chunk().startLine(),
        result.chunk().endLine(),
        result.chunk().symbol(),
        result.explanation(),
        result.chunk().content());
  }

  private WorkspaceComponents components(Path workspace) throws IOException {
    return WorkspaceComponents.create(
        workspace,
        this.layout,
        this.environment,
        Optional.ofNullable(this.environment.get("MINICLAUDE_FAKE_RESPONSE")));
  }

  private ConfigurationWizard configurationWizard() {
    return new ConfigurationWizard(this.layout.configFile(), new UserConfigWriter());
  }

  private void renderNonInteractive(RenderEvent event) {
    Objects.requireNonNull(event);
    switch (event) {
      case Thinking thinking:
        this.output.println("thinking> " + thinking.text());
        break;
      case Progress progress:
        this.error.println("... " + progress.text());
        break;
      case Text text:
        this.output.print(text.text());
        this.output.flush();
        break;
      case Error failure:
        this.error.println("error: " + failure.text());
        break;
      case Completed ignored:
        this.output.println();
        break;
      default:
        throw new MatchException(null, null);
    }
  }

  private int failed(Throwable exception) {
    this.error.println(
        "MiniClaudeCode: "
            + Objects.requireNonNullElse(
                exception.getMessage(), exception.getClass().getSimpleName()));
    return 2;
  }

  private static ApplicationSession.TurnSelection selection(
      SessionCommandHandler commands, WorkspaceComponents components) {
    if (commands == null) {
      ProviderProfile profile = components.config().activeProfile();
      return new ApplicationSession.TurnSelection(
          components.config().activeProvider(), profile.model(), profile.thinking());
    } else {
      return new ApplicationSession.TurnSelection(
          commands.activeProvider(), commands.activeModel(), commands.thinkingEnabled());
    }
  }

  static int exitCode(TurnOutcome outcome) {
    Objects.requireNonNull(outcome, "outcome must not be null");
    return switch (outcome.status()) {
      case COMPLETED -> 0;
      case WAITING_APPROVAL -> 3;
      case CANCELLED -> 130;
      case FAILED, RUNNING -> 2;
    };
  }

  private static List<String> toolNames(WorkspaceComponents components) {
    return components.tools().descriptors().stream().map(value -> value.qualifiedName()).toList();
  }

  private static String skills(WorkspaceComponents components) {
    return components.skills().list().stream()
        .map(value -> value.name() + " - " + value.description())
        .reduce((left, right) -> left + System.lineSeparator() + right)
        .orElse("(none)");
  }
}
