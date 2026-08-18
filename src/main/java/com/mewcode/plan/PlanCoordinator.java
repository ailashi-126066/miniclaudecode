package com.mewcode.plan;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public final class PlanCoordinator {
    private final PlanRepository repository;
    public PlanCoordinator(PlanRepository repository){this.repository=repository;}
    public PlanState create(String goal,List<PlanState.Step> steps){validate(steps);var p=new PlanState(java.util.UUID.randomUUID().toString(),goal,PlanState.Status.DRAFT,List.copyOf(steps),Instant.now());repository.save(p);return p;}
    public PlanState activate(){var p=require();var n=new PlanState(p.id(),p.goal(),PlanState.Status.ACTIVE,p.steps(),Instant.now());repository.save(n);return n;}
    public PlanState complete(String id,List<String> verification,List<String> changed){var p=require();List<PlanState.Step> steps=new ArrayList<>();boolean found=false;for(var s:p.steps()){if(s.id().equals(id)){found=true;if(s.requiresVerification()&&(verification==null||verification.isEmpty()))throw new IllegalStateException("verification evidence is required");s=new PlanState.Step(s.id(),s.description(),s.dependsOn(),s.acceptanceCriteria(),s.requiresVerification(),PlanState.StepStatus.COMPLETED,s.attempts(),new PlanState.Evidence(List.of(),verification==null?List.of():List.copyOf(verification),changed==null?List.of():List.copyOf(changed),"",Instant.now()));}steps.add(s);}if(!found)throw new IllegalArgumentException("unknown step: "+id);var status=steps.stream().allMatch(s->s.status()==PlanState.StepStatus.COMPLETED)?PlanState.Status.COMPLETED:p.status();var n=new PlanState(p.id(),p.goal(),status,List.copyOf(steps),Instant.now());repository.save(n);return n;}
    public PlanState fail(String id,String reason){var p=require();List<PlanState.Step> steps=new ArrayList<>();for(var s:p.steps()){if(s.id().equals(id)){int attempts=s.attempts()+1;s=new PlanState.Step(s.id(),s.description(),s.dependsOn(),s.acceptanceCriteria(),s.requiresVerification(),PlanState.StepStatus.FAILED,attempts,new PlanState.Evidence(List.of(),List.of(),List.of(),reason,Instant.now()));}steps.add(s);}var status=steps.stream().anyMatch(s->s.attempts()>=3)?PlanState.Status.BLOCKED:p.status();var n=new PlanState(p.id(),p.goal(),status,List.copyOf(steps),Instant.now());repository.save(n);return n;}
    public String status(){return repository.load().map(p->p.goal()+" ["+p.status()+"]\n"+p.steps().stream().map(s->s.id()+" "+s.status()+" - "+s.description()).reduce("",(a,b)->a+b+"\n")).orElse("No active plan");}
    private PlanState require(){return repository.load().orElseThrow(()->new IllegalStateException("no active plan"));}
    private static void validate(List<PlanState.Step> steps){if(steps==null||steps.isEmpty()||steps.size()>12)throw new IllegalArgumentException("plan must contain 1-12 steps");var ids=new HashSet<String>();for(var s:steps)if(!ids.add(s.id()))throw new IllegalArgumentException("duplicate step: "+s.id());for(var s:steps)if(!ids.containsAll(s.dependsOn())||s.dependsOn().contains(s.id()))throw new IllegalArgumentException("invalid dependency: "+s.id());}
}
