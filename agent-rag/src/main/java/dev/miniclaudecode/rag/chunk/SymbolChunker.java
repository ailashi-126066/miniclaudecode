package dev.miniclaudecode.rag.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Declaration-aware chunking for the languages that have no parser on the classpath.
 *
 * <p>Java is AST-chunked; everything else used to fall through to a prose splitter that cuts on
 * token windows and knows nothing about functions, so a Python chunk routinely began and ended
 * mid-body. The bigger cost was indirect: those chunks carried an empty {@code symbol}, and symbol
 * is the highest-weighted field in the pipeline — a 3x boost in the BM25 query, weight 5.0 in the
 * reranker's TF-IDF, plus a separate exact-symbol bonus. Non-Java code could not compete on the
 * field that decides most rankings.
 *
 * <p>This is pattern matching over a lexically masked source, not parsing. Every judgement is made
 * against {@link SourceMask}'s output rather than the raw text, which is what makes it trustworthy:
 * a {@code }} inside a string no longer closes a block, a commented-out {@code func old()} is no
 * longer a declaration, and a brace in a docstring no longer unbalances the rest of the file.
 * Content is still sliced from the original, so chunks read exactly as written.
 *
 * <p>What it still is not: it does not know types, and a declaration whose header spans lines in an
 * unusual way can be missed. Missing a declaration is the safe direction — when a file yields none
 * at all it is handed to {@code delegate} untouched, so a mis-detection degrades to prose chunking
 * rather than losing the file.
 *
 * <p>This remains the dependency-free fallback for languages without a bundled Tree-sitter grammar
 * and for hosts where a native parser cannot load. Missing a declaration is safer than inventing a
 * symbol, because symbol matches receive the largest retrieval boost.
 *
 * <p>C, C++ and C# are deliberately absent. Their member declarations have no leading keyword to
 * anchor on ({@code void Start()} is a method, a call, or a variable depending on context), and a
 * pattern loose enough to catch them also catches control flow — wrong symbols are worse than no
 * symbols, because the ranking trusts that field most.
 */
public final class SymbolChunker implements DocumentChunker {

  /** How a declaration's extent is determined once its opening line is found. */
  private enum Style {
    /** C-like: count braces from the opening line until nesting returns to zero. */
    BRACE,
    /** Python and Ruby: the body is every following line indented deeper than the declaration. */
    INDENT
  }

  private record Language(
      String name, Style style, SourceMask.Syntax syntax, List<Declaration> declarations) {}

  /** One declaration form: a pattern whose named group {@code name} holds the symbol. */
  private record Declaration(Pattern pattern, CodeChunk.Kind kind) {}

  private record Match(int line, int indent, CodeChunk.Kind kind, String name, String owner) {}

  private static final Map<String, Language> LANGUAGES = languages();

  private final DocumentChunker delegate;

  public SymbolChunker(DocumentChunker delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
  }

  /** Whether this chunker recognises the file's language at all. */
  public static boolean supports(String path) {
    return language(path).isPresent();
  }

  @Override
  public List<CodeChunk> chunk(String path, String content) {
    Objects.requireNonNull(path, "path must not be null");
    Objects.requireNonNull(content, "content must not be null");
    Optional<Language> detected = language(path);
    if (detected.isEmpty() || content.isBlank()) {
      return this.delegate.chunk(path, content);
    }
    Language language = detected.orElseThrow();
    List<String> lines = content.lines().toList();
    // Structure is read off the masked copy and content is sliced from the original. The mask keeps
    // every character position, so a line number means the same thing in both.
    List<String> maskedLines = SourceMask.mask(content, language.syntax()).lines().toList();
    List<Match> matches = declarations(language, maskedLines);
    if (matches.isEmpty()) {
      return this.delegate.chunk(path, content);
    }

    List<CodeChunk> chunks = new ArrayList<>();
    // Imports and module-level statements sit before the first declaration and answer "what does
    // this file depend on", which no declaration chunk can.
    int firstDeclaration = matches.getFirst().line();
    if (firstDeclaration > 1) {
      addChunk(
          chunks,
          path,
          language,
          CodeChunk.Kind.SECTION,
          "",
          "module header",
          1,
          firstDeclaration - 1,
          lines);
    }
    for (int index = 0; index < matches.size(); index++) {
      Match match = matches.get(index);
      // Stopping at the next declaration is what keeps a container chunk to its own header: the
      // members that follow are chunks in their own right, so spanning them would store the file
      // twice, exactly the duplication the Java TYPE skeleton exists to avoid.
      int nextDeclaration =
          index + 1 < matches.size() ? matches.get(index + 1).line() : lines.size() + 1;
      int end = Math.min(extent(language, maskedLines, match.line()), nextDeclaration - 1);
      addChunk(
          chunks,
          path,
          language,
          match.kind(),
          match.owner(),
          match.name(),
          match.line(),
          Math.max(match.line(), end),
          lines);
    }
    return List.copyOf(chunks);
  }

