package dev.miniclaudecode.rag.chunk;

import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class TreeSitterChunkerTest {
  private final TreeSitterChunker chunker = new TreeSitterChunker(new LangChainDocumentChunker());

  @Test
  void pythonUsesAstBoundariesAndRetainsUnicodeSymbols() {
    String source =
        """
        import pathlib

        class 服务:
            def load(self):
                return pathlib.Path("data")

            def save(self):
                return True
        """;

    List<CodeChunk> chunks = this.chunker.chunk("service.py", source);

    Assertions.assertThat(chunks)
        .extracting(CodeChunk::symbol)
        .containsExactly("module header", "服务", "load", "save");
    Assertions.assertThat(chunks)
        .filteredOn(chunk -> chunk.symbol().equals("load"))
        .singleElement()
        .satisfies(
            chunk -> {
              Assertions.assertThat(chunk.owner()).isEqualTo("服务");
              Assertions.assertThat(chunk.content()).contains("pathlib.Path");
              Assertions.assertThat(chunk.content()).doesNotContain("return True");
            });
  }

  @Test
  void cFamilyGetsSymbolsThatThePatternFallbackDeliberatelyCannotGuess() {
    String c =
        """
        typedef struct Server {
          int port;
        } Server;

        int start_server(Server *server) {
          return server->port;
        }
        """;
    String cpp =
        """
        class Worker {
        public:
          void run();
        };

        void Worker::run() {
          execute();
        }
        """;
    String csharp =
        """
        class Worker {
          public Worker() {}
          public void Run() { Execute(); }
        }
        """;

    Assertions.assertThat(this.chunker.chunk("server.c", c))
        .extracting(CodeChunk::symbol)
        .contains("Server", "start_server");
    Assertions.assertThat(this.chunker.chunk("worker.cpp", cpp))
        .extracting(CodeChunk::symbol)
        .contains("Worker", "run");
    Assertions.assertThat(this.chunker.chunk("worker.cpp", cpp))
        .filteredOn(chunk -> chunk.symbol().equals("run"))
        .singleElement()
        .satisfies(chunk -> Assertions.assertThat(chunk.owner()).isEqualTo("Worker"));
    Assertions.assertThat(this.chunker.chunk("Worker.cs", csharp))
        .extracting(CodeChunk::symbol)
        .contains("Worker", "Run");
  }

  @Test
  void javascriptAndGoPreserveCallableNamesAndOwners() {
    String javascript =
        """
        export const handleRequest = async (request) => {
          return request.id;
        };
        """;
    String go =
        """
        package server

        type Server struct {}

        func (s *Server) Start() error {
          return nil
        }
        """;

    Assertions.assertThat(this.chunker.chunk("handler.ts", javascript))
        .extracting(CodeChunk::symbol)
        .contains("handleRequest");
    Assertions.assertThat(this.chunker.chunk("server.go", go))
        .filteredOn(chunk -> chunk.symbol().equals("Start"))
        .singleElement()
        .satisfies(chunk -> Assertions.assertThat(chunk.owner()).isEqualTo("Server"));
  }

  @Test
  void filesWithoutDeclarationsUseTheConfiguredFallback() {
    DocumentChunker fallback =
        (path, content) ->
            List.of(
                CodeChunk.create(
                    path, "fallback", CodeChunk.Kind.TEXT, "", "", "fallback", 1, 1, content));
    TreeSitterChunker fallingBack = new TreeSitterChunker(fallback);

    Assertions.assertThat(fallingBack.chunk("values.py", "answer = 42"))
        .singleElement()
        .extracting(CodeChunk::symbol)
        .isEqualTo("fallback");
  }
}
