package com.mewcode.rag.chunk;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

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
        .forEach(type -> addType(chunks, path, packageName, unit, type));
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
    // Compact record constructors and initializer blocks are BodyDeclarations of their own kinds;
    // without these passes their bodies would live in no chunk at all now that the TYPE chunk is
    // a skeleton.
    unit.findAll(CompactConstructorDeclaration.class)
        .forEach(
            constructor ->
                add(
                    chunks,
                    path,
                    packageName,
                    owner(constructor),
                    constructor.getNameAsString() + "()",
                    CodeChunk.Kind.CONSTRUCTOR,
                    constructor,
                    lines));
    unit.findAll(InitializerDeclaration.class)
        .forEach(
            initializer ->
                add(
                    chunks,
                    path,
                    packageName,
                    owner(initializer),
                    initializer.isStatic() ? "static initializer" : "instance initializer",
                    CodeChunk.Kind.METHOD,
                    initializer,
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

  /**
   * Emits the TYPE chunk as a structural skeleton instead of the full class body.
   *
   * <p>Every method, constructor and field already gets its own chunk, so a TYPE chunk holding the
   * whole body stored each file roughly (1 + members) times: the index bloated, and one oversized
   * top-ranked chunk could eat the entire search token budget. The skeleton keeps what only the
   * type-level view can answer — "what does this class contain" — while member bodies stay in their
   * member chunks. The chunk id is unchanged: it hashes path/kind/owner/symbol/ startLine, none of
   * which move.
   */
  private static void addType(
      List<CodeChunk> chunks,
      String path,
      String packageName,
      CompilationUnit unit,
      TypeDeclaration<?> type) {
    type.getRange()
        .ifPresent(
            range ->
                chunks.add(
                    CodeChunk.create(
                        path,
                        "java",
                        CodeChunk.Kind.TYPE,
                        packageName,
                        owner(type),
                        type.getNameAsString(),
                        range.begin.line,
                        range.end.line,
                        skeleton(unit, type))));
  }

  private static String skeleton(CompilationUnit unit, TypeDeclaration<?> type) {
    StringBuilder text = new StringBuilder();
    unit.getPackageDeclaration()
        .ifPresent(pkg -> text.append(pkg.toString().trim()).append("\n\n"));
    type.getJavadocComment()
        .ifPresent(comment -> text.append(comment.toString().stripTrailing()).append('\n'));
    type.getAnnotations().forEach(annotation -> text.append(annotation).append('\n'));
    text.append(typeHeader(type)).append(" {\n");
    if (type instanceof EnumDeclaration enumeration && !enumeration.getEntries().isEmpty()) {
      text.append("  ")
          .append(
              enumeration.getEntries().stream()
                  .map(entry -> entry.getNameAsString())
                  .collect(Collectors.joining(", ")))
          .append(";\n");
    }

    for (BodyDeclaration<?> member : type.getMembers()) {
      if (member instanceof FieldDeclaration field) {
        for (VariableDeclarator variable : field.getVariables()) {
          text.append("  ").append(fieldLine(field, variable)).append('\n');
        }
      } else if (member instanceof ConstructorDeclaration constructor) {
        text.append("  ")
            .append(constructor.getDeclarationAsString(true, true, true))
            .append(";\n");
      } else if (member instanceof CompactConstructorDeclaration compact) {
        text.append("  ").append(compact.getNameAsString()).append(" { ... }\n");
      } else if (member instanceof InitializerDeclaration initializer) {
        text.append(initializer.isStatic() ? "  static { ... }\n" : "  { ... }\n");
      } else if (member instanceof AnnotationMemberDeclaration annotationMember) {
        // Annotation members are one-liners; the declaration with its default IS the content.
        text.append("  ").append(annotationMember.toString().strip()).append('\n');
      } else if (member instanceof MethodDeclaration method) {
        text.append("  ").append(method.getDeclarationAsString(true, true, true)).append(";\n");
      } else if (member instanceof TypeDeclaration<?> nested) {
        // Nested types get their own TYPE chunk; here only their existence matters.
        text.append("  ").append(typeHeader(nested)).append(" { ... }\n");
      }
    }

    text.append('}');
    return text.toString();
  }

  private static String typeHeader(TypeDeclaration<?> type) {
    String modifiers =
        type.getModifiers().stream()
            .map(modifier -> modifier.getKeyword().asString())
            .collect(Collectors.joining(" "));
    String prefix = modifiers.isEmpty() ? "" : modifiers + " ";
    if (type instanceof ClassOrInterfaceDeclaration declaration) {
      StringBuilder header =
          new StringBuilder(prefix)
              .append(declaration.isInterface() ? "interface " : "class ")
              .append(declaration.getNameAsString());
      if (!declaration.getTypeParameters().isEmpty()) {
        header.append('<').append(joinNodes(declaration.getTypeParameters())).append('>');
      }
      if (!declaration.getExtendedTypes().isEmpty()) {
        header.append(" extends ").append(joinNodes(declaration.getExtendedTypes()));
      }
      if (!declaration.getImplementedTypes().isEmpty()) {
        header.append(" implements ").append(joinNodes(declaration.getImplementedTypes()));
      }
      return header.toString();
    }
    if (type instanceof RecordDeclaration record) {
      StringBuilder header =
          new StringBuilder(prefix)
              .append("record ")
              .append(record.getNameAsString())
              .append('(')
              .append(joinNodes(record.getParameters()))
              .append(')');
      if (!record.getImplementedTypes().isEmpty()) {
        header.append(" implements ").append(joinNodes(record.getImplementedTypes()));
      }
      return header.toString();
    }
    if (type instanceof EnumDeclaration enumeration) {
      StringBuilder header =
          new StringBuilder(prefix).append("enum ").append(enumeration.getNameAsString());
      if (!enumeration.getImplementedTypes().isEmpty()) {
        header.append(" implements ").append(joinNodes(enumeration.getImplementedTypes()));
      }
      return header.toString();
    }
    if (type instanceof AnnotationDeclaration annotation) {
      return prefix + "@interface " + annotation.getNameAsString();
    }
    return prefix + type.getNameAsString();
  }

  private static String joinNodes(NodeList<? extends Node> nodes) {
    return nodes.stream().map(Node::toString).collect(Collectors.joining(", "));
  }

  private static String fieldLine(FieldDeclaration field, VariableDeclarator variable) {
    String modifiers =
        field.getModifiers().stream()
            .map(modifier -> modifier.getKeyword().asString())
            .collect(Collectors.joining(" "));
    // Short one-line initializers (constants, defaults) carry signal; anything larger is body,
    // and bodies belong to member chunks, not the skeleton.
    String initializer =
        variable
            .getInitializer()
            .map(Node::toString)
            .map(value -> value.length() <= 60 && !value.contains("\n") ? " = " + value : " = ...")
            .orElse("");
    return (modifiers.isEmpty() ? "" : modifiers + " ")
        + variable.getTypeAsString()
        + " "
        + variable.getNameAsString()
        + initializer
        + ";";
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
