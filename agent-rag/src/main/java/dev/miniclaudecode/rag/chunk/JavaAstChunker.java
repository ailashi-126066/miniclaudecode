package dev.miniclaudecode.rag.chunk;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class JavaAstChunker implements DocumentChunker {
  private final JavaParser parser =
      new JavaParser(new ParserConfiguration().setLanguageLevel(LanguageLevel.JAVA_21));

  @Override
  public List<CodeChunk> chunk(String path, String content) {
    ParseResult<CompilationUnit> result = this.parser.parse(content);
    CompilationUnit unit =
        result
            .getResult()
            .filter(ignored -> result.isSuccessful())
            .orElseThrow(() -> new ParseProblemException(result.getProblems()));
    String packageName =
        unit.getPackageDeclaration().map(value -> value.getNameAsString()).orElse("");
    List<String> lines = content.lines().toList();
    List<CodeChunk> chunks = new ArrayList<>();
    unit.findAll(TypeDeclaration.class).stream()
        .map(TypeDeclaration.class::cast)
        .forEach(
            type ->
                add(
                    chunks,
                    path,
                    packageName,
                    owner(type),
                    type.getNameAsString(),
                    CodeChunk.Kind.TYPE,
                    type,
                    lines));
    unit.findAll(MethodDeclaration.class)
        .forEach(
            method ->
                add(
                    chunks,
                    path,
                    packageName,
                    owner(method),
                    method.getSignature().asString(),
                    CodeChunk.Kind.METHOD,
                    method,
                    lines));
    unit.findAll(ConstructorDeclaration.class)
        .forEach(
            constructor ->
                add(
                    chunks,
                    path,
                    packageName,
                    owner(constructor),
                    constructor.getSignature().asString(),
                    CodeChunk.Kind.CONSTRUCTOR,
                    constructor,
                    lines));
    unit.findAll(FieldDeclaration.class)
        .forEach(
            field -> {
              for (VariableDeclarator variable : field.getVariables()) {
                add(
                    chunks,
                    path,
                    packageName,
                    owner(field),
                    variable.getNameAsString(),
                    CodeChunk.Kind.FIELD,
                    field,
                    lines);
              }
            });
    chunks.sort(
        Comparator.comparingInt(CodeChunk::startLine)
            .thenComparing(CodeChunk::kind)
            .thenComparing(CodeChunk::symbol));
    return List.copyOf(chunks);
  }

  private static void add(
      List<CodeChunk> chunks,
      String path,
      String packageName,
      String owner,
      String symbol,
      CodeChunk.Kind kind,
      Node node,
      List<String> lines) {
    node.getRange()
        .ifPresent(
            range -> {
              int start = range.begin.line;
              int end = range.end.line;
              String source =
                  String.join("\n", lines.subList(start - 1, Math.min(end, lines.size())));
              chunks.add(
                  CodeChunk.create(
                      path, "java", kind, packageName, owner, symbol, start, end, source));
            });
  }

  private static String owner(Node node) {
    List<String> names = new ArrayList<>();

    for (Node current = node;
        current != null;
        current = (Node) current.getParentNode().orElse(null)) {
      if (current instanceof TypeDeclaration<?> type) {
        names.addFirst(type.getNameAsString());
      }
    }

    return String.join(".", names);
  }
}
