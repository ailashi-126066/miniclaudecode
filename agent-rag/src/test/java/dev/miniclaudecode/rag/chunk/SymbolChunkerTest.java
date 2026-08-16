package dev.miniclaudecode.rag.chunk;

import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The symbol these chunks carry is the point of the class: it is boosted 3x in the BM25 query and
 * weighted 5.0 in the reranker, so an empty symbol is what kept non-Java code out of the top ranks.
 */
class SymbolChunkerTest {

  private final SymbolChunker chunker = new SymbolChunker(new LangChainDocumentChunker());

  @Test
  void pythonMethodsCarryTheirClassAsOwnerAndEndAtTheDedent() {
    String source =
        """
        import os


        class SessionStore:
            def load(self, session_id):
                return os.path.join(self.root, session_id)

            def save(self, session):
                self.entries.append(session)


        def module_level():
            return 1
        """;

    List<CodeChunk> chunks = this.chunker.chunk("store/session.py", source);

    Assertions.assertThat(chunks)
        .extracting(CodeChunk::symbol)
        .containsExactly("module header", "SessionStore", "load", "save", "module_level");
    Assertions.assertThat(chunks)
        .filteredOn(chunk -> chunk.symbol().equals("load"))
        .singleElement()
        .satisfies(
            chunk -> {
              Assertions.assertThat(chunk.owner()).isEqualTo("SessionStore");
              Assertions.assertThat(chunk.kind()).isEqualTo(CodeChunk.Kind.METHOD);
              Assertions.assertThat(chunk.content()).contains("os.path.join");
              // The body stops at the dedent, so `save` does not bleed into `load`.
              Assertions.assertThat(chunk.content()).doesNotContain("self.entries");
            });
    Assertions.assertThat(chunks)
        .filteredOn(chunk -> chunk.symbol().equals("module_level"))
        .singleElement()
        .satisfies(chunk -> Assertions.assertThat(chunk.owner()).isEmpty());
  }

  @Test
  void goReceiverMethodsAreNamedAfterTheFunctionNotTheReceiver() {
    String source =
        """
        package server

        type Server struct {
            addr string
        }

        func (s *Server) Start(ctx context.Context) error {
            return s.listen(ctx)
        }

        func NewServer(addr string) *Server {
            return &Server{addr: addr}
        }
        """;

    List<CodeChunk> chunks = this.chunker.chunk("server/server.go", source);

    Assertions.assertThat(chunks)
        .extracting(CodeChunk::symbol)
        .containsExactly("module header", "Server", "Start", "NewServer");
    Assertions.assertThat(chunks)
        .filteredOn(chunk -> chunk.symbol().equals("Start"))
        .singleElement()
        .satisfies(
            chunk -> {
              Assertions.assertThat(chunk.content()).contains("return s.listen(ctx)");
              Assertions.assertThat(chunk.content()).doesNotContain("NewServer");
            });
  }

  @Test
  void typescriptCoversClassMembersArrowsAndInterfaces() {
    String source =
        """
        import { Client } from "./client";

        export interface Options {
          retries: number;
        }

        export class Runner {
          async start(options: Options): Promise<void> {
            await this.client.connect();
          }
        }

        export const buildRunner = (options: Options) => {
          return new Runner(options);
        };
        """;

    List<CodeChunk> chunks = this.chunker.chunk("src/runner.ts", source);

    Assertions.assertThat(chunks)
        .extracting(CodeChunk::symbol)
        .containsExactly("module header", "Options", "Runner", "start", "buildRunner");
    Assertions.assertThat(chunks)
        .filteredOn(chunk -> chunk.symbol().equals("start"))
        .singleElement()
        .satisfies(chunk -> Assertions.assertThat(chunk.owner()).isEqualTo("Runner"));
  }

  @Test
  void controlFlowIsNotMistakenForAMethodDeclaration() {
    String source =
        """
        export function handle(request) {
          if (request.valid) {
            return ok();
          }
          for (const item of request.items) {
            process(item);
          }
          while (queue.length > 0) {
            drain();
          }
          return null;
        }
        """;

    Assertions.assertThat(this.chunker.chunk("src/handle.js", source))
        .extracting(CodeChunk::symbol)
        .containsExactly("handle");
  }