  private static void addChunk(
      List<CodeChunk> chunks,
      String path,
      Language language,
      CodeChunk.Kind kind,
      String owner,
      String symbol,
      int startLine,
      int endLine,
      List<String> lines) {
    int end = Math.min(endLine, lines.size());
    String text = String.join("\n", lines.subList(startLine - 1, end));
    if (text.isBlank()) {
      return;
    }
    chunks.add(
        CodeChunk.create(path, language.name(), kind, "", owner, symbol, startLine, end, text));
  }

  private static List<Match> declarations(Language language, List<String> lines) {
    List<Match> matches = new ArrayList<>();
    List<Match> containers = new ArrayList<>();
    for (int index = 0; index < lines.size(); index++) {
      String line = lines.get(index);
      for (Declaration declaration : language.declarations()) {
        Matcher matcher = declaration.pattern().matcher(line);
        if (!matcher.find()) {
          continue;
        }
        int indent = indent(line);
        // The nearest still-open container names the owner, which gives a Python method the
        // `ClassName` qualifier that a Java method gets from its AST parent.
        containers.removeIf(container -> indent <= container.indent());
        Match match =
            new Match(
                index + 1,
                indent,
                declaration.kind(),
                matcher.group("name"),
                containers.isEmpty() ? "" : containers.getLast().name());
        matches.add(match);
        if (declaration.kind() == CodeChunk.Kind.TYPE) {
          containers.add(match);
        }
        break;
      }
    }
    return List.copyOf(matches);
  }

  /** Last line of the declaration starting at {@code startLine}, inclusive. */
  private static int extent(Language language, List<String> lines, int startLine) {
    return language.style() == Style.INDENT
        ? indentExtent(lines, startLine)
        : braceExtent(lines, startLine);
  }

  private static int indentExtent(List<String> lines, int startLine) {
    int declarationIndent = indent(lines.get(startLine - 1));
    int end = startLine;
    for (int index = startLine; index < lines.size(); index++) {
      String line = lines.get(index);
      if (line.isBlank()) {
        continue;
      }
      if (indent(line) <= declarationIndent) {
        return end;
      }
      end = index + 1;
    }
    return lines.size();
  }

  /**
   * Brace counting from the declaration line. Strings and comments are not tracked, so a stray
   * brace in a literal can close a chunk early. A signature with no brace at all — an interface
   * method, an abstract declaration, a Rust trait method — yields its single line, which is right.
   */
  private static int braceExtent(List<String> lines, int startLine) {
    int depth = 0;
    boolean opened = false;
    for (int index = startLine - 1; index < lines.size(); index++) {
      String line = lines.get(index);
      for (int position = 0; position < line.length(); position++) {
        char character = line.charAt(position);
        if (character == '{') {
          depth++;
          opened = true;
        } else if (character == '}') {
          depth--;
        }
      }
      if (opened && depth <= 0) {
        return index + 1;
      }
      if (!opened && line.stripTrailing().endsWith(";")) {
        return index + 1;
      }
    }
    return opened ? lines.size() : startLine;
  }

  private static int indent(String line) {
    int count = 0;
    while (count < line.length() && (line.charAt(count) == ' ' || line.charAt(count) == '\t')) {
      count++;
    }
    return count;
  }

  private static Optional<Language> language(String path) {
    String name = path.toLowerCase(Locale.ROOT);
    int dot = name.lastIndexOf('.');
    return dot < 0 ? Optional.empty() : Optional.ofNullable(LANGUAGES.get(name.substring(dot + 1)));
  }

  private static Declaration type(String regex) {
    return new Declaration(Pattern.compile(regex), CodeChunk.Kind.TYPE);
  }

  private static Declaration method(String regex) {
    return new Declaration(Pattern.compile(regex), CodeChunk.Kind.METHOD);
  }

  /** {@code //} line comments, {@code /* *}{@code /} blocks, and double/single quoted strings. */
  private static SourceMask.Syntax cStyle(SourceMask.Quote... extraQuotes) {
    List<SourceMask.Quote> quotes = new ArrayList<>(List.of(extraQuotes));
    quotes.add(SourceMask.Quote.of("\"", true));
    quotes.add(SourceMask.Quote.of("'", true));
    return new SourceMask.Syntax(
        List.of("//"), List.of(new SourceMask.Block("/*", "*/")), List.copyOf(quotes));
  }

