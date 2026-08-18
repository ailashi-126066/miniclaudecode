package com.mewcode.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class PlanRepository {
    private static final ObjectMapper JSON=new ObjectMapper().registerModule(new JavaTimeModule()).enable(SerializationFeature.INDENT_OUTPUT);
    private final Path jsonPath; private final Path markdownPath;
    public PlanRepository(Path workspace){Path dir=workspace.resolve(".mewcode/plans");this.jsonPath=dir.resolve("active.json");this.markdownPath=dir.resolve("active.md");}
    public synchronized void save(PlanState plan){try{Files.createDirectories(jsonPath.getParent());JSON.writeValue(jsonPath.toFile(),plan);Files.writeString(markdownPath,render(plan));}catch(Exception e){throw new IllegalStateException("Cannot save plan",e);}}
    public synchronized Optional<PlanState> load(){try{return Files.exists(jsonPath)?Optional.of(JSON.readValue(jsonPath.toFile(),PlanState.class)):Optional.empty();}catch(Exception e){return Optional.empty();}}
    public synchronized void clear(){try{Files.deleteIfExists(jsonPath);Files.deleteIfExists(markdownPath);}catch(Exception e){throw new IllegalStateException(e);}}
    private static String render(PlanState p){StringBuilder b=new StringBuilder("# ").append(p.goal()).append("\n\nStatus: ").append(p.status()).append("\n\n");for(var s:p.steps())b.append("- [").append(s.status()==PlanState.StepStatus.COMPLETED?'x':' ').append("] ").append(s.id()).append(": ").append(s.description()).append("\n");return b.toString();}
}
