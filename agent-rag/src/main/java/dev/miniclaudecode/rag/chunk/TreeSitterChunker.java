package dev.miniclaudecode.rag.chunk;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;

/** Syntax-tree based declaration chunking for non-Java source files. */
public final class TreeSitterChunker implements DocumentChunker {
  private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}_$][\\p{L}\\p{N}_$]*");

  private record Language(
      String name,
      String parserClass,
      Set<String> types,
      Set<String> methods,
      Set<String> constructors,
      Set<String> callableBindings) {}

  private record Declaration(
      CodeChunk.Kind kind, String owner, String symbol, int startLine, int endLine) {}

  private static final Map<String, Language> LANGUAGES = languages();

  private final DocumentChunker delegate;

  public TreeSitterChunker(DocumentChunker delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
  }

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
    try {
      return this.treeChunks(path, content, language);
    } catch (LinkageError | ReflectiveOperationException | RuntimeException unavailable) {
      // A missing/unsupported native library or a malformed source file must not make indexing
      // fail. The pattern chunker still provides useful symbols, and ultimately delegates to text.
      return this.delegate.chunk(path, content);
    }
  }

  private List<CodeChunk> treeChunks(String path, String content, Language language)
      throws ReflectiveOperationException {
    TSLanguage parserLanguage = parserLanguage(language);
    TSParser parser = new TSParser();
    if (!parser.setLanguage(parserLanguage)) {
      return this.delegate.chunk(path, content);
    }
    TSTree tree = parser.parseString(null, content);
    if (tree == null) {
      return this.delegate.chunk(path, content);
    }

    byte[] utf8 = content.getBytes(StandardCharsets.UTF_8);
    List<Declaration> declarations = new ArrayList<>();
    collect(language, tree.getRootNode(), utf8, "", false, declarations);
    declarations.sort(
        Comparator.comparingInt(Declaration::startLine).thenComparingInt(Declaration::endLine));
    if (declarations.isEmpty()) {
      return this.delegate.chunk(path, content);
    }
    return chunks(path, content, language.name(), declarations);
  }

  private static TSLanguage parserLanguage(Language language)
      throws ClassNotFoundException,
          NoSuchMethodException,
          InstantiationException,
          IllegalAccessException,
          InvocationTargetException {
    return (TSLanguage) Class.forName(language.parserClass()).getConstructor().newInstance();
  }

  private static void collect(
      Language language,
      TSNode node,
      byte[] source,
      String owner,
      boolean insideCallable,
      List<Declaration> declarations) {
    CodeChunk.Kind kind = kind(language, node);
    boolean accepted = kind != null && !insideCallable;
    String symbol = accepted ? symbol(node, source) : "";
    if (accepted && !symbol.isBlank()) {
      String declarationOwner = inferredOwner(node, source, owner, symbol);
      if (kind == CodeChunk.Kind.METHOD && symbol.equals(declarationOwner)) {
        kind = CodeChunk.Kind.CONSTRUCTOR;
      }
      declarations.add(
          new Declaration(
              kind,
              declarationOwner,
              symbol,
              node.getStartPoint().getRow() + 1,
              inclusiveEndLine(node)));
      if (kind == CodeChunk.Kind.TYPE) {
        owner = symbol;
      }
    }

    boolean nestedInCallable =
        insideCallable
            || (accepted && (kind == CodeChunk.Kind.METHOD || kind == CodeChunk.Kind.CONSTRUCTOR));
    for (int index = 0; index < node.getNamedChildCount(); index++) {
      collect(language, node.getNamedChild(index), source, owner, nestedInCallable, declarations);
    }
  }

  private static CodeChunk.Kind kind(Language language, TSNode node) {
    String type = node.getType();
    if (language.types().contains(type)) {
      return CodeChunk.Kind.TYPE;
    }
    if (language.constructors().contains(type)) {
      return CodeChunk.Kind.CONSTRUCTOR;
    }
    if (language.methods().contains(type)) {
      return CodeChunk.Kind.METHOD;
    }
    if (language.callableBindings().contains(type)) {
      TSNode value = node.getChildByFieldName("value");
      if (present(value)
          && (value.getType().contains("function") || value.getType().equals("lambda"))) {
        return CodeChunk.Kind.METHOD;
      }
    }
    return null;
  }

  private static String symbol(TSNode node, byte[] source) {
    TSNode named = nameNode(node);
    if (!present(named)) {
      return "";
    }
    String raw = text(named, source).strip();
    if (!raw.contains("\n") && raw.length() <= 160) {
      return raw;
    }
    Matcher matcher = IDENTIFIER.matcher(raw);
    String last = "";
    while (matcher.find()) {
      last = matcher.group();
    }
    return last;
  }

  private static TSNode nameNode(TSNode node) {
    for (String field : List.of("name", "declarator", "type")) {
      TSNode candidate = node.getChildByFieldName(field);
      if (!present(candidate)) {
        continue;
      }
      // C/C++ declarators nest from the variable outwards. The function name in
      // `Worker::run()` is therefore the last identifier, while a normal `name` field is already
      // the exact symbol node.
      TSNode nested =
          field.equals("declarator") ? declaratorIdentifier(candidate) : identifier(candidate);
      return present(nested) ? nested : candidate;
    }
    return identifier(node);
  }

  private static TSNode identifier(TSNode node) {
    if (!present(node)) {
      return null;
    }
    String type = node.getType();
    if (type.equals("identifier")
        || type.endsWith("_identifier")
        || type.equals("constant")
        || type.equals("type_identifier")) {
      return node;
    }
    for (int index = 0; index < node.getNamedChildCount(); index++) {
      TSNode found = identifier(node.getNamedChild(index));
      if (present(found)) {
        return found;
      }
    }
    return null;
  }

  private static TSNode declaratorIdentifier(TSNode node) {
    if (!present(node)) {
      return null;
    }
    TSNode nested = node.getChildByFieldName("declarator");
    if (present(nested)) {
      return declaratorIdentifier(nested);
    }
    TSNode name = node.getChildByFieldName("name");
    if (present(name)) {
      return identifier(name);
    }
    return identifier(node);
  }

  private static String inferredOwner(
      TSNode node, byte[] source, String currentOwner, String symbol) {
    if (!currentOwner.isBlank()) {
      return currentOwner;
    }
    if (node.getType().equals("method_declaration")) {
      TSNode receiver = node.getChildByFieldName("receiver");
      if (present(receiver)) {
        Matcher matcher = IDENTIFIER.matcher(text(receiver, source));
        String last = "";
        while (matcher.find()) {
          last = matcher.group();
        }
        if (!last.isBlank() && !last.equals(symbol)) {
          return last;
        }
      }
    }
    int qualifier = Math.max(symbol.lastIndexOf("::"), symbol.lastIndexOf('.'));
    if (qualifier > 0) {
      return symbol.substring(0, qualifier);
    }
    Matcher qualified =
        Pattern.compile("([\\p{L}_$][\\p{L}\\p{N}_$]*)\\s*::\\s*" + Pattern.quote(symbol))
            .matcher(text(node, source));
    return qualified.find() ? qualified.group(1) : "";
  }

  private static int inclusiveEndLine(TSNode node) {
    int row = node.getEndPoint().getRow();
    int line = node.getEndPoint().getColumn() == 0 ? row : row + 1;
    return Math.max(node.getStartPoint().getRow() + 1, line);
  }

  private static List<CodeChunk> chunks(
      String path, String content, String language, List<Declaration> declarations) {
    List<String> lines = content.lines().toList();
    List<CodeChunk> chunks = new ArrayList<>();
    int firstDeclaration = declarations.getFirst().startLine();
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
    for (int index = 0; index < declarations.size(); index++) {
      Declaration declaration = declarations.get(index);
      int end = Math.min(declaration.endLine(), lines.size());
      if (declaration.kind() == CodeChunk.Kind.TYPE) {
        for (int next = index + 1; next < declarations.size(); next++) {
          Declaration child = declarations.get(next);
          if (child.startLine() > declaration.endLine()) {
            break;
          }
          if (child.startLine() > declaration.startLine()) {
            end = Math.min(end, child.startLine() - 1);
            break;
          }
        }
      }
      addChunk(
          chunks,
          path,
          language,
          declaration.kind(),
          declaration.owner(),
          declaration.symbol(),
          declaration.startLine(),
          Math.max(declaration.startLine(), end),
          lines);
    }
    return List.copyOf(chunks);
  }

  private static void addChunk(
      List<CodeChunk> chunks,
      String path,
      String language,
      CodeChunk.Kind kind,
      String owner,
      String symbol,
      int startLine,
      int endLine,
      List<String> lines) {
    if (lines.isEmpty() || startLine > lines.size()) {
      return;
    }
    int end = Math.min(endLine, lines.size());
    String content = String.join("\n", lines.subList(startLine - 1, end));
    if (!content.isBlank()) {
      chunks.add(
          CodeChunk.create(path, language, kind, "", owner, symbol, startLine, end, content));
    }
  }

  private static boolean present(TSNode node) {
    return node != null && !node.isNull();
  }

  private static String text(TSNode node, byte[] source) {
    int start = Math.max(0, Math.min(node.getStartByte(), source.length));
    int end = Math.max(start, Math.min(node.getEndByte(), source.length));
    return new String(source, start, end - start, StandardCharsets.UTF_8);
  }

  private static Optional<Language> language(String path) {
    String name = path.toLowerCase(Locale.ROOT);
    int dot = name.lastIndexOf('.');
    return dot < 0 ? Optional.empty() : Optional.ofNullable(LANGUAGES.get(name.substring(dot + 1)));
  }

  private static Map<String, Language> languages() {
    Set<String> noNodes = Set.of();
    Set<String> callableBindings = Set.of("variable_declarator", "lexical_declaration");
    Language python =
        language(
            "python",
            "org.treesitter.TreeSitterPython",
            Set.of("class_definition"),
            Set.of("function_definition"),
            noNodes,
            noNodes);
    Language javascript =
        language(
            "javascript",
            "org.treesitter.TreeSitterJavascript",
            Set.of("class_declaration", "class"),
            Set.of("function_declaration", "generator_function_declaration", "method_definition"),
            noNodes,
            callableBindings);
    Language typescript =
        language(
            "typescript",
            "org.treesitter.TreeSitterTypescript",
            Set.of(
                "class_declaration",
                "abstract_class_declaration",
                "interface_declaration",
                "enum_declaration",
                "type_alias_declaration"),
            Set.of(
                "function_declaration",
                "generator_function_declaration",
                "method_definition",
                "method_signature",
                "function_signature"),
            noNodes,
            callableBindings);
    Language tsx =
        language(
            "typescript",
            "org.treesitter.TreeSitterTsx",
            typescript.types(),
            typescript.methods(),
            noNodes,
            callableBindings);
    Language go =
        language(
            "go",
            "org.treesitter.TreeSitterGo",
            Set.of("type_spec"),
            Set.of("function_declaration", "method_declaration"),
            noNodes,
            noNodes);
    Language rust =
        language(
            "rust",
            "org.treesitter.TreeSitterRust",
            Set.of("struct_item", "enum_item", "trait_item", "impl_item", "union_item"),
            Set.of("function_item"),
            noNodes,
            noNodes);
    Language c =
        language(
            "c",
            "org.treesitter.TreeSitterC",
            Set.of("struct_specifier", "union_specifier", "enum_specifier", "type_definition"),
            Set.of("function_definition"),
            noNodes,
            noNodes);
    Language cpp =
        language(
            "cpp",
            "org.treesitter.TreeSitterCpp",
            Set.of(
                "class_specifier",
                "struct_specifier",
                "union_specifier",
                "enum_specifier",
                "namespace_definition"),
            Set.of("function_definition"),
            noNodes,
            noNodes);
    Language csharp =
        language(
            "csharp",
            "org.treesitter.TreeSitterCSharp",
            Set.of(
                "class_declaration",
                "interface_declaration",
                "struct_declaration",
                "record_declaration",
                "enum_declaration"),
            Set.of("method_declaration", "local_function_statement"),
            Set.of("constructor_declaration"),
            noNodes);
    Language ruby =
        language(
            "ruby",
            "org.treesitter.TreeSitterRuby",
            Set.of("class", "module"),
            Set.of("method", "singleton_method"),
            noNodes,
            noNodes);

    return Map.ofEntries(
        Map.entry("py", python),
        Map.entry("pyi", python),
        Map.entry("js", javascript),
        Map.entry("jsx", javascript),
        Map.entry("mjs", javascript),
        Map.entry("cjs", javascript),
        Map.entry("ts", typescript),
        Map.entry("tsx", tsx),
        Map.entry("go", go),
        Map.entry("rs", rust),
        Map.entry("c", c),
        Map.entry("h", c),
        Map.entry("cc", cpp),
        Map.entry("cpp", cpp),
        Map.entry("cxx", cpp),
        Map.entry("hh", cpp),
        Map.entry("hpp", cpp),
        Map.entry("hxx", cpp),
        Map.entry("cs", csharp),
        Map.entry("rb", ruby));
  }

  private static Language language(
      String name,
      String parserClass,
      Set<String> types,
      Set<String> methods,
      Set<String> constructors,
      Set<String> callableBindings) {
    return new Language(name, parserClass, types, methods, constructors, callableBindings);
  }
}
