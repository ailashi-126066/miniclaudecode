package com.mewcode.plan;
import static org.junit.jupiter.api.Assertions.*;import java.nio.file.Path;import java.util.List;import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
class PlanCoordinatorTest{
 @TempDir Path dir;

 @Test void verificationGatesCompletion(){
  var c=new PlanCoordinator(new PlanRepository(dir));
  c.create("goal",List.of(new PlanState.Step("s1","implement",List.of(),List.of("tests pass"),true,PlanState.StepStatus.PENDING,0,null)));
  assertThrows(IllegalStateException.class,()->c.complete("s1",List.of(),List.of()));
  assertEquals(PlanState.Status.COMPLETED,c.complete("s1",List.of("mvn test passed"),List.of("A.java")).status());
 }

 @Test void storesPlanStateAndReadablePlanInOneMarkdownFile() throws Exception {
  var repository=new PlanRepository(dir);
  new PlanCoordinator(repository).create("single source",List.of(new PlanState.Step("s1","implement",List.of(),List.of(),false,PlanState.StepStatus.PENDING,0,null)));
  assertTrue(java.nio.file.Files.exists(dir.resolve(".mewcode/plans/active.md")));
  assertFalse(java.nio.file.Files.exists(dir.resolve(".mewcode/plans/active.json")));
  assertEquals("single source",repository.load().orElseThrow().goal());
  assertTrue(repository.readForModel().contains("# single source"));
 }
}
