package dev.deadstreets.core;

import java.util.SplittableRandom;

/** Run with: javac -d build/core src/main/java/dev/deadstreets/core/*.java src/test/java/dev/deadstreets/core/*.java
 * then: java -ea -cp build/core dev.deadstreets.core.CoreHarness
 */
public final class CoreHarness {
    private static int assertions=0;
    private static void check(boolean yes, String what) { if(!yes) throw new AssertionError(what); assertions++; }
    public static void main(String[] args) {
        var rules = new SpawnerCycle.Rules(2,5,30,60,16,60_000);
        var rnd = new SplittableRandom(1234);
        var c = new SpawnerCycle();
        var first = c.tick(1_000L,true,0,rules,rnd);
        check(first.firstWave() && first.spawnCount() >= 2 && first.spawnCount() <=5,"instant wave");
        long firstDelay = first.remainingSeconds();
        check(firstDelay>=30 && firstDelay<=60,"random interval");
        check(c.tick(2_000,true,4,rules,rnd).spawnCount()==0,"no repeat for player two");
        check(c.tick(5_000,false,4,rules,rnd).active()==false,"deactivation");
        check(c.tick(6_000,true,4,rules,rnd).spawnCount()==0,"no boundary reactivation abuse");
        check(c.tick(90_000,false,0,rules,rnd).spawnCount()==0,"inactive no spawn");
        check(c.tick(150_000,true,0,rules,rnd).firstWave(),"rearm after cooldown");
        c.tick(151_000,false,0,rules,rnd);
        var restored=SpawnerCycle.restore(c.save(151_000));
        check(restored.tick(152_000,true,0,rules,rnd).spawnCount()==0,"restart no instant duplication");
        var capped=new SpawnerCycle();
        check(capped.tick(1,true,16,rules,rnd).capped(),"max-nearby cap");
        check(capped.tick(2,true,12,rules,rnd).spawnCount()<=4,"cap after resume");
        check(DangerRating.calculate(200,Temperament.DOCILE).level()==DangerRating.Level.HIGH,"large docile horde still dangerous");
        check(DangerRating.calculate(5,Temperament.FRENZIED).level()==DangerRating.Level.LOW,"small frenzied horde not extreme");
        check(DangerRating.calculate(85,Temperament.AGITATED).level()==DangerRating.Level.HIGH,"mixed danger");
        for(var t:Temperament.values()) check(t.baseHearingRange()>0 && t.dangerMultiplier()>0,"temperament config");
        var range = new PopulationRoller.Range(2,10);
        int rolled=PopulationRoller.roll(54321L,"city7-district5-build8","RESIDENTIAL",range);
        check(rolled>=2 && rolled<=10,"range bound");
        check(rolled==PopulationRoller.roll(54321L,"city7-district5-build8","RESIDENTIAL",range),"deterministic per-build roll");
        var player=java.util.UUID.randomUUID();var horde=java.util.UUID.randomUUID();
        var gate=new HordeAlertGate(150,180_000L);
        check(!gate.observe(player,horde,175,1000),"outside alert range");
        check(gate.observe(player,horde,145,2000),"genuinely approaching horde");
        check(!gate.observe(player,horde,110,3000),"no repeated alert");
        check(!gate.observe(player,horde,145,4000),"receding group no alert");
        // Boundary-crossing builds must distribute each ordinal exactly once, even with negative chunks.
        var multi = new MetropolisFlag("world-a", 431L, "build-001", "RESIDENTIAL", -19, 50, -18, 25, 76, 35);
        var generous = new PopulationRoller.Range(64,64);
        var visited = new java.util.HashSet<Integer>();
        int total = 0;
        for (int cx=-2;cx<=1;cx++) for(int cz=-2;cz<=2;cz++) {
            var assigned=PopulationPlan.inChunk(multi,generous,cx,cz,256);
            for(var cnd:assigned) {
                check(cnd.chunkX()==cx && cnd.chunkZ()==cz, "chunk ownership");
                check(cnd.x() >= multi.minX() && cnd.x()<=multi.maxX() && cnd.z()>=multi.minZ() && cnd.z()<=multi.maxZ(),"footprint placement");
                check(visited.add(cnd.index()),"no ordinal duplicated across chunks");
                total++;
            }
            check(assigned.equals(PopulationPlan.inChunk(multi,generous,cx,cz,256)),"repeatable chunk assignment");
        }
        check(total==64 && visited.size()==64,"all 64 walkers assigned once");
        check(PopulationPlan.inChunk(multi,new PopulationRoller.Range(0,0),-1,-1,256).isEmpty(),"empty flag cannot generate zombies");
        check(PopulationPlan.activationKey(multi).equals(PopulationPlan.activationKey(multi)),"persistent marker key stable");
        var indoor=new MetropolisFlag("world-a",431L,"indoor-build","HIGHRISE",0,40,0,31,90,31,
                java.util.List.of(new MetropolisFlag.SpawnPoint(2,-1,2),new MetropolisFlag.SpawnPoint(18,-1,18)));
        var parsed=MetropolisFlag.parse(indoor.format());
        check(parsed.interiorPoints().size()==2 && parsed.interiorPointsProvided(),"v2 interior points round-trip");
        var noIndoor=MetropolisFlag.parse("BUILD\tworld-a\t431\tno-room\tHIGHRISE\t0\t40\t0\t15\t90\t15\t");
        check(PopulationPlan.inChunk(noIndoor,new PopulationRoller.Range(3,3),0,0,10).isEmpty(),"v2 empty interior list never falls back to outdoor placement");
        var insideA=PopulationPlan.inChunk(indoor,new PopulationRoller.Range(2,2),0,0,10);
        var insideB=PopulationPlan.inChunk(indoor,new PopulationRoller.Range(2,2),1,1,10);
        check(insideA.size()+insideB.size()==2,"interior points assigned to their owning chunks");
        check(insideA.stream().allMatch(cnd->cnd.y()==-1)&&insideB.stream().allMatch(cnd->cnd.y()==-1),"interior candidate columns retain runtime floor-resolution sentinel");

        try {PopulationPlan.inChunk(multi,generous,0,0,10);throw new AssertionError("missing limit");}
        catch(IllegalArgumentException expected){assertions++;}
        System.out.println("PASS " + assertions + " Dead Streets R03 core assertions, JDK " + Runtime.version());
    }
}