  private static Map<String, Language> languages() {
    String name = "(?<name>[\\p{L}_$][\\p{L}\\p{N}_$]*)";

    // Triple quotes must precede single quotes so a docstring is not read as an empty string
    // followed by loose text.
    SourceMask.Syntax pythonSyntax =
        new SourceMask.Syntax(
            List.of("#"),
            List.of(),
            List.of(
                SourceMask.Quote.multiline("\"\"\"", true),
                SourceMask.Quote.multiline("'''", true),
                SourceMask.Quote.of("\"", true),
                SourceMask.Quote.of("'", true)));

    Language python =
        new Language(
            "python",
            Style.INDENT,
            pythonSyntax,
            List.of(type("^\\s*class\\s+" + name), method("^\\s*(?:async\\s+)?def\\s+" + name)));

    Language ruby =
        new Language(
            "ruby",
            Style.INDENT,
            new SourceMask.Syntax(
                List.of("#"),
                List.of(new SourceMask.Block("=begin", "=end")),
                List.of(SourceMask.Quote.of("\"", true), SourceMask.Quote.of("'", true))),
            List.of(
                type("^\\s*(?:class|module)\\s+" + name),
                method("^\\s*def\\s+(?:self\\.)?" + name)));

    Language go =
        new Language(
            "go",
            Style.BRACE,
            // Backtick raw strings span lines and ignore escapes, which is exactly the form most
            // likely to contain a stray brace: embedded SQL, JSON and templates all live in them.
            cStyle(new SourceMask.Quote("`", "`", false, true)),
            List.of(
                type("^\\s*type\\s+" + name + "\\s+(?:struct|interface)\\b"),
                // A method's receiver precedes the name: `func (s *Server) Start(...)`.
                method("^\\s*func\\s+(?:\\([^)]*\\)\\s*)?" + name + "\\s*\\(")));

    Language rust =
        new Language(
            "rust",
            Style.BRACE,
            cStyle(),
            List.of(
                type("^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?(?:struct|enum|trait|impl)\\s+" + name),
                method(
                    "^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?(?:async\\s+)?(?:const\\s+)?(?:unsafe\\s+)?"
                        + "fn\\s+"
                        + name)));

    Language javascript =
        new Language(
            "javascript",
            Style.BRACE,
            // Template literals are masked whole, interpolations included. A `${cond ? a : b}` is
            // real code, but it cannot contain a declaration, and masking it keeps its braces from
            // unbalancing the enclosing function.
            cStyle(new SourceMask.Quote("`", "`", true, true)),
            List.of(
                type("^\\s*(?:export\\s+(?:default\\s+)?)?(?:abstract\\s+)?class\\s+" + name),
                type("^\\s*(?:export\\s+)?(?:interface|enum)\\s+" + name),
                method(
                    "^\\s*(?:export\\s+(?:default\\s+)?)?(?:async\\s+)?function\\s*\\*?\\s*"
                        + name),
                // `const handler = (a) => {` and `export const run = async function (a) {`.
                method(
                    "^\\s*(?:export\\s+)?(?:const|let|var)\\s+"
                        + name
                        + "\\s*(?::[^=]+)?=\\s*(?:async\\s+)?(?:function\\b|\\(?[^=;]*=>)"),
                // Class members: `  async start(options) {`. The lookahead keeps control flow out;
                // `\b` matters so that `ifPresent(...)` is not mistaken for an `if`.
                method(
                    "^\\s+(?:public\\s+|private\\s+|protected\\s+|static\\s+|readonly\\s+|"
                        + "async\\s+|get\\s+|set\\s+|\\*)*"
                        + "(?!(?:if|for|while|switch|catch|return|await|function|class|new|do"
                        + "|else|try|typeof|throw)\\b)"
                        + name
                        + "\\s*\\([^;]*\\)\\s*(?::[^{;]+)?\\{")));

    // Kotlin, Swift, Scala and PHP share C-style comments; PHP also uses `#` for line comments and
    // Kotlin/Scala have multiline `"""` strings, so both are included for the whole group.
    SourceMask.Syntax keywordDeclaredSyntax =
        new SourceMask.Syntax(
            List.of("//", "#"),
            List.of(new SourceMask.Block("/*", "*/")),
            List.of(
                SourceMask.Quote.multiline("\"\"\"", false),
                SourceMask.Quote.of("\"", true),
                SourceMask.Quote.of("'", true)));

    Language keywordDeclared =
        new Language(
            "source",
            Style.BRACE,
            keywordDeclaredSyntax,
            List.of(
                type(
                    "^\\s*(?:public\\s+|internal\\s+|private\\s+|protected\\s+|abstract\\s+|"
                        + "sealed\\s+|static\\s+|final\\s+|open\\s+|data\\s+|case\\s+)*"
                        + "(?:class|interface|struct|enum|trait|object|protocol|extension)\\s+"
                        + name),
                method(
                    "^\\s*(?:public\\s+|internal\\s+|private\\s+|protected\\s+|static\\s+|"
                        + "override\\s+|virtual\\s+|async\\s+|suspend\\s+|open\\s+|final\\s+|"
                        + "inline\\s+|mutating\\s+|abstract\\s+)*"
                        + "(?:fun|func|def|function)\\s+"
                        + name)));

    return Map.ofEntries(
        Map.entry("py", python),
        Map.entry("pyi", python),
        Map.entry("rb", ruby),
        Map.entry("go", go),
        Map.entry("rs", rust),
        Map.entry("js", javascript),
        Map.entry("jsx", javascript),
        Map.entry("mjs", javascript),
        Map.entry("cjs", javascript),
        Map.entry("ts", javascript),
        Map.entry("tsx", javascript),
        Map.entry("kt", keywordDeclared),
        Map.entry("kts", keywordDeclared),
        Map.entry("swift", keywordDeclared),
        Map.entry("scala", keywordDeclared),
        Map.entry("php", keywordDeclared));
  }
}