  @Test
  void aFileWithNoRecognisedDeclarationFallsBackToTheTextChunker() {
    // Degrading to the previous behaviour matters more than forcing a bad guess: a config-style
    // Python file has no def or class, and losing it from the index would be worse than prose
    // chunking it.
    String source = "DEBUG = True\nHOSTS = [\"a\", \"b\"]\nTIMEOUT = 30\n";

    List<CodeChunk> chunks = this.chunker.chunk("settings.py", source);

    Assertions.assertThat(chunks).isNotEmpty();
    Assertions.assertThat(chunks).allSatisfy(chunk -> Assertions.assertThat(chunk).isNotNull());
    Assertions.assertThat(String.join("\n", chunks.stream().map(CodeChunk::content).toList()))
        .contains("TIMEOUT = 30");
  }

  @Test
  void aBraceInsideAStringDoesNotEndTheEnclosingFunction() {
    // Brace counting on raw text ended `Start` at the `}` inside the SQL literal, so the chunk was
    // truncated mid-body and everything after it shifted. Go's backtick strings are the worst case:
    // they span lines and routinely hold SQL, JSON or templates.
    String source =
        """
        package store

        func Start(ctx context.Context) error {
            query := `SELECT json_build_object('a', 1) }` + suffix
            note := "closing brace } inside a string"
            // a commented } as well
            return run(query, note)
        }

        func Stop() error {
            return nil
        }
        """;

    List<CodeChunk> chunks = this.chunker.chunk("store/run.go", source);

    Assertions.assertThat(chunks)
        .extracting(CodeChunk::symbol)
        .containsExactly("module header", "Start", "Stop");
    Assertions.assertThat(chunks)
        .filteredOn(chunk -> chunk.symbol().equals("Start"))
        .singleElement()
        .satisfies(
            chunk -> {
              // The whole body survives, and the literal is reproduced verbatim from the original.
              Assertions.assertThat(chunk.content()).contains("return run(query, note)");
              Assertions.assertThat(chunk.content()).contains("json_build_object('a', 1) }");
            });
  }

  @Test
  void aCommentedOutDeclarationIsNotIndexedAsARealOne() {
    String source =
        """
        # def legacy_load(self):
        #     return None

        def load(path):
            \"\"\"Docstring mentioning def shadow_load(x) and a } brace.\"\"\"
            return open(path)
        """;

    Assertions.assertThat(this.chunker.chunk("loader.py", source))
        .extracting(CodeChunk::symbol)
        .containsExactly("module header", "load");
  }

  @Test
  void aTemplateLiteralHoldingBracesDoesNotTruncateTheMethod() {
    String source =
        """
        export class Renderer {
          render(items) {
            const markup = `<ul>${items.map((i) => `<li>${i}</li>`).join("")}</ul>`;
            return markup;
          }
        }
        """;

    Assertions.assertThat(this.chunker.chunk("src/renderer.ts", source))
        .filteredOn(chunk -> chunk.symbol().equals("render"))
        .singleElement()
        .satisfies(chunk -> Assertions.assertThat(chunk.content()).contains("return markup;"));
  }

  @Test
  void recognisesTheLanguagesItClaimsAndDeclinesTheOthers() {
    Assertions.assertThat(SymbolChunker.supports("a.py")).isTrue();
    Assertions.assertThat(SymbolChunker.supports("a.tsx")).isTrue();
    Assertions.assertThat(SymbolChunker.supports("a.rs")).isTrue();
    Assertions.assertThat(SymbolChunker.supports("a.kt")).isTrue();
    // Java has a real parser; C-family members have no keyword to anchor a pattern on.
    Assertions.assertThat(SymbolChunker.supports("a.java")).isFalse();
    Assertions.assertThat(SymbolChunker.supports("a.cs")).isFalse();
    Assertions.assertThat(SymbolChunker.supports("a.cpp")).isFalse();
    Assertions.assertThat(SymbolChunker.supports("README.md")).isFalse();
  }
}
