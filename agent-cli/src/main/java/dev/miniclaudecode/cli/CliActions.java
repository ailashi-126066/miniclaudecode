package dev.miniclaudecode.cli;

import java.nio.file.Path;

public interface CliActions {

  int configure();

  int interactive(Path workspace);

  int run(Path workspace, String prompt);

  int index(Path workspace);

  int rag(Path workspace, String query);
}
